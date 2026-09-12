# 来源、致谢与许可证说明

本文件说明极氪即刻（车机版）（Zeekr Shortcut (Car Version)）的每一部分从哪里来、
受什么许可约束。

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

## 2. 第三方依赖

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

## 3. 与极氪（ZEEKR）的关系

**没有任何关系。** 本项目是独立的第三方实验性软件，与浙江极氪智能科技有限公司
及其关联方无隶属、合作或背书关系。「极氪」「ZEEKR」「7X」等名称仅用于说明本应用
适用于哪种车机环境，相关商标权归其各自所有者所有。
