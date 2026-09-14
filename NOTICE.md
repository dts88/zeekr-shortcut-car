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

本仓库的第一个 commit 就是 EVCam 在上述版本的完整工作树。相机管线、分段录制、存储管理、
回放、保活、补盲等功能的起点都来自 EVCam，著作权归 suyunkai 及 EVCam 的其他贡献者所有。

### 导入时排除的内容

`.git`、`.claude`、`.cursor`、`app/release`（预编译产物）、`logcat_debug.txt`、`release.bat`。

### 本项目做了哪些改动

按 GPL-3.0 第 5(a) 条要求，改动一律以 diff 形式可见：第一个 commit 之后的全部提交就是完整的
改动记录，每个版本的要点见 [`CHANGELOG.md`](CHANGELOG.md)。

大的方向：

- **新增，本项目独立实现**：极氪合成流的识别、拆分与四宫格渲染（`app/src/main/java/com/kooo/evcam/zeekr/`），
  视频流配置与配置编辑器（`app/src/main/java/com/kooo/evcam/profile/`），电子后视镜，发送到手机。
- **删除**：钉钉 / 飞书 / Telegram 远程查看、心跳推图、MJPEG / RTP 推流，连同对应的 SDK 依赖。
- **重做**：录制参数的来源（改由视频流配置按路决定）、设置界面、主界面与悬浮按钮、视频回看。
- **包名与构建**：applicationId 改为 `io.github.dts88.zeekrshortcut`，签名可由环境变量覆盖，
  CI 构建与发布见 `.github/`。

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
| AndroidX（AppCompat、Activity、ConstraintLayout、RecyclerView、CardView、Preference、SlidingPaneLayout、WorkManager） | Apache License 2.0 |
| Material Components | Apache License 2.0 |
| Gson | Apache License 2.0 |
| Glide | BSD / MIT / Apache 2.0（见其仓库 LICENSE） |
| NanoHTTPD | BSD 3-Clause |
| ZXing Core | Apache License 2.0 |

`assets/douyin.jpg`、`assets/douyin2.png` 来自 EVCam，
是其补盲功能作者（抖音 @星星舰见）的反馈群二维码，随上游一同保留以维持署名。

---

## 3. 与极氪（ZEEKR）的关系

**没有任何关系。** 本项目是独立的第三方实验性软件，与浙江极氪智能科技有限公司
及其关联方无隶属、合作或背书关系。「极氪」「ZEEKR」「7X」等名称仅用于说明本应用
适用于哪种车机环境，相关商标权归其各自所有者所有。
