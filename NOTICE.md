# 来源、致谢与许可证说明

本文件说明极氪极氪（车机版）（Zeekr Shortcut (Car Version)）的每一部分从哪里来、受什么许可约束，
以及我们**用了什么、没用什么**。

---

## 1. EVCam —— 代码基座（GPL-3.0）

- **项目**：<https://github.com/suyunkai/EVCam>
- **作者**：suyunkai
- **许可证**：GNU General Public License v3.0
- **导入版本**：commit `0876b976bff554ef6bad2c65513cf0149f451d81`

### 用了什么

**几乎全部**。本仓库的第一个 commit 就是 EVCam 在上述版本的完整工作树。
应用的相机管线、编码、存储、回放、保活、远程查看等能力全部来自 EVCam，
著作权归 suyunkai 及 EVCam 的其他贡献者所有。

### 导入时排除的内容

`.git`、`.claude`、`.cursor`、`app/release`（预编译产物）、`logcat_debug.txt`、`release.bat`。

### 本项目做了哪些改动

按 GPL-3.0 第 5(a) 条要求，改动一律以 diff 形式可见（第一个 commit 之后的所有提交）。
主要改动：

| 改动 | 文件 |
|------|------|
| 新增极氪合成流几何拆分 | `app/src/main/java/com/kooo/evcam/zeekr/CompositeStreamGeometry.java`（新增） |
| 新增合成流四宫格容器 | `app/src/main/java/com/kooo/evcam/zeekr/FourLaneContainer.java`（新增） |
| 新增合成流车型档案 | `app/src/main/java/com/kooo/evcam/zeekr/ZeekrCompositeProfile.java`（新增） |
| 新增按能力查找相机 | `app/src/main/java/com/kooo/evcam/zeekr/ZeekrCameraLocator.java`（新增） |
| 新增关于与致谢页 | `app/src/main/java/com/kooo/evcam/zeekr/AboutActivity.java`、`res/layout/activity_about.xml`（新增） |
| 新增极氪布局 | `res/layout/activity_main_zeekr_7x.xml`（新增） |
| 新增车型常量与摄像头数量 | `AppConfig.java`（修改） |
| 新增布局分支、相机初始化、合成流交互 | `MainActivity.java`（修改） |
| 新增车型选项 | `SettingsFragment.java`（修改） |
| 后台相机初始化支持合成流 | `camera/CameraManagerHolder.java`（修改） |
| 改包名、版本号、签名可覆盖、单元测试选项 | `app/build.gradle.kts`（修改） |
| 改应用名与新增字符串 | `res/values/strings.xml`（修改） |
| 新增关于入口 | `res/menu/navigation_menu.xml`（修改） |
| 新增 CI 构建 | `.github/workflows/build.yml`（新增） |
| 上游文档移入 `docs/`，移除未被代码引用的大图 | `docs/`、`assets/`（移动/删除） |

平台事实笔记见 [`docs/zeekr-platform-notes.md`](docs/zeekr-platform-notes.md)，
其中逐条标注了来源与未验证项。

上游原始文档保留在 [`docs/`](docs/) 目录：
`upstream-EVCam-README.md`、`upstream-EVCam-CLAUDE.md`、`upstream-EVCam-AGENTS.md`、
`upstream-EVCam-免责声明.md`。

### 许可影响

GPL-3.0 是传染性（copyleft）许可证。因此**本项目整体以 GPL-3.0 发布**，
分发时必须提供对应源代码、保留版权与许可声明，且不得添加额外限制。

---

## 2. openavm-recorder / AVM Recorder —— 公开技术资料（保留所有权利）

- **项目**：<https://github.com/Dantenothing/openavm-recorder>
- **作者**：Dantenothing
- **许可**：`Copyright © 2026 Dantenothing. All rights reserved.`

### 该项目的授权状态

其 README「Source availability and licence status」一节明确声明：

- 源代码公开可见，仅供检查与安全审查；
- **不授予覆盖整个项目的开源许可证**，仓库中也不包含 `LICENSE` 文件；
- 除法律、GitHub 服务条款或另行书面许可允许外，**不授予复制、修改、再分发、
  重新打包、发布衍生版本或出售**其源代码或 APK 的许可；
- 官方签名 APK 仅可用于个人非商业的评估与测试，**不包括修改与再分发**；
- AVM Recorder 的名称与图标**不授权**用于衍生品牌，或用于暗示某个修改版是官方发布。

### 我们用了什么

**只用了公开 README 中记载的接口事实**，即对车机输出接口的事实性描述：

| 事实 | 内容 |
|------|------|
| 取流方式 | App Lab 只向第三方应用提供**一路已处理的合成流**，而非四路独立摄像头 |
| 竖排尺寸 | `1280×5140` = 四个 1280×1280 方形画面竖向堆叠 |
| 分隔带 | `5140 − 4×1280 = 20` 行，为 5 条 4px 分隔带（上边缘、三条内部分隔、下边缘） |
| 横排尺寸 | `5120×1280` = 四个 1280×1280 画面横向排列，无分隔带 |
| 推荐码率 | 整条合成流约 28 Mbps |
| 工程原则 | 只使用 HAL 实际声明的尺寸，不做臆造与近似替换 |
| 小尺寸提示 | HAL 可能只声明约 640×480 的 Surface 提示，但内部仍送同一份合成内容 |
| 渲染约束 | **用 GL 自建 SurfaceTexture 顶替相机生产者会导致预览崩溃**；可行结构是一个普通 TextureView 做唯一消费者、由父容器重画进四个格子 |
| 落盘表现 | 约 200 MB/min、3–4 MB/s 持续写入 |
| 存储限制 | 被测 App Lab 环境不允许直接写外置 USB 存储（**本项目实测不成立，未采用**） |
| 相机所有权 | 原厂 360°/倒车/泊车相机可能随时接管，第三方应用必须让路 |
| 资源管理 | App Lab 提示体积过大的应用可能无法启动、高负载时可能被降速 |

这些是对硬件/系统输出接口的事实性描述与数值，不构成受著作权保护的表达。

### 我们没有用什么

- **没有复制该项目的任何源代码**（一行都没有）。该项目为 Kotlin + Jetpack Compose，
  本项目对应实现为独立编写的 Java。我们**阅读**了其公开源码以了解车机行为
  （这正是其「源代码公开可见以供检查」的用途），从中提取的是上表那些事实性结论，
  而不是代码本身；
- 没有使用其名称、图标或任何品牌标识；
- 没有复制或再分发其 APK；
- 没有暗示本项目与其存在任何关联。

### 致谢

极氪合成流的存在与具体格式，是 openavm-recorder 项目公开记录并分享出来的。
没有这份公开资料，本项目无法适配极氪车机。**在此向 Dantenothing 致以诚挚感谢。**

### 声明

本项目与 openavm-recorder 及其作者**没有隶属或合作关系，未获其背书**。
本项目的任何问题都不应向 openavm-recorder 反馈。

### 如果你是 Dantenothing

如果你认为本项目的任何部分越过了上述边界，请提 issue 或直接联系仓库所有者，
我们会立即处理。反过来，如果你愿意授权复用 openavm-recorder 的代码，
我们也非常乐意在获得书面许可后按你希望的方式署名与整合。

---

## 3. 第三方依赖

以下组件由 Gradle 在构建时拉取，随 APK 分发，各自受其上游许可证约束：

| 组件 | 许可证 |
|------|--------|
| AndroidX / Material Components | Apache License 2.0 |
| AndroidX WorkManager | Apache License 2.0 |
| OkHttp、Okio | Apache License 2.0 |
| Gson | Apache License 2.0 |
| ZXing Core | Apache License 2.0 |
| Glide | BSD / MIT / Apache 2.0（见其仓库 LICENSE） |
| NanoHTTPD | BSD 3-Clause |
| gRPC（grpc-okhttp、grpc-stub） | Apache License 2.0 |
| DingTalk App Stream Client | 见钉钉开放平台条款 |

`assets/douyin.jpg`、`assets/douyin2.png` 来自 EVCam，
是其补盲功能作者（抖音 @星星舰见）的反馈群二维码，随上游一同保留以维持署名。

---

## 4. 与极氪（ZEEKR）的关系

**没有任何关系。** 本项目是独立的第三方实验性软件，与浙江极氪智能科技有限公司
及其关联方无隶属、合作或背书关系。「极氪」「ZEEKR」「7X」等名称仅用于说明本应用
适用于哪种车机环境，相关商标权归其各自所有者所有。
