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
  视频流配置与配置编辑器（`app/src/main/java/com/kooo/evcam/profile/`），电子后视镜，发送到手机，
  模拟声浪（`app/src/main/java/com/kooo/evcam/sound/`，见第 5 节）。
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
dts88 写的部分另有两条 GPL-3.0 第 7 条允许的附加条款（署名、改版须标明），见第 8 节。

---

## 2. 贡献者的代码和资料（GPL-3.0）

下面这些不是从 EVCam 来的，是别的开发者为本项目做的。随 PR 直接合并的，作者也记在 git 历史里；
没有直接合并、由本项目挑出来用的，在这里写明出处，代码文件的类说明和版权头里也各写一行。
本项目读过贡献者的 PR、照它的思路自己重写、再把原 PR 关掉的，也记在这里。

- **wysie**（<https://github.com/wysie>）：
  - 直接合并：超级镜子的方向标签（PR #17）；去掉没用到的麦克风权限检查（PR #28）；
    下载更新时核对安装包的 sha256 和大小、只走 https（`DownloadIntegrity`，PR #38）。
  - 取自 PR #19：盲区画面里的关闭按钮只关这一次（`SideViewDismissal`）、转的角度大时收窄视野不出黑块（`SideViewFraming`）、
    纵向视野随窗口形状变（`SideViewProjection.Rays`）、原厂画面在时不备着（`SideViewDecision` 里的一条）。
  - 照他的思路由本项目重写（原 PR 读过后关掉）：每个设置页按小标题分组（PR #62，来自 PR #31）；
    主界面预览停画时盖一层提示（PR #67，来自 PR #36 的核心一点）；字号不一致的问题由他的 PR #45 查出，
    本项目改成一律照设计规范的字号（PR #69）。
- **FerixzTanz**（<https://github.com/FerixzTanz>）：打转向灯时显示侧方摄像头画面的最初代码和实车研究（PR #4，已并入 PR #19）——
  打灯判定（`SideViewDecision`）、转过角度的虚拟相机（`SideViewProjection`）、按调用方给的反投影切网格（`FisheyeMesh.SourceMap`），
  以及对应的测试。
  盲区画面的默认角度（朝后 33°、朝外 10°、视野 90°）和窗口大小（屏高 33%、宽 ÷ 高 2.2）照他在自己 7X 上实车调出的值（PR #43）。
  盲区画面投到 HUD 上（2026-10-21）照他的 CAMCORDER（<https://github.com/FerixzTanz/CAMCORDER>）：找 HUD 那块屏的规则（`HudScreen`）、
  画面的位置大小 / 对比度 / 透明度 / 边缘渐隐的公式（`HudPicture`）、用 Presentation 投 HUD 的窗口画法（`SideViewHudWindow`），
  以及全部 HUD 设置项和他在自己 7X 上调出的默认值。

- **jlunnn**（<https://github.com/jlunnn>，CarPiano、7X-U 的作者）：灵动贴的「后雾灯闪烁」动作（`CarMoves.rearFogFlash`，
  从他的 7X-U 搬来，他在 7X 上实测过）；比较盘的路径时统一用 / 分隔，单元测试在 Windows 上也能通过（`FallbackVolumes`）。
  都是 PR #160，2026-11-06 收录，项目所有者照界面文字规范改了动作的名字。
- **calvinbui**（Calvin Bui，<https://github.com/calvinbui>）：在他的澳洲 7X（固件 2.1.1c）上实测 6 项设置的记录：
  内外循环、二排空调、自动远光、低速紧急制动、陡坡缓降、舒适加速（`docs/australian-7x-*.md`，PR #145，2026-11-06 收录）。
  他没核实、本项目实测过的休眠、下电、重启，补在舒适加速那一篇的第 8 节。

盲区画面的服务和窗口（`SideViewService`、`SideViewWindow`）里读车辆信号、备着 / 显示两种状态的写法，也照 wysie、FerixzTanz 两位 PR 里的侧视弹窗写。
这些代码照本仓库的 GPL-3.0 发布。碰相机的部分（窗口怎么挂、画布怎么交给通道调度）由本项目照通道调度重写，没有用 PR 里的那一套。

---

## 3. 第三方依赖

以下组件由 Gradle 在构建时拉取，随 APK 分发，各自受其上游许可证约束：

| 组件 | 许可证 |
|------|--------|
| AndroidX（AppCompat、Activity、ConstraintLayout、RecyclerView、CardView、Preference、SlidingPaneLayout、WorkManager） | Apache License 2.0 |
| Material Components | Apache License 2.0 |
| Gson | Apache License 2.0 |
| Glide | BSD / MIT / Apache 2.0（见其仓库 LICENSE） |
| NanoHTTPD | BSD 3-Clause |
| ZXing Core | Apache License 2.0 |

`docs/evcam-upstream-douyin.jpg`、`docs/evcam-upstream-douyin2.png` 来自 EVCam，
是其补盲功能作者（抖音 @星星舰见）的反馈群二维码，随上游一同保留以维持署名。
应用里没有用到它们，2.0 之后从 `assets/` 挪到 `docs/`，不再打进 APK。
同一位作者的补盲免责声明 `docs/upstream-EVCam-补盲免责声明.md`（原 `res/raw/blind_spot_disclaimer.md`）也来自 EVCam，
应用里同样没有用到，2026-11-06 挪到 `docs/`。

---

## 4. 与极氪（ZEEKR）的关系

**没有任何关系。** 本项目是独立的第三方实验性软件，与浙江极氪智能科技有限公司
及其关联方无隶属、合作或背书关系。「极氪」「ZEEKR」「7X」等名称仅用于说明本应用
适用于哪种车机环境，相关商标权归其各自所有者所有。

---

## 5. 只借用了事实的项目（不含代码）

- **CarPiano**（<https://github.com/jlunnn/CarPiano>，MIT）：在 7X 车机上往喇叭出声的实测事实 —— App Lab 容器里不指定输出设备就没声；
  车外 `BUS12_OUTER_NOTIFY` 配媒体用途有声；系统音量不一定管到车外那一路，要自己做软件增益。回放出声、模拟声浪选喇叭的做法用到了这些事实，
  代码没有用。
- **EngineV1**（<https://evenginev1.com>，商业网站，项目所有者给的参考）：首页公开试听的「Dodge Hellcat」素材上量出来的频谱事实 ——
  点火阶次和 1、2 阶的比例、固定共振峰的位置、带负荷时七八成是随机成分、高频怎么滚降 —— 用来校准逐缸合成器（`sound.PulseVoice`）；
  也看了它网页上的做法（真车录音按转速、负荷分段循环、交叉淡化，再加随油门开合的低通）。录音和代码都没有用。

模拟声浪的合成方法、各款声音和调校，都是 dts88 的原创；上面两个项目只提供了这些事实。

---

## 6. 不是代码的内容

- `app/src/main/res/raw/outside_voice_go_first.wav`（「You're welcome to go first. Be careful.」）：项目所有者用手机录下车外喇叭放出来的这句语音，
  再去噪、去混响、校正音色（2026-11-01）。

---

## 7. 用到本项目的代码或信息时

如果你的项目用到了本项目的代码，或者用到了这里最先公开的事实（平台笔记、信号编号、摄像头排布等），
请在显眼的位置注明：README 靠前的位置，以及应用的「关于」或致谢页。例如：

> Uses code / findings from [Zeekr Shortcut](https://github.com/dts88/zeekr-shortcut-car) by dts88 (GPL-3.0).

- **代码**：GPL-3.0，保留版权和许可声明本来就是必须的；dts88 写的部分还要照第 8 节的附加条款，
  在「关于」或致谢页里保留署名，改过的版本要标明不是原版。其中含 EVCam（第 1 节）或贡献者（第 2 节）的部分，
  也请一并保留他们的署名。
- **事实**：事实不受许可证约束，所以这一点是请求，不是许可条款。本项目对 CarPiano 和 EngineV1（第 5 节）也是这样做的。

---

## 8. 附加条款（GPL-3.0 第 7 条）

dts88 为本项目写的代码，在 GPL-3.0 之外附加两条：

1. **署名**：用到这些代码的作品（改没改都算），要在它显示的版权与许可声明里（应用就是「关于」或致谢页）保留署名，
   让用户看得见。原样分发的，保留「极氪即刻 © 2026 dts88」这一行（或它的译文）；改过的版本、或者把代码用进别的作品，
   写「Based on Zeekr Shortcut by dts88 (https://github.com/dts88/zeekr-shortcut-car)」。源文件开头的版权头也要保留。
2. **改版要标明**：改过的版本不能冒充原版，不能说是 dts88 出的，要用合理的方式标明和原版不同，
   比如换一个应用名，或者在名字旁边写「修改版」。

这是 GPL-3.0 第 7 条 b、c 两款明确允许的附加条款，不是第 10 条说的「额外限制」。本项目仍然是 GPL-3.0 的开源软件：
任何人照样可以使用、修改、再发布，包括商用。

- 只管 dts88 写的部分。EVCam（第 1 节）和贡献者（第 2 节）的代码照原来的 GPL-3.0，不受这两条约束。
- 从加上本节的那个提交起生效；之前的提交和已经发布的版本照原来的条款。
- 每个源文件开头的版权头写明谁有版权、许可证是什么，并指向这里（GPL-3.0 第 7 条要求写明附加条款在哪）。
- 以下面的英文原文为准。

> **Additional terms under section 7 of the GNU General Public License, version 3**
>
> These terms apply to the material authored by dts88 in this repository, that is, everything except the EVCam code
> and the contributors' code described in sections 1 and 2 of this file. They apply from the commit that adds this
> section onward; earlier commits and releases stay under the terms they were released with.
>
> 1. **Attribution (section 7(b)).** Any work that contains this material must keep, in the Appropriate Legal Notices
>    it displays (for an app, its About or credits screen) and reasonably visible to users, the line
>    "Zeekr Shortcut © 2026 dts88" (or its translation) for an unmodified copy, or
>    "Based on Zeekr Shortcut by dts88 (https://github.com/dts88/zeekr-shortcut-car)" for a modified version or a
>    larger work. The copyright notices at the top of the source files must be kept as well.
> 2. **Modified versions (section 7(c)).** A modified version must not misrepresent its origin. It must not be
>    presented as the original Zeekr Shortcut or as coming from dts88, and must be marked in reasonable ways as
>    different from the original, for example by a different app name or the words "modified version" next to its
>    name.
