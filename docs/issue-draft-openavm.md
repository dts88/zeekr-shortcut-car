# 给 openavm-recorder 的 issue 草稿

> 用途：向 openavm-recorder 作者 Dantenothing 说明本项目、致谢，并主动确认边界。
> 提交地址：https://github.com/Dantenothing/openavm-recorder/issues/new
>
> **未经你确认，不会替你提交。** 内容可随意增删。

---

## 标题

```
Thanks + disclosure: a separate GPL-3.0 project that uses your documented facts (no code copied)
```

## 正文（英文，建议直接用这版）

```markdown
Hi, and thank you for this project.

I want to be upfront about something I've built, and give you the chance to
object if any of it isn't OK with you.

### What I built

I've published a Zeekr surround-view dashcam app:
https://github.com/dts88/zeekr-shortcut-car

It's a fork of [EVCam](https://github.com/suyunkai/EVCam) (GPL-3.0, by suyunkai),
which gave me the camera pipeline, encoding, storage and playback. I added
support for the Zeekr composite surround-view stream on top of it.

### What I used from your project — and what I didn't

I read your README and your source code. I have **not copied any of your code**,
and I'm not redistributing your APK. I understand your project is
`All rights reserved` with no open-source licence, and that your name and logo
are not licensed for derivative branding — I've respected all of that.

What I did use is the **factual information** you documented about the platform:

- App Lab exposes one already-composited stream, not four cameras
- `1280x5140` vertical: four 1280x1280 views + five 4px separator bands
- `5120x1280` horizontal variant
- ~28 Mbps, ~200 MB/min write profile
- the HAL may declare only a small squashed surface hint while still delivering
  the same composite content
- App Lab does not allow writing recordings to external USB storage
- factory 360/reverse/parking functions may reclaim the camera and must be given way
- **that replacing the working camera producer with a GL-owned SurfaceTexture
  crashes, and that the working structure is one ordinary TextureView redrawn by
  its parent into four cells**

That last one deserves a specific thank-you. I had already written the GL version
— my own implementation, but architecturally the exact thing you documented as
crashing. Your comment in `ProductHomeCameraPolicy.kt` saved me from shipping it
to a real car and then having no idea why the preview died. I threw my
implementation away and rewrote it as a container that redraws a plain
TextureView, which is the structure you found to work.

My implementations are written independently in Java (your project is Kotlin +
Compose). If you look at them and feel anything crosses a line, tell me and I'll
change or remove it immediately.

### Attribution

You're credited in three places, all of which state your licence terms and
explicitly say no code was copied and that we're not affiliated with or endorsed
by you:

- the README
- [NOTICE.md](https://github.com/dts88/zeekr-shortcut-car/blob/main/NOTICE.md)
- an in-app "About & Credits" screen
- plus [docs/zeekr-platform-notes.md](https://github.com/dts88/zeekr-shortcut-car/blob/main/docs/zeekr-platform-notes.md),
  which records each platform fact with its source and marks what is measured vs
  still unknown

### Two things I'd genuinely welcome

1. **Tell me if I've overstepped.** I've tried to stay strictly on the
   facts-not-expression side of the line, but you're the one whose work it is.
2. **If you'd ever be willing to license your UI code** for reuse under GPL-3.0,
   I'd much rather build on what you've already tuned on a real car than
   re-derive it. Entirely your call, and a "no" is a perfectly fine answer —
   nothing in my project depends on it.

Thanks again for documenting all of this publicly instead of keeping it to
yourself. It's the reason a second app for this platform could exist at all.
```

---

## 中文版（如果你更想用中文发）

```markdown
你好，首先感谢你这个项目。

我想主动说明一件事，也给你一个提出异议的机会。

### 我做了什么

我发布了一个极氪环视记录仪应用：https://github.com/dts88/zeekr-shortcut-car

它 fork 自 [EVCam](https://github.com/suyunkai/EVCam)（GPL-3.0，作者 suyunkai），
相机管线、编码、存储、回放都来自 EVCam。我在其之上增加了对极氪环视合成流的支持。

### 我用了你项目的什么，没用什么

我阅读了你的 README 和源码。我**没有复制你的任何代码**，也没有再分发你的 APK。
我清楚你的项目是 All rights reserved、未授予开源许可，名称与图标也不授权用于衍生品牌
—— 这些我都遵守了。

我使用的是你公开记录的**事实信息**：

- App Lab 只提供一路已合成的视频流，而不是四个摄像头
- 竖排 `1280×5140`：四个 1280×1280 + 5 条 4px 分隔带
- 横排 `5120×1280`
- 约 28 Mbps、约 200 MB/min 的落盘表现
- HAL 可能只声明一个压扁的小尺寸提示，但内容仍是同一份合成流
- App Lab 不允许把录像直接写入外置 U 盘
- 原厂 360°/倒车/泊车功能可能随时接管相机，必须让路
- **用 GL 自建 SurfaceTexture 顶替相机生产者会崩溃，可行结构是一个普通 TextureView
  由父容器重画进四个格子**

最后这一条要特别谢你。我当时已经把 GL 版本写完了——是我自己写的实现，
但架构上正是你记录下来会崩的那种做法。你在 `ProductHomeCameraPolicy.kt` 里的那句注释，
让我没有把它推上真车、然后完全搞不清预览为什么死掉。我把自己的实现整个删掉，
按你验证可行的结构重写成了容器方案。

我的实现是用 Java 独立编写的（你的项目是 Kotlin + Compose）。
如果你看过之后觉得任何地方越界了，请告诉我，我会立刻修改或删除。

### 署名

你在三个地方被致谢，且都写明了你的许可条款、明确说明未复制代码、
以及本项目与你没有隶属关系也未获你背书：

- README
- [NOTICE.md](https://github.com/dts88/zeekr-shortcut-car/blob/main/NOTICE.md)
- 应用内的「关于与致谢」页面
- 另有 [docs/zeekr-platform-notes.md](https://github.com/dts88/zeekr-shortcut-car/blob/main/docs/zeekr-platform-notes.md)，
  逐条记录平台事实的来源，并标注哪些是实测、哪些仍未验证

### 两件我很希望得到回应的事

1. **如果我越界了，请直接告诉我。** 我尽量严格停在「事实而非表达」这一侧，
   但这毕竟是你的作品。
2. **如果你愿意授权你的界面代码以 GPL-3.0 复用**，我非常乐意直接用你已经在真车上
   调好的那套，而不是自己重新摸索。完全由你决定，拒绝也完全没问题
   —— 我的项目不依赖这件事。

再次感谢你把这些都公开出来，而不是留着自己用。
这是这个平台上能出现第二个应用的唯一原因。
```
