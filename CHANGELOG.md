# 更新日志

本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### 变更

- 应用中文名定为「极氪极氪（车机版）」，成为默认的 launcher 名称；英文环境下仍显示 `Zeekr Shortcut`（新增 `values-en/strings.xml`）。

### 移除（重要）

- **裁掉全部用不到的模块，APK 从 24.4 MB 降到约 16 MB。** 这些功能面向吉利银河车型，
  与极氪合成流场景无关：
  - 钉钉 / 飞书 / Telegram 远程查看、心跳推图（连同 DingTalk Stream SDK）
  - MJPEG / RTP / UDP 推流（连同 NanoHTTPD）
  - 二维码扫码互传（连同 ZXing）
  - VHAL 车辆信号（连同 gRPC ×2 与原生 .so）
- 共删除 58 个源文件，MainActivity 减少约 1300 行。移除按「整个成员」粒度进行，
  不会把方法切成两半；生命周期与核心方法（onCreate、initCamera、onScreenOff/On、
  exitApp 等）为手工编辑而非删除。
- `VhalSignalObserver` 换成公开 API 完全一致的空实现，因此补盲模块无需改动即可编译。
  该 gRPC 服务在极氪车机上本就不存在，原实现只会不断重连失败。
- 所有被移除的代码**完整保留在 git 历史中**作为参考实现。

### 修正

- **U 盘存储可用。** 此前依据 openavm-recorder 的记录标注为「App Lab 不允许直接写
  外置 USB 存储」。本项目作者已在极氪车机上实测：EVCam 的 U 盘写入可以正常工作。
  README、NOTICE、平台笔记与应用内致谢页均已更正，U 盘存储按可用对待，
  并建议直接录到 U 盘以避免车机内部闪存的写入损耗。

### 文档

- README 不再罗列本项目用不到的 EVCam 功能，改为明确列出「当前启用」与「已裁剪」两部分。
- 给 openavm-recorder 作者的致谢 issue 草稿改为只谈该项目本身，
  不涉及其他项目、也不介绍本应用的功能。


### 变更（重要）

- **四宫格渲染架构整体重写：从 OpenGL 改为父容器重画子视图。**
  深入阅读 openavm-recorder 公开源码后发现一条关键实测结论：用 GL 自建的
  SurfaceTexture 顶替原本正常工作的相机生产者，**在真车上会导致预览崩溃**；
  其验证可行的结构是「Camera2 只喂一个普通 TextureView，由父容器把该子视图
  重复画进四个格子」。本项目原先的 `CompositeTextureView` 正是前一种做法，
  已删除并由 `FourLaneContainer` 取代。相机链路现在完全不被改造。
- 拆分几何改为按**合成流真实尺寸**计算归一化窗口，再套到实际绘制区域。
  这样 HAL 给出压扁的小尺寸提示（如 640×480）时，四个画面的位置与比例仍然正确。
  `setSourceSize()` 会忽略「不像合成流」的尺寸，避免把已探测到的正确几何降级。
- 新增 `FourLaneContainer.DisplayMode.RAW`：不拆分、原样显示整条合成流，用于排查问题。

### 新增

- [`docs/zeekr-platform-notes.md`](docs/zeekr-platform-notes.md)：极氪 App Lab
  平台事实笔记。逐条记录视频流格式、渲染约束、相机所有权、资源限制、存储限制，
  并标注每条是【实测】还是【未知】、以及本项目据此做的决定。

### 文档

- README 增补三条重要限制：**App Lab 不允许直接写外置 U 盘**（实测）、
  APK 体积可能影响 App Lab 启动、原厂相机功能优先于本应用。
- NOTICE 补全所使用事实的清单，并说明「阅读源码提取事实」与「复制代码」的区别。

### 修复

- CI 的单元测试任务改为 `:app:testDebugUnitTest`（`testReleaseUnitTest` 在本
  AGP 版本下不存在，导致首次构建失败）。

## [0.1.0-alpha] - 2026-08-27

首个版本。以 EVCam `0876b97`（GPL-3.0）为代码基座，新增极氪合成流支持。

### 新增

- **极氪7X 车型档案**：车机只提供一路四联合成流，摄像头数量按 1 处理。
- **合成流几何拆分** `CompositeStreamGeometry`：把 `1280x5140` / `5120x1280` 等
  合成帧按真实几何拆成四个方形画面，正确处理 5 条 4px 分隔带；
  排布不匹配时安全回退为四等分。纯 Java 实现，含 20+ 条 JVM 单元测试。
- **GL 四宫格视图** `CompositeTextureView`：继承 `AutoFitTextureView`，
  重写 `getSurfaceTexture()` 返回自建 OES 外部纹理，因此**无需改动上游相机管线**
  即可把长条合成流渲染成 2x2 四宫格。支持 FIT/FILL 缩放、单画面聚焦、画面排列自定义。
- **按能力查找相机** `ZeekrCameraLocator`：遍历所有 Camera2 设备，
  选出真正声明了合成流尺寸的那一路，不再写死摄像头下标。
- **合成流车型档案** `ZeekrCompositeProfile`：已知尺寸优先、码率推荐；
  绝不臆造 HAL 未声明的分辨率。
- **「关于与致谢」页面**：应用内完整说明两个功能来源、各自许可证与使用边界。
  入口在侧边菜单。
- **极氪专用布局** `activity_main_zeekr_7x.xml`：四宫格 + 四象限角标 +
  四宫格/单画面切换按钮 + 合成流识别结果诊断条。
- **GitHub Actions CI**：推送即跑单元测试并构建 APK；打 `v*` tag 自动发布 Release。

### 变更

- 应用名改为 `Zeekr Shortcut`（中文名后改为「极氪极氪（车机版）」，见 Unreleased），包名改为 `io.github.dts88.zeekrshortcut`，
  版本号从 `0.1.0-alpha` 重新起算（EVCam 基座版本为 1.6.6）。
- 签名密钥可通过环境变量覆盖（`ZEEKR_KEYSTORE` 等），默认仍用仓库内的公开测试密钥。
- 启用 `unitTests.isReturnDefaultValues`，使纯逻辑单元测试可在 JVM 上直接运行。
- 上游 EVCam 的 `README.md` / `CLAUDE.md` / `AGENTS.md` / `免责声明.md` 移入 `docs/`，
  避免与本项目文档混淆。

### 移除

- `assets/logo.png`、`assets/donate.jpg`：未被代码引用，移出 APK 以减小体积
  （logo 移至 `docs/evcam-upstream-logo.png` 供文档使用）。

### 说明

- 本版本**未在实车上验证**。自动化测试只覆盖纯逻辑部分；
  GL 渲染、相机取流与录制链路需要实车验证。首次使用请在静止车辆上测试。
