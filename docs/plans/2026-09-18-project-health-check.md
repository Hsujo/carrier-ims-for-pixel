# 项目体检报告：Carrier IMS for Pixel（2026-09-18）

> 范围：`app/` 全部 Kotlin 源码（前端 Compose UI / ViewModel、后端 Shizuku 特权层）、Gradle 配置、Manifest、单元测试。
> 方式：静态通读 + 对照 AOSP 框架源码行为。本次环境无 Android SDK，未编译、未跑 `SupportRulesTest`；标注「需真机验证」的项目请在设备上复核。

---

## 0. 结论速览

| 维度 | 评级 | 一句话 |
|---|---|---|
| 后端：特权调用架构 | 🟠 中 | 每次特权操作 = 一次 `am startInstrumentation` 往返，且**在 UI 主线程执行**；全局互斥串行化，单次超时 15 s，失败还会用 Broker 再等 15 s |
| 后端：配置写入语义 | 🔴 高 | `CarrierConfigManager.overrideConfig` 是 **putAll 合并**语义，而 `buildBundle` 只写「开启」的 key → **关闭任何开关对系统都是空操作** |
| 后端：读回映射 | 🔴 高 | `5G+ 图标` 用 `containsKey` 判断，而 `getConfigForSubId` 永远带默认值 → **永远显示为开启** |
| 后端：网络探测 | 🔴 高 | targetSdk 36 默认禁止明文流量，`http://…/generate_204` 探测**必然失败**；探测串行，最坏 10–16 s |
| 后端：MCC 覆盖遗留路径 | 🔴 高 | `setCarrierTestOverride` 传空串而非 `null`，会把 IMSI/ICCID/GID/SPN 一并覆盖成空；该路径经旧配置/备份/开机恢复仍可触发 |
| 前端：状态管理 | 🟠 中 | ~40 个 `remember` 状态全在 Activity，旋转/深色切换即丢失并**重发所有网络请求**（GitHub、广告、探测） |
| 前端：日志页 | 🟠 中 | logcat 每行一次主线程切换 + 每次重组全量 `filter`，且进入一次后**永不停止** |
| 前端：资源生命周期 | 🟡 低 | WebView 未 `destroy()`；广告图全尺寸解码无缓存 |
| 测试 | 🟡 低 | 仅覆盖支付/广告规则；版本比较、配置映射、日志解析、Bundle 构建零覆盖 |

---

## 1. 后端（Shizuku 特权层 / ViewModel 业务）

### 1.1 架构与性能

**1.1.1 特权操作实际运行在 App 主线程**

`ShizukuProvider.startInstrumentation`（`ShizukuProvider.kt:279`）使用 `flags = 8`（`INSTR_FLAG_NO_RESTART`）。AOSP 在该标志下走 `instrumentWithoutRestart` → `ActivityThread.handleInstrumentWithoutRestart`，即**在已运行的 App 进程主线程上** `new` 出 `ImsModifier`/`ConfigReader` 等并调用 `onCreate()`。因此：

- `ImsModifier.onCreate` 的 `Thread.sleep(100)` 轮询（`ImsModifier.kt:197-212`，最多 5 s）和 `waitForShizukuBinderReady`（`ShizukuCompat.kt`，最多 2 s）都是**主线程 sleep**。同进程内 Shizuku binder 早已就绪，这段循环是从「独立进程」设计继承下来的死代码，但一旦触发就是 ANR 级卡顿。
- `getConfigForSubId`、`overrideConfig`、APN 的 `ContentResolver.insert/update/query`、`Settings.Global.putString`、`buildDumpText` 的反射（约 1000 个字段）全部阻塞主线程。
- 每次调用还要付出 `startInstrumentation` + `finishInstrumentation` + `finishInstrumentationWithoutRestart`（重建 `Instrumentation`、`ContextImpl`）的 AMS 往返成本，估计 20–100 ms/次。

**影响面**：选中一张 SIM 时 `LaunchedEffect(selectedSim, shizukuStatus, allSimList)`（`MainActivity.kt:626-667`）会串行发起 `readCarrierConfig` + `readImsRegistrationStatus`（选「所有 SIM」时每张卡一次），加上 `LaunchedEffect(shizukuStatus)` 的 `queryCaptivePortalConfig`、`updateShizukuStatus` 的 `readSimInfoList`（最多 3 次），首屏 5–8 次特权往返。四个 QS Tile 每次下拉通知栏各做 2–3 次（`QsTiles.kt:75-101`）。

**修复方案（按成本递增）**

1. 删除 `ImsModifier.onCreate` 里的 sleep 轮询，统一改为 `if (!Shizuku.pingBinder()) { 立即返回失败 }`；其余 Instrumentation 的 `waitForShizukuBinderReady()` 同样改成一次性检查。
2. 合并读操作：新增一个 `StateReader` Instrumentation，一次返回 `carrierConfig(keys)` + `isImsRegistered` + captive portal 三项，把首屏往返从 5–8 次压到 2 次。QS Tile 缓存 slot→subId 映射（用 `SubscriptionManager.addOnSubscriptionsChangedListener` 失效），避免每次下拉都 `readSimInfoList`。
3. 长期：改用 Shizuku `UserService`（shell uid 常驻进程，AIDL 直连），彻底摆脱主线程与 AMS 往返，天然支持并发读。这是最大的一笔重构，但也是唯一能同时解决「主线程执行」「全局互斥」「15 s 超时」三个问题的方案。

**1.1.2 超时与 Broker 重试把失败放大到 30 s**

- `INSTRUMENTATION_RESULT_TIMEOUT_MS = 15_000`（`ShizukuProvider.kt:43`）；`shouldRetryWithBroker` 对 `"empty result"` 也重试（`:335`），即超时后再用 `BrokerInstrumentation` 等 15 s。
- `BrokerInstrumentation` 与 `ImsModifier.applyOverrideConfig` 逻辑完全相同（同样 `startDelegateShellPermissionIdentity` + 反射 `overrideConfig`），没有任何提权差异，重试等价于「原样再来一次」。
- 互斥期间整个 App 的所有特权调用排队；`CancellationException` 分支还会 `NonCancellable` 再等 15 s（`:293-301`）。

**修复**：超时降到 5 s；`shouldRetryWithBroker` 去掉 `"empty result"`；如无独立提权价值，直接删除 `BrokerInstrumentation`（顺带消除 1.2.4 的 bug）。

**1.1.3 网络探测串行且 http 探测必然失败**

- Manifest 无 `android:usesCleartextTraffic` 也无 `networkSecurityConfig`，targetSdk 36 默认禁止明文 → `HttpURLConnection` 对 `http://` 直接抛 `IOException: Cleartext HTTP traffic … not permitted`，被 `isPortalUrlReachable` 的 `catch` 吞掉返回 `false`（`MainViewModel.kt:881-899`）。
  - `NETWORK_EXIT_SERVICE_URLS["联网验证"]` 是 http（`:93`）→「网络出口检测」里的「验证」项**永远 FAIL**。
  - `DEFAULT_CAPTIVE_PORTAL_TEST_URLS[0]` 是 http → 白白浪费一次 2.5 s 超时。
  - 若系统只覆盖了 http 地址（`isOverridden` 且 https 为空），`isPortalConfigReachable` 恒为 false → 误判 NEED_FIX。
- `isDefaultPortalCheckReachable` / `isPortalConfigReachable` 用 `any {}` 串行，最坏 4×2.5 s = 10 s；`checkNetworkExit` 串行 4 个请求，最坏 16 s+。`queryCaptivePortalFixState()` 在每次 Shizuku 变 READY、每次修复后、Dump、诊断时都会跑。

**修复**

```xml
<!-- app/src/main/res/xml/network_security_config.xml -->
<network-security-config>
    <base-config cleartextTrafficPermitted="false" />
    <!-- 仅对联网验证探测域名放行明文，其余保持禁止 -->
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">connectivitycheck.gstatic.cn</domain>
        <domain includeSubdomains="false">connectivitycheck.gstatic.com</domain>
    </domain-config>
</network-security-config>
```

Manifest `<application>` 增加 `android:networkSecurityConfig="@xml/network_security_config"`。

探测并行化（`MainViewModel.kt`）：

```kotlin
private suspend fun anyPortalReachable(urls: List<String>): Boolean = withContext(Dispatchers.IO) {
    if (urls.isEmpty()) return@withContext false
    coroutineScope {
        urls.map { url -> async { isPortalUrlReachable(url) } }.awaitAll().any { it }
    }
}

private suspend fun isDefaultPortalCheckReachable(): Boolean =
    anyPortalReachable(DEFAULT_CAPTIVE_PORTAL_TEST_URLS)

private suspend fun isPortalConfigReachable(httpUrl: String, httpsUrl: String): Boolean {
    val targets = buildList {
        if (httpUrl.isNotBlank()) add(httpUrl)
        if (httpsUrl.isNotBlank()) add(httpsUrl)
    }
    return anyPortalReachable(targets)
}
```

`checkNetworkExit` 里把 `fetchJsonObject(ipapi)` 与三个可达性探测同样用 `async` 并行，总耗时从「累加」变为「取最大」。另外 `ipapi.co` 免费额度 1000 次/日且在大陆网络不稳定，建议加备用源或允许失败时仍显示可达性结果（当前 `runCatching` 包住整体，ipapi 失败会连带三项探测结果一起丢掉）。

**1.1.4 Logcat 采集：逐行主线程切换、O(n) 删头、永不停止**

- `LogcatRepository.kt:43-53`：每读一行 `withContext(Dispatchers.Main) { _logs.add(...) }`，满 2000 后 `removeAt(0)` 是 `SnapshotStateList` 的 O(n) 搬移；系统 logcat 常见几百行/秒，主线程被淹没。
- `LogcatViewModel.init { startLogcat() }`（`LogcatViewModel.kt:19-21`）只在 `Application.onTerminate`（真机永不调用）停止 → 打开一次日志页后，logcat 子进程 + 主线程写入在整个进程生命周期内持续运行。

**修复**

```kotlin
// LogcatRepository.kt（保留原有注释与结构，仅替换读取循环）
val pending = ArrayList<LogEntry>(256)
var lastFlush = SystemClock.uptimeMillis()
while (isActive && bufferedReader.readLine().also { line = it } != null) {
    if (!isCapturing) break
    line?.let { pending += LogEntry.parseLog(it) }
    val now = SystemClock.uptimeMillis()
    if (pending.size >= 200 || now - lastFlush >= 100) {
        val batch = ArrayList(pending); pending.clear(); lastFlush = now
        withContext(Dispatchers.Main) {
            _logs.addAll(batch)
            val overflow = _logs.size - MAX_LINES
            if (overflow > 0) _logs.removeRange(0, overflow)
        }
    }
}
```

`LogcatViewModel.onCleared()` 调 `LogcatRepository.stopAndClear()`（或引用计数）；`ProcessBuilder` 加 `-T 500` 避免开机后首次拉取整段历史缓冲。

### 1.2 计算 / 逻辑 Bug

**1.2.1 🔴 关闭开关对系统是空操作（putAll 合并语义）**

- `ImsModifier.buildBundle`（`ImsModifier.kt:48-181`）只对 `enableXxx == true` 的功能 `putBoolean(key, true)`，关闭时**不写 key**。
- AOSP `CarrierConfigLoader.overrideConfig`：`currentOverrides[phoneId].putAll(overrides)`，即**合并**，只有 `overrides == null` 才清空。
- 结果：用户把 VoLTE/VoNR/5G NR 等从 ON 拨到 OFF → 系统覆盖项里的 `true` 原样保留；下次 `loadCurrentConfiguration` 从系统读回又变 ON；开机自动恢复读本地 prefs（false）→ 仍然不写 key → 依旧关不掉。唯一能真正关闭的途径是「重置配置」。
- TikTok 修复之所以「好用」，恰恰是因为它在关闭方向也显式写了 `sim_country_iso_override_string="cn"`。

**修复（推荐 A：全量应用 = 先清后写，Tile 单键 = 合并）**

```kotlin
// ImsModifier.kt Companion
const val BUNDLE_REPLACE_ALL = "replace_all"

// ImsModifier.overrideConfig()
val replaceAll = arguments.getBoolean(BUNDLE_REPLACE_ALL, false)
arguments.remove(BUNDLE_REPLACE_ALL)
...
for (subId in subIds) {
    val values = baseValues?.let { PersistableBundle(it) }
    Log.i(TAG, "overrideConfig for subId $subId with values $values")
    if (replaceAll && !reset) {
        // overrideConfig 为 putAll 合并语义：先清空既有覆盖，保证“关闭”能回到运营商默认
        applyOverrideConfig(cm, subId, null, preferPersistent = preferPersistent)
    }
    applyOverrideConfig(cm, subId, values, preferPersistent = preferPersistent)
    ...
}
```

`MainViewModel.onApplyConfiguration` 里 `bundle.putBoolean(ImsModifier.BUNDLE_REPLACE_ALL, true)`；QS Tile 的 `updateCarrierConfigBoolean` 不加该标志，保持单键合并。`BrokerInstrumentation` 若保留需同步处理。

备选 B：对关闭的功能显式写 `false`（与 Tile 当前行为一致），但 5G+ 图标那组 int/string/array 无法用 `false` 表达「恢复默认」，因此 A 更完整。

**1.2.2 🔴 5G+ 图标读回永远为开启**

`FeatureConfigMapper.kt:117-124` 用 `bundle.containsKey(...)` 判断；`getConfigForSubId` 返回的是 `sDefaults` + 运营商配置 + 覆盖项的**完整 bundle**，这几个 key 永远存在（默认 0 / "" / 空数组）→ `FIVE_G_PLUS_ICON` 恒为 true。

**修复**：改为按值比对，并把常量从 `ImsModifier` 抽到共享的 `CarrierConfigKeys` object 避免两处硬编码漂移。

```kotlin
val fiveGPlusIconEnabled =
    bundle.getInt(KEY_NR_ADVANCED_THRESHOLD_BANDWIDTH_KHZ, 0) == CarrierConfigKeys.NR_ADVANCED_THRESHOLD_KHZ_FOR_5GA &&
        bundle.getString(KEY_5G_ICON_CONFIGURATION) == CarrierConfigKeys.NR_ICON_CONFIGURATION_5GA
```

顺带：`buildBundle` 里 5G+ 与 5G 阈值嵌套在 `if (enable5GNR)` 内（`ImsModifier.kt:145-179`），UI 却允许在 5G NR 关闭时单独打开它们 → 乐观更新显示 ON、实际未写。要么 UI 在 5G NR 关闭时禁用这两个开关，要么把它们从嵌套里拆出来独立写入。

**1.2.3 🔴 `setCarrierTestOverride` 传空串，且遗留路径仍可触发**

- `ImsModifier.applyCarrierTestMccOverride`（`:366-377`）对 imsi/iccid/gid1/gid2/plmn/spn 传 `""`。框架侧 `IccRecords.getIMSI()` 的判断是 `fakeImsi != null` → 空串被当作有效覆盖值，SIM 的 IMSI、ICCID、GID、SPN 全部变成空。这与 README 中「国家码修改不稳定」的观察一致，实际是自伤。
- `clearCarrierTestOverride`（`:381-422`）：AOSP `ITelephony` 没有 `clearCarrierTestOverride`，反射恒为 null → 走 fallback 再次用当前 MCCMNC + 空串覆盖，**从未退出测试模式**。
- 虽然 STRING 类型功能的 UI 已被 `removeAll { it.valueType == STRING }`（`MainActivity.kt:3592-3597`）隐藏，但 `countryMccOverride` 仍会从三处进入：旧版本残留的 `__country_mcc_override__` 偏好（`loadSavedCountryMccOverride`）、`ConfigBackupSnapshot.countryMccOverride` 恢复、以及开机自动恢复。每次拨动任何开关都会重新执行一遍。

**修复**：
1. 立即：未覆盖的参数改传 `null`（与 CTS 用法一致）；`clearCarrierTestOverride` 改为全 `null` 调用 + `resetIms`。
2. 根治：既然功能已下线，`loadConfiguration`/`loadSavedCountryMccOverride` 时顺手 `remove(COUNTRY_MCC_PREF_KEY)`，`parseConfigBackup` 忽略 `country_mcc_override`，`onApplyConfiguration` 删除 `countryMccOverride` 参数，`ImsModifier` 删除 `applyCarrierTestMccOverride` 整段。

**1.2.4 🟠 Broker 路径把内部 key 写进 CarrierConfig，并静默丢弃 MCC 覆盖**

`BrokerInstrumentation.kt:44-57` 只移除 `select_sim_id/reset/prefer_persistent`，`country_mcc_override`、`country_mnc_hint` 会被 `toPersistableBundle` 当作 `putString` 写入运营商配置覆盖项（可能被持久化）；同时 Broker 不执行 `setCarrierTestOverride`，却返回成功。若保留 Broker，需和 `ImsModifier` 共用同一份「剥离内部 key」逻辑。

**1.2.5 🟠 开机恢复：boot count 过早持久化**

`checkAndMarkBootChanged`（`MainViewModel.kt:1244-1259`）在 ViewModel 构造时就写入新 boot count。若用户开机后先打开本 App、Shizuku 尚未启动，`pendingConfigRestoreAfterBoot` 只存在于内存；进程被杀后再启动，boot count 已相等 → 本次开机永远不会恢复。

**修复**：把 `runtimePrefs.edit { putInt(KEY_LAST_BOOT_COUNT, …) }` 移到 `maybeRestoreSavedConfigurationAfterBoot` 成功（或确认无配置需恢复）之后。

**1.2.6 🟠 QS Tile 切换与本地 prefs 不同步**

`BaseVoLTETileService.toggleVoLTE` 只改系统覆盖项，不更新 `sim_config_<subId>` → 下次开机 `restoreSavedConfigurationAfterBoot` 用旧 prefs 覆盖回去，用户在 Tile 上的操作被静默回滚。修复：Tile 成功后同步写 prefs（把 `saveConfiguration` 的单键更新抽到 `ShizukuProvider` 旁的一个共享 store）。

**1.2.7 🟠 附加页对「另一张 SIM」应用的是当前 SIM 的功能快照**

`MainActivity.kt:1241-1266`：当主页选中「所有 SIM」时，附加页 `extraSelectedSim` 取第一张真实 SIM，但 `onTikTokFixChange → handleFeatureSwitchChange` 用的 `committedFeatureSwitches` 仍是「所有 SIM/默认值」那份 → 会把这份快照 + TikTok 状态整体写到第一张 SIM 并 `saveConfiguration`。修复：附加页切换 TikTok 时先按 `extraSelectedSim.subId` `loadCurrentConfiguration`/`loadConfiguration` 得到该卡自己的 map 再合并。

**1.2.8 🟡 「所有 SIM」配置从不被开机恢复**

`saveConfiguration(-1, …)` 写到 `sim_config_-1`，但 `restoreSavedConfigurationAfterBoot` 只遍历 `subId >= 0`。要么把 -1 的配置广播到各 subId 的 prefs，要么在恢复时也处理 -1。

**1.2.9 🟡 APN：兜底查询跨 SIM 匹配**

`ApnModifier.findExistingApnId`（`:136-139`）第二条查询不带 `sub_id`，双卡同运营商时会 `update` 到另一张卡的 APN 行并把 `sub_id` 改掉；`type` 精确匹配又使 `"default,supl,ims"` 与 `"default,ims,supl"` 视为不同而重复插入。建议：只保留带 `sub_id` 的查询；`type` 比较前按逗号拆分排序。

**1.2.10 🟡 其他小项**

- `loadSimListInternal` 的 `retryCount = if (shizukuReady) 3 else 1`（`MainViewModel.kt:337`）：此处 `shizukuReady` 必为 true，死条件。
- `readSimInfoList` 的 `it.displayName.toString()`：`displayName` 可为 null → 显示 "null"。
- `LogcatViewModel.exportLogFile`：分隔线后缺 `\n`（`:35`），且每行 `appendText` 打开一次文件（2000 次 open/close），改为 `bufferedWriter().use { }`。
- `SimpleDateFormat` 在 `nowShortTime`/`backupSubtitle`/`appendSwitchFailureLog` 中每次新建；诊断流每行一次，可缓存或改 `DateTimeFormatter`。
- `isChinaDomesticSim`、MCC→ISO 映射在 `MainViewModel`、`MainActivity`、`countryIsoOptions` 三处各写一份，已出现漂移风险。

---

## 2. 前端（Compose UI）

### 2.1 性能

**2.1.1 🟠 状态全部在 Activity 的 `remember` 中，配置变更即重置并重发网络**

`MainActivity.kt:487-526` 约 40 个 `remember { mutableStateOf }`（无 `rememberSaveable`），Manifest 未声明 `configChanges`。旋转、深色模式切换、语言切换、分屏（Fold/Tablet 常见）都会重建 Activity，于是：
- `LaunchedEffect(Unit)`（`:558-567`）重新请求 GitHub Releases（两个 URL），`:568-577` 重新拉广告并**再次弹出首页广告**（`intervalHours == 0` 时）；
- `LaunchedEffect(shizukuStatus)` 重新跑 captive portal 探测；
- 已输入的打赏留言、合作表单、网络出口结果、诊断日志全部丢失；
- `SupportPaymentDialog` 的 WebView 重建并重新加载支付页，可能产生重复订单。

**修复**：把「一次性加载的数据」（更新信息、广告、备份列表、captive portal 状态、网络出口结果）和「跨 Tab 保留的输入」迁到 `MainViewModel` 的 `StateFlow`；纯 UI 状态（`selectedTab`、`selectedSim.subId`）用 `rememberSaveable`。

**2.1.2 🟠 日志页：每次重组全量过滤 + LazyColumn 无 key**

`LogcatActivity.kt:74-76` 每次重组执行 `logs.filter {}`（最多 2000 项），配合 1.1.4 每行一次重组 → O(n) 每行。`components/Logcat.kt:65` `items(logs)` 无 `key`，删头后整列重新 diff。

**修复**：`LogEntry` 增加自增 `id: Long`；`items(filtered, key = { it.id })`；过滤用

```kotlin
val filtered by remember(filter) { derivedStateOf { logs.filter { it.level.isLevelEnabled(filter) } } }
```

`derivedStateOf` 只在 `logs` 快照或 `filter` 变化时重算，且批量写入后每 100 ms 只算一次。

**2.1.3 🟠 Dump 页：单个 `Text` 承载整份配置 + 每次按键全量过滤**

`DumpActivity.kt:95-169`：几十 KB 的字符串塞进一个 `Text`，文本布局 O(n)；每输入一个字符都 `lineSequence().filter().joinToString()` 后重新布局整段。两个分支（预置文本 / 加载文本）代码完全重复。

**修复**：`val lines = remember(text) { text.lines() }`，`val visible = remember(lines, filterText) { … }`，用 `LazyColumn(items = visible)` 逐行渲染；合并两个分支。

**2.1.4 🟡 `FeaturesCard` 每次重组重建并排序功能列表**

`MainActivity.kt:3592-3604`：`Feature.entries.toMutableList()… sortedWith(compareBy(...))` 在动画（进度条、Switch）期间每帧执行。包一层 `remember(isSelectAllSim, selectedSim?.subId, showTikTokFix)`。

**2.1.5 🟡 广告图全尺寸解码、无缓存**

`loadRemoteAdImage`（`:2791-2808`）`BitmapFactory.decodeStream` 无 `inSampleSize`，一张 2000×3000 的图解码后 24 MB；`produceState` 绑定在 composable 上，切换 Tab 离开再回来就重新下载。修复：先 `inJustDecodeBounds` 取尺寸，按目标宽度算 `inSampleSize`；结果放进 ViewModel 的 `LruCache<String, Bitmap>`。

### 2.2 逻辑 / 资源 Bug

**2.2.1 🟠 WebView 从不销毁**

`SupportPaymentDialog`（`:2968-3021`）`AndroidView` 无 `onRelease`，对话框关闭后 WebView 及其渲染进程泄漏。加 `onRelease = { it.stopLoading(); it.destroy() }`。

**2.2.2 🟠 应用内更新：安装权限流程断裂 + 同名文件导致 1009**

- `installDownloadedApk`（`:1709-1719`）无权限时跳设置页后直接 `return`，`pendingUpdateDownloadId` 已在 receiver 中置 -1 → 用户授权回来后没有任何东西重新触发安装，只能重新下载。修复：保留 `downloadId` 到字段，`onResume` 检查 `canRequestPackageInstalls()` 后续装。
- `startUpdateDownload`（`:1655-1661`）目标文件已存在（上次未安装成功）时，`DownloadManager` 报 `ERROR_FILE_ALREADY_EXISTS`（reason 1009）。入队前先删除同名文件。

**2.2.3 🟡 Tab 切换丢失输入**

`SupportPage`/`CooperationPage` 内部 `remember` 的表单字段随 `if (selectedTab == …)` 离开组合而清空。改 `rememberSaveable` 或提升到 ViewModel。

**2.2.4 🟡 死代码约 400 行**

STRING 类型功能已从列表移除，`CountryIsoFeatureItem`、`StringFeatureItem`、`sanitizeMccInput`、`findCountryIsoOptionByMcc`、`currentCountryOverrideSummary`、`countryIsoApplySignalBySubId`、`onTextFeatureCommit` 等全部不可达；`Feature.CARRIER_NAME/COUNTRY_ISO` 仍参与 prefs 读写与备份。与 1.2.3 一并清理。

**2.2.5 🟡 `HomeStatusCard.onRefresh` 与 `SystemInfoCard.onRefresh` 逻辑重复且用了旧值**

两处都是 `viewModel.updateShizukuStatus()` 后立刻 `if (shizukuStatus == READY)` 判断已组合的旧值；抽成一个 `refreshShizukuAndSimList()` 挂到 ViewModel。

---

## 3. 构建 / 依赖 / 测试

- `material3 = 1.5.0-alpha11`：`MaterialExpressiveTheme`、`HorizontalFloatingToolbar` 均为实验 API，生产包依赖 alpha 有升级断裂风险；至少固定到同一 BOM 内的 stable/beta。
- 单元测试仅 `SupportRulesTest`。零覆盖但最该有测试的纯逻辑：
  - `parseVersion`/`isVersionNewer`/`toDisplayVersion`（目前是 `MainActivity` 私有函数，需抽成 `VersionRules` object）；
  - `FeatureConfigMapper.fromBundle`（建议改为 `fromMap(Map<String, Any?>)`，`Bundle` 只在调用处转换，便于 JVM 测试）；
  - `ImsModifier.buildBundle` 的 key 集合（同样用 `Map` 中间层）；
  - `LogEntry.parseLog` 的 threadtime 解析（含 `--------- beginning of main` 行）。
- CI 只在 `release` 分支跑 `assembleRelease`，不跑 `test`；建议 PR 触发 `./gradlew :app:testDebugUnitTest lint`。

---

## 4. 建议的修复顺序

| 优先级 | 项目 | 预估改动 |
|---|---|---|
| P0 | 1.2.1 关闭开关空操作（`BUNDLE_REPLACE_ALL`） | ImsModifier + MainViewModel 各 ~10 行 |
| P0 | 1.2.2 5G+ 读回按值比对 | FeatureConfigMapper ~5 行 + 常量抽取 |
| P0 | 1.1.3 network_security_config + 探测并行 | 新增 1 个 xml，MainViewModel ~30 行 |
| P0 | 1.2.3 `setCarrierTestOverride` 传 null / 下线遗留路径 | ImsModifier、MainViewModel、备份解析 |
| P1 | 1.1.4 + 2.1.2 logcat 批量写入、`onCleared` 停止、key/derivedStateOf | Repository、ViewModel、Activity |
| P1 | 2.1.1 状态迁移到 ViewModel / `rememberSaveable` | MainActivity 结构性调整 |
| P1 | 1.2.5 boot count 延后写入；1.2.6 Tile 同步 prefs | 各 ~10 行 |
| P1 | 1.1.2 超时 5 s、去掉 Broker 空结果重试 | ShizukuProvider |
| P2 | 2.2.1 WebView 销毁；2.2.2 更新流程；2.1.3 Dump 页 LazyColumn | 局部 |
| P2 | 1.1.1-2 合并读操作 / QS 缓存 | 新增 1 个 Instrumentation |
| P3 | 死代码清理、重复逻辑合并、测试补强、CI 跑 test | 持续 |
| 长期 | 1.1.1-3 迁移到 Shizuku UserService | 重构 |

---

## 5. 需真机验证的假设

1. `INSTR_FLAG_NO_RESTART` 在 Pixel Android 14–16 上确实走 `instrumentWithoutRestart`（可在 `ImsModifier.onCreate` 打印 `Looper.myLooper() == Looper.getMainLooper()` 与 `Process.myPid()` 确认）。
2. `overrideConfig` 的 putAll 合并行为：在真机上先开启 VoNR、再关闭、然后 `adb shell dumpsys carrier_config` 查看 `vonr_enabled_bool` 是否仍为 true。
3. `setCarrierTestOverride` 传空串后的 `getSubscriberId()` 表现：`adb shell dumpsys telephony.registry` / `dumpsys phone` 观察 IMSI 是否为空。
4. http 探测在 targetSdk 36 下的 `IOException`：logcat 搜索 `Cleartext HTTP traffic`。
