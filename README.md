# Carrier IMS for Pixel (TurboIMS)

<p align="center">
  <img src="app/src/main/ic_launcher-playstore.png" width="128" alt="Carrier IMS logo" />
</p>

<p align="center">
  <strong>面向 Google Pixel 的运营商与 IMS 工具集</strong><br/>
  在 Shizuku 权限下快速调优 VoLTE/VoWiFi/VoNR、5G 显示与网络兼容行为
</p>

<p align="center">
  中文（默认） | <a href="README_EN.md">English</a>
</p>

<p align="center">
  <a href="https://github.com/Hsujo/carrier-ims-for-pixel/releases"><img alt="Release" src="https://img.shields.io/github/v/release/Hsujo/carrier-ims-for-pixel"></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/github/license/Hsujo/carrier-ims-for-pixel"></a>
  <img alt="Platform" src="https://img.shields.io/badge/Platform-Android%2013%2B-3DDC84">
  <img alt="Device" src="https://img.shields.io/badge/Device-Pixel%20Tensor-blue">
  <img alt="Permission" src="https://img.shields.io/badge/Requires-Shizuku-orange">
</p>

## 分叉说明

- 本仓库分叉自 [ryfineZ/carrier-ims-for-pixel](https://github.com/ryfineZ/carrier-ims-for-pixel)
- 已移除原项目的广告弹窗、打赏 / 去广告、商务合作页面，只保留功能界面
- 已移除应用内检查更新与下载安装，请到本仓库 [Releases](https://github.com/Hsujo/carrier-ims-for-pixel/releases) 获取新版本
- 「提 Issue / 打开仓库」指向本仓库

## 项目定位

本项目是基于 [Mystery00/TurboIMS](https://github.com/Mystery00/TurboIMS) 的持续维护分支，面向中国大陆与跨区使用场景做了大量交互与兼容性增强。  
目标是让普通用户也能更低门槛地完成 IMS 功能调优与问题排查。

## 界面预览

<p align="center">
  <img src="docs/Screenshot1.png" width="46%" alt="screenshot-1" />
  <img src="docs/Screenshot2.png" width="46%" alt="screenshot-2" />
</p>

## 功能矩阵

| 模块 | 能力 | 说明 |
|---|---|---|
| 系统信息 | 版本/设备/补丁/Shizuku 状态 | 集中展示运行环境，便于排障 |
| IMS 注册 | IMS 注册状态查询与手动注册 | 未注册时可一键触发注册流程 |
| 运营商能力 | VoLTE / VoWiFi / ViLTE / VoNR / UT / Cross‑SIM | 开关项实时生效，失败自动回滚并记录日志 |
| 5G 能力 | 5G NR / 5G 信号强度 / 5G+ 图标 | 适配中国大陆常见展示需求 |
| 网络修复 | 一键修复网络验证（captive portal） | 修复“已连接但网络受限/感叹号” |
| TikTok 修复 | 修复 TikTok 无网络（大陆 SIM） | 仅大陆 SIM 提供该选项，海外 SIM 默认无需修复 |
| 诊断工具 | 日志查看、配置全量查看、Issue 快捷上报 | 失败日志可直接附带到 Issue |

## 为什么这个分支更适合日常使用

- 更清晰的 UI 结构：核心操作入口前置，文案更贴近实际问题
- 更稳的写入策略：优先安全路径，失败可回退，避免高风险操作
- 更完整的排障链路：失败日志沉淀、系统信息复制、Issue 一步提交
- 新增网络能力诊断：支持对照 App 配置与 CarrierConfig 读回，定位更直接
- 界面进一步紧凑化：信息密度更高，常用按钮与卡片留白优化
- 更贴近国内网络环境：5G 显示与联网验证修复能力内置

## 快速开始

1. 从 [Releases](https://github.com/Hsujo/carrier-ims-for-pixel/releases) 下载并安装 APK  
2. 安装并启动 [Shizuku](https://shizuku.rikka.app/zh-hans/)  
3. 打开 App，授权 Shizuku  
4. 选择 SIM，按需开启功能开关  

## 适用范围

- 设备：Pixel Tensor 平台（Pixel 6/7/8/9/10、Fold、Tablet）
- 系统：Android 13 及以上（建议 Android 14/15/16）
- 语言：简体中文 / English

## 构建（开发者）

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

如需本地签名，请在 `local.properties` 中配置：

```properties
SIGN_KEY_STORE_FILE=/path/to/your.keystore
SIGN_KEY_STORE_PASSWORD=***
SIGN_KEY_ALIAS=***
SIGN_KEY_PASSWORD=***
```

### 侧载开发版（GitHub Actions 自动构建）

- 推送到 `claude/**` 分支、合并进 `master`，或在 Actions 页面手动运行 **Android Sideload Dev Build**，都会构建一个侧载开发版
- 下载：Actions → Android Sideload Dev Build → 对应的运行 → Artifacts 里的 `TurboIMS-Hsujo-Dev`（压缩包内是 `TurboIMS-Hsujo-Dev-<提交号>.apk`）
- 包名 `io.github.vvb2060.ims.mod.hsujo`，桌面名称「TurboIMS Hsujo Dev」，可与正式版同时安装；需要在 Shizuku 中单独授权
- 开发版不会在开机后自动恢复配置，只执行你在界面上手动触发的写入，避免与正式版互相覆盖
- 运行摘要和 `Report APK identity` 步骤会显示签名证书的 SHA256，可与本地密钥核对

#### 固定开发密钥（可选，只需配置一次）

不配置时，每次构建都用临时生成的 debug 密钥签名，签名各不相同，安装新版前必须先卸载旧的开发版。配置后各次构建签名一致，可以直接覆盖安装。这把密钥只用于开发版，与正式版签名无关；密码和别名固定为 `android` / `androiddebugkey`，工作流按这组值读取，不要修改。

1. 生成密钥（需要 JDK 17+ 的 `keytool`，Android Studio 自带于安装目录的 `jbr/bin/`）：
   ```bash
   keytool -genkeypair -keystore sideload.jks -storetype PKCS12 -alias androiddebugkey -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=TurboIMS Hsujo Dev"
   ```
2. 转成单行 Base64：
   - Linux：`base64 -w0 sideload.jks > sideload.b64`
   - macOS：`base64 -i sideload.jks > sideload.b64`
   - Windows PowerShell：`[Convert]::ToBase64String([IO.File]::ReadAllBytes("sideload.jks")) | Set-Content -NoNewline sideload.b64`
3. 仓库 **Settings → Secrets and variables → Actions → New repository secret**：名称 `SIDELOAD_KEYSTORE_BASE64`，内容粘贴 `sideload.b64` 的全部文本（必须是 Actions 的 Repository secret）
4. 妥善备份 `sideload.jks`，**不要提交到仓库**；添加完成后删除 `sideload.b64`。本地可用 `keytool -list -v -keystore sideload.jks -storepass android` 查看 SHA256 指纹，与 CI 输出核对

secret 存在但内容无效（Base64 不完整、别名或密码不对）时，工作流会直接失败并提示原因，不会悄悄退回临时密钥。

## 常见问题

### 1. IMS 仍未注册

- 先确认 Shizuku 已就绪  
- 检查 VoLTE / VoWiFi 是否可用  
- 使用日志与 Issue 上报能力收集现场信息

### 2. 网络有信号但无法上网

- 先检查 APN 是否缺失或异常  
- 再尝试“网络验证修复”功能  

### 3. TikTok 仍不可用

- 仅大陆 SIM 才会出现“修复 TikTok 无网络”开关  
- 变更后建议重启目标 App 或清理其会话缓存再测试

### 4. 为什么去掉“修改国家码”，改为“一键修复 TikTok 登录”

- 旧方案里所谓“国家码修改”，本质是写入 CarrierConfig 覆盖项 `sim_country_iso_override_string`，并不是真正修改基带层的 MCC/MNC。  
- 设备真实网络归属值（如 `gsm.operator.numeric`、注册态中的 MCC/MNC）通常不会被这类覆盖项直接改变，因此它不是稳定、通用的“改国家码”方案。  
- 实测中 TikTok 可用性的关键点并非“改成某个国家”，而是把 ISO 覆盖成异常值后，触发 TikTok 的识别分支异常，从而绕过其对 SIM 归属地的部分判断逻辑。  
- 基于这个实际机理，项目将入口改为更直观的一键开关“修复 TikTok 无网络”，避免用户误以为此功能可以真实改写运营商归属信息。  
- 该行为依赖目标 App 版本与风控策略，后续可能变化；此功能仅用于兼容性排查与测试，不保证长期有效。

## 更新记录

- 详细版本变更见 [CHANGELOG.md](CHANGELOG.md)
- 历史发布见 [Releases](https://github.com/Hsujo/carrier-ims-for-pixel/releases)

## 致谢

- [ryfineZ/carrier-ims-for-pixel](https://github.com/ryfineZ/carrier-ims-for-pixel)
- [Mystery00/TurboIMS](https://github.com/Mystery00/TurboIMS)
- [vvb2060/Ims](https://github.com/vvb2060/Ims)
- [kyujin-cho/pixel-volte-patch](https://github.com/kyujin-cho/pixel-volte-patch)
- [nullbytepl/CarrierVanityName](https://github.com/nullbytepl/CarrierVanityName)

## 免责声明

本应用会修改系统运营商相关配置，仅用于学习、测试与自有设备调优。请自行评估风险并对操作结果负责。

## License

Apache-2.0
