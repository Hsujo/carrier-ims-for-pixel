# 项目复检报告：Carrier IMS for Pixel（2026-09-25）

> 范围：`app/` 全部 Kotlin 源码、Gradle 配置、Manifest、资源与单元测试；对照 2026-09-18 体检报告逐项复核并处理。
> 方式：静态通读 + 对照 AOSP 行为；本次环境有 Android SDK（platform 36 / build-tools 36），已完成 `testDebugUnitTest`、`assembleDebug`、`assembleRelease`（R8）构建。**没有真机**，标注「需真机验证」的项目请在设备上复核。
> 分支：`claude/sweet-allen-656t9c`，基线提交 `e9035ac`。
> 2026-09-26 追加：合并此前遗漏的 5G 开发线，并修复 Android 17 上开关打不开的问题，见第 8 节。

---

## 0. 结论速览

| 维度 | 09-18 评级 | 本次处理 | 当前 |
|---|---|---|---|
| 原项目广告 / 打赏 / 合作残留 | — | 页面、弹窗、网络请求、远程服务配置全部移除；应用内更新一并移除 | ✅ |
| 后端：配置写入语义（关闭开关无效） | 🔴 高 | 应用完整配置时先清空该 SIM 覆盖再写入 | ✅ 需真机验证 |
| 后端：5G+ 图标读回永远开启 | 🔴 高 | 改为比对写入的阈值与图标配置 | ✅ 有单测 |
| 后端：网络探测明文被禁 / 串行 | 🔴 高 | 仅对 gstatic / google 域名放行明文；探测并行 | ✅ |
| 后端：MCC 覆盖遗留路径 | 🔴 高 | 路径移除，重置时仍清理历史覆盖，回退调用改传 `null` | ✅ |
| 后端：特权调用架构 | 🟠 中 | 移出主线程、去掉 sleep 轮询与 Broker 重试、合并读取 | 🟡 仍为串行 Instrumentation |
| 前端：状态管理 | 🟠 中 | 配置变更不重建；网络态移入 ViewModel | 🟡 Activity 仍持有较多状态 |
| 前端：日志页 | 🟠 中 | 批量刷新、derivedStateOf 过滤、离开即停止 | ✅ |
| 前端：资源生命周期 | 🟡 低 | WebView 与广告图随广告/打赏页一起删除 | ✅ |
| 测试 | 🟡 低 | 删除支付/广告用例，新增 5G+ 读回、MCC/MNC 规范化用例 | 🟡 Bundle 构建仍无覆盖 |

代码量：`app/src/main/java` 从 9 459 行降到 6 704 行（`MainActivity.kt` 4 342 → 2 375 行，`MainViewModel.kt` 1 393 → 1 246 行），共 59 个文件、+698 / −4 893 行。

---

## 1. 移除的原项目残留

| 内容 | 处理 |
|---|---|
| 底栏「支持作者」「商务合作」两页 | 删除，底栏只剩 IMS / 附加功能 / 关于 |
| 首页广告弹窗、合作页广告卡片（Muggle Leads 广告位接口） | 删除，含远程图片加载与展示频控 |
| DoDoPay 打赏（WebView 支付弹窗）、满 100 去广告验证、打赏留言列表 | 删除 |
| 商务合作表单（提交到 leads 平台） | 删除 |
| `BuildConfig.AD_API_BASE_URL` / `DODOPAY_*` / `BUSINESS_*`、`gradle.properties` 中的支付地址 | 删除 |
| 应用内检查更新 / 下载安装（指向 ryfineZ Release）、`REQUEST_INSTALL_PACKAGES`、安装包清理接收器 | 删除；仓库与 Issue 链接改为 Hsujo 分叉 |
| 旧版本残留数据（`ad_state` 偏好、`ad_free`、`support_client_ref`） | 启动时清理 |
| 商业化改版设计文档、支付/广告单元测试 | 删除 |

---

## 2. 后端（Shizuku 特权层 / ViewModel）

### 2.1 配置写入语义 ✅

- **问题**：`CarrierConfigManager.overrideConfig` 在 phone 进程里对已有覆盖做 `putAll`，而 `ImsModifier.buildBundle` 只写「开启」的 key，关闭任何开关都不会撤销之前写入的 `true`。
- **处理**：新增 `ImsModifier.BUNDLE_REPLACE`。`MainViewModel.onApplyConfiguration` 置为 true，`ImsModifier` 对每个 subId 先 `overrideConfig(subId, null, persistent)` 清空，再写入完整配置（沿用 persistent → 非 persistent 回退）。QS 图块的单项切换仍用合并语义，不会清掉其它开关。
- **行为变化**：关闭开关 = 回到运营商默认值，而不是强制写 `false`。若运营商本身支持某项（例如 VoLTE 默认可用），关闭后重新读回仍会显示开启，这是真实的系统状态。
- **需真机验证**：关闭 VoWiFi / VT / 5G+ 后 `adb shell dumpsys carrier_config` 中对应 key 恢复默认；一次应用会触发两次配置变更广播，确认 IMS 不会反复掉注册。

### 2.2 5G+ 图标读回 ✅

- `ConfigReader` 读的是 `getConfigForSubId` 的完整配置，每个 key 都带默认值，`containsKey` 永远为真。
- 改为 `FeatureConfigMapper.isFiveGPlusIconApplied(阈值, 图标配置)`，与写入常量（110 000 kHz、`5g_icon_configuration_string`）比对；常量改由 `FeatureConfigMapper` 统一提供，`ImsModifier` 引用。新增 `FeatureConfigMapperTest`。

### 2.3 网络探测 ✅

- 新增 `res/xml/network_security_config.xml`，只对 `gstatic.cn`、`gstatic.com`、`google.cn`、`google.com`（含子域）放行明文，`http://connectivitycheck.gstatic.cn/generate_204` 探测恢复可用。
- 默认探测地址（4 个）、当前配置地址（2 个）、网络出口检测（IP 查询 + 3 项可达性）全部改为并行，最坏耗时从逐个累加（10–16 s）降到单次超时（2.5 s / 4 s）。
- 其它厂商自定义的明文探测地址仍会被拦截，此时状态回落为「需要修复」，不影响修复 / 恢复操作。

### 2.4 MCC 覆盖遗留路径 ✅

- `setCarrierTestOverride(subId, mccmnc, "", "", …)` 会把 IMSI / ICCID / GID1 / GID2 / PLMN / SPN 覆盖成空串。MCC 输入界面早已被 `FeaturesCard` 过滤掉，但保存值、备份恢复、开机恢复仍会走这条路径。
- 处理：删除 `applyCarrierTestMccOverride` 与所有 `countryMccOverride` 传递链（ViewModel、备份、开机恢复、诊断），保存时不再写入 `__country_mcc_override__`；「重置配置」仍调用 `clearCarrierTestOverride` 清除历史覆盖，其回退调用的 6 个空串改为 `null`。
- 同时删除了不可达的国家码 / 运营商名称输入 UI（约 600 行）与 29 个对应字符串。`Feature` 枚举保持不变，兼容已保存的偏好和备份。

### 2.5 特权调用架构 🟡

已处理：
- **主线程执行**：新增 `BackgroundInstrumentation`，`onCreate`（主线程）只保存参数并 `start()`，实际工作在 Instrumentation 自带线程的 `onStart()` 里执行；后台线程异常兜底 `finish()`，避免崩溃进程。
- **sleep 轮询**：`ImsModifier` 最长 5 s、其它 Instrumentation 最长 2 s 的 `Thread.sleep` 轮询改为一次性 `Shizuku.pingBinder()` 检查。
- **Broker 重试**：删除 `BrokerInstrumentation`（与 `ImsModifier` 逻辑重复，且 `ImsModifier` 已有 persistent 回退），失败不再额外等待 15 s。
- **超时**：结果等待 15 s → 10 s；AMS 调用在 `Dispatchers.IO + NonCancellable` 中执行。
- **往返次数**：选卡 / 重置后通过一次 `ConfigReader` 调用同时拿到配置和 IMS 注册状态（原来两次）；QS 图块在 Android 14+ 用 `SubscriptionManager.getSubscriptionId(slot)` 取 subId，不再每次下拉都读 SIM 列表。

仍存在：
- 全局互斥 + 每次 `am startInstrumentation` 往返的模式没变，特权调用仍串行。按约定本次不做 Shizuku `UserService` 重构，列为后续最主要的性能项。
- 「所有 SIM」模式下仍按卡逐个读取 IMS 状态。

---

## 3. 前端

### 3.1 状态管理 🟡

- `MainActivity` / `LogcatActivity` / `DumpActivity` 声明 `configChanges`（方向、尺寸、深浅色等），旋转和深浅色切换不再重建 Activity，也不会重新发起特权调用；`BaseActivity.onConfigurationChanged` 重新应用系统栏样式。
- 网络验证状态、网络出口检测结果、配置备份列表移入 `MainViewModel` 的 `StateFlow`；网络验证状态只在 Shizuku 变为就绪时查询一次。底栏选中页改用 `rememberSaveable`。
- 仍存在：功能开关、选中 SIM、诊断等约 20 个 `remember` 状态仍在 Activity 中；语言切换等未声明的配置变更仍会重建并重新读取当前 SIM 配置。

### 3.2 日志页 ✅

- 解析留在 IO 线程，每 200 ms 批量提交主线程（原为每行一次 `withContext(Main)`），超过 2 000 行一次性 `removeRange` 裁剪（原为每行 `removeAt(0)`）。
- 过滤结果用 `derivedStateOf` 缓存；「滚动到底」改用过滤后的列表下标。
- 离开日志页即停止 `logcat` 进程；再次进入时 logcat 会先输出 logd 缓冲区，已有日志不丢。会话标识避免快速重进时旧会话误清理新进程。
- 导出改用 `bufferedWriter`（原为每行 `appendText` 打开一次文件），并在主线程取快照；修复无内容行导出为空的问题。

### 3.3 资源生命周期 ✅

WebView（打赏支付页）与全尺寸广告图解码随对应功能一起删除。

---

## 4. 新发现

| 问题 | 处理 |
|---|---|
| `LogcatActivity` 无必要 `exported="true"`，任何应用都能拉起并启动日志抓取 | 改为 `exported="false"` |
| 日志导出逐行 `appendText`、`removeAt(0)` O(n) | 已处理（见 3.2） |
| 调试包 61 MB：`material-icons-extended` 未经 R8 裁剪，而项目只用到其中 3 个图标（`Cached`、`ContentCopy`、`FilterList`） | 未处理，建议改为矢量资源并移除依赖，可明显缩短调试构建与安装时间；release 包由 R8 裁剪，不受影响 |
| `values-*` 下 17 种语言的翻译被 `localeFilters`（仅 en / zh-rCN）排除，不会进 APK | 未处理，仅仓库体积问题 |
| `strings.xml` 中约 30 个字符串、`colors.xml` 中 7 个颜色早已无引用（lint `UnusedResources`） | 未处理，release 构建会被资源收缩移除 |
| 诊断输出与 `serviceSummary` 中有硬编码中文 | 未处理 |

---

## 5. 后续建议（按收益排序）

1. **Shizuku UserService 重构**：以 shell uid 常驻进程 + AIDL 直连替换 Instrumentation，一次性解决串行互斥、AMS 往返与超时问题，需真机回归。
2. 把 `MainActivity` 剩余的开关 / 选卡 / 诊断状态迁入 `MainViewModel`，拆分 2 000+ 行的单文件。
3. 移除 `material-icons-extended`（见第 4 节）。
4. 引入 Robolectric，覆盖 `ImsModifier.buildBundle`、`FeatureConfigMapper.fromBundle` 与备份 JSON 读写。

---

## 6. 需真机验证清单

- [ ] 关闭 VoWiFi / VT / 5G+ 等开关后，`dumpsys carrier_config` 中对应 key 回到运营商默认值；IMS 注册在一次应用后保持稳定。
- [ ] 5G+ 图标开关：开启后读回为开，关闭并重新进入后读回为关。
- [ ] 附加功能页「网络验证」状态在国内网络下正确显示「正常 / 可恢复 / 需修复」。
- [ ] Android 14+ 上 SIM1 / SIM2 的 VoLTE 与 IMS 状态图块对应正确卡槽；双卡互换后仍正确。
- [ ] 首屏与切换 SIM 无卡顿；logcat 中 `ConfigReader` 等日志的线程名为 `Instr: …`。
- [ ] 旋转屏幕、切换深浅色后界面状态保留且状态栏图标颜色正确。
- [ ] 曾设置过 MCC 覆盖的设备执行一次「重置配置」后 SIM 信息恢复正常。

---

## 7. 验证记录

| 命令 | 结果 |
|---|---|
| `./gradlew :app:testDebugUnitTest` | 通过（`ToolRulesTest` 3 项、`FeatureConfigMapperTest` 3 项） |
| `./gradlew :app:assembleDebug` | 通过 |
| `./gradlew :app:assembleRelease`（R8 + 资源收缩） | 通过 |
| 残留检索 `dodopay / 3jiezhiwai / commercial / ad_free / RELEASES_LATEST / ryfineZ` | 源码与 `gradle.properties` 中仅剩清理旧数据用的 `ad_free` 键名 |
| release APK 体积（与基线 `e9035ac` 同环境构建对比） | 3 041 766 → 2 823 638 字节（−7.2%） |
| `./gradlew :app:lintDebug` | 119 errors / 64 warnings，均为既有问题：`MissingTranslation`（17 种语言未同步翻译）、`LocalContextGetResourceValueCall`、`MissingPermission`（`activeSubscriptionInfoList` / `dataNetworkType`）等；项目设置了 `checkReleaseBuilds = false`，不阻断构建 |

---

## 8. 2026-09-26 回归与修正

合并本报告对应的 PR 后，在 Pixel 8 Pro（Android 17，API 37，`CP41.260831.007`）上安装侧载开发版（`3.9.0.d120.23d9d043`），发现两个问题。

### 8.1 5G 开发线的功能缺失

- **原因**：开发线 `claude/5g-default-location-nsa-sa-x6yl6h`（25 个提交，`e899a94` … `a46a2a4`）从未合入 master。本次复检以 master 为基线，没有先核对未合并的分支。同包名（`.hsujo`）的开发版被 master 构建替换后，这些功能也就不见了；代码本身没有丢失。
- **处理**：用合并提交把开发线并入 `claude/sweet-allen-656t9c`，保留原提交历史。恢复的内容：
  - NR 模式（NSA / NSA+SA / SA）及实际读回值；
  - 5G 数据诊断页：
    - BAD / GOOD 快照与对比、ZIP 导出；
    - 按目标 SIM 的连通性与时延探测；
    - 后台监测（前台服务）与异常时自动采集；
    - 诊断用的 shell 通道是 Shizuku UserService（`shell/ShellService` + AIDL），也可以作为第 5 节第 1 条重构的起点；
  - 写入后以读回的 CarrierConfig 为准（`ApplyResult`），回到前台时重新读取；
  - `CarrierIsoRules` 统一 MCC → ISO 映射。
- **冲突处理**：共 9 个文件冲突。
  - 开发线的功能与注释全部保留。
  - 本报告的删除项继续保持删除：Broker 重试、MCC 覆盖、不可达的文本输入回调、应用内更新。
  - 写入语义上两者兼容：2.1 的「先清空再写入完整配置」正是开发线读回逻辑假定的「关闭 = 移除覆盖、回到运营商默认值」。NR 模式包含在完整配置里，不会被清空；QS 图块仍是单项合并写入。
  - `MainViewModel` 补回只读配置的 `loadCurrentConfiguration()`，供写入后读回使用；选卡仍用 `loadCurrentState()` 一次读取配置与 IMS 状态。

### 8.2 Android 17 上开关打不开 🔴

- **现象**：打开 VoLTE 等开关后立即回弹。日志中 `SimReader` / `CaptivePortalFixer` / `ConfigReader` 报 `NoSuchMethodError: No interface method stopDelegateShellPermissionIdentity()V`。
- **原因**：Android 17 的 `IActivityManager` 不再提供无参的 `stopDelegateShellPermissionIdentity()`。上面三处调用外有 try 保护，只记警告。但 `ImsModifier.overrideConfig()` 在 `finally` 中直接调用它，异常逃出后覆盖了**已经成功**的写入，`BUNDLE_RESULT=false`，界面随即回滚开关。QS 图块走同一路径，同样受影响。
- **处理**：采用开发线 `571dc58` 的 `ShellPermissionDelegation`（`ShizukuCompat.kt`）：
  - 只有开启委托失败才判定主操作失败；
  - 清理永不抛出：先按方法名反射兜底，仍失败则作为 `BUNDLE_RESULT_WARNING` 上报，界面提示「已成功应用；清理兼容性警告」。
  - 7 个特权类统一使用该封装，源码中已没有直接调用。

### 8.3 验证记录

| 检查 | 结果 |
|---|---|
| `./gradlew testDebugUnitTest -Pturboims.debugApplicationIdSuffix=.hsujo` | 通过：13 个测试类、67 项（开发线 11 个类 + 本报告 2 个类） |
| `./gradlew assembleDebug -Pturboims.debugApplicationIdSuffix=.hsujo` | 通过；包名 `io.github.vvb2060.ims.mod.hsujo`，清单含 `DiagnosticsActivity`、`MonitorService` 与 7 个 Instrumentation，无 `REQUEST_INSTALL_PACKAGES` |
| `./gradlew assembleRelease`（R8） | 通过；`shell/ShellService`、`IShellService`、`IShellService$Stub` 被 keep 规则保留 |
| 检索 `start/stopDelegateShellPermissionIdentity(` 的直接调用 | 只在 `ShizukuCompat.kt` 的封装内部 |
| 检索 `countryMcc`、`BrokerInstrumentation`、`UpdateApkCleanup`、广告 / 打赏符号 | 无 |
| 与开发线 `a46a2a4` 逐行比对 | 开发线新增、却不在合并结果中的 19 行，全部属于上面的保持删除项（MCC 实参、Broker、文本输入回调）或 `setup-java` 升级 |
| `actionlint` | 通过 |

### 8.4 需真机验证清单（追加）

- [ ] Android 17 上打开 / 关闭开关不再回弹，日志出现 `overrideConfig succeeded with cleanup compatibility warning`；QS 图块切换正常。
- [ ] 附加功能页出现「5G 数据诊断」与「NR 模式」卡片。
- [ ] 切换 NR 模式后「实际」数组与所选一致；5G NR 关闭时 NR 模式不可选。
- [ ] 诊断页能采集 BAD / GOOD 快照并导出 ZIP；后台监测的前台通知正常。
- [ ] 关闭一个开关后，读回显示运营商默认值。
