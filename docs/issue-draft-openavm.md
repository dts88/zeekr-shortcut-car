# 给 openavm-recorder 的 issue 草稿

> 用途：向 openavm-recorder 作者 Dantenothing 致谢，并主动确认边界。
> 提交地址：https://github.com/Dantenothing/openavm-recorder/issues/new
>
> **未经你确认，不会替你提交。** 内容可随意增删。
>
> 范围：只谈 openavm-recorder 本身。不提任何其他项目，不介绍我在做什么应用、
> 有什么功能——那些与本次致谢无关，也没必要占用对方的注意力。

---

## 标题

```
Thank you for documenting the composite stream — and a note on how I used it
```

## 正文（英文，建议直接用这版）

```markdown
Hi,

This isn't a bug report — I just wanted to say thank you, and to be transparent
about how I've used what you published.

### Thank you for the platform documentation

You could have kept all of this to yourself. Instead you wrote down, in public
and in detail, what the App Lab environment actually hands a third-party app:

- one already-composited stream rather than four cameras
- `1280x5140` vertical: four 1280x1280 views plus five 4px separator bands
- the `5120x1280` horizontal variant
- the ~28 Mbps / ~200 MB/min recording profile
- the fact that the HAL may declare only a small squashed surface hint while
  still delivering the same composite content
- the principle of only ever using sizes the HAL actually declares, with no
  invented fallbacks
- that factory 360 / reverse / parking functions may reclaim the camera, and that
  a third-party app must yield rather than compete

I also appreciate that you documented the limits of what you'd established, and
the open questions about shared resources, rather than overclaiming. That's rare.

### The one that saved me

The comment in `ProductHomeCameraPolicy.kt`:

> "The old automatic preview crashed because it replaced the working producer
> with a GL-owned SurfaceTexture. The current path has been verified on the car:
> Camera2 feeds one ordinary TextureView and its parent redraws that same child
> into four cells."

I had already built the GL version — my own code, but architecturally the exact
thing you'd found to crash. I would have shipped it to a real vehicle and had no
idea why the preview died. I deleted it and rewrote it around a plain TextureView
redrawn by its parent, which is the structure you found to work.

That one comment was worth more than anything else I read. Thank you for leaving
it in the source instead of just fixing it silently.

### How I used your work — and how I didn't

I've written a separate application that targets the same composite stream.
I want to be precise about the boundary:

- I **read** your README and your source. I understand that's the stated purpose
  of your source being publicly visible — inspection — and that visibility is not
  a licence.
- I have **not copied any of your code**. My implementations are written
  independently in Java; yours is Kotlin + Compose.
- I have **not** redistributed your APK, forked your repository, or used the
  AVM Recorder name or logo in any way.
- What I used is the **factual information** above: resolutions, layout, band
  arithmetic, and the behavioural constraints you documented.
- I credit you by name and link, state your `All rights reserved` terms, and say
  explicitly that no code was copied and that I'm not affiliated with or endorsed
  by you — in the project README, in a NOTICE file, and in an in-app credits
  screen.

### Two things I'd welcome

1. **If you think I've overstepped, tell me.** I've tried to stay strictly on the
   facts-not-expression side of the line, but it's your work and your call. If
   anything needs to change or come down, say so and I'll do it.
2. **If you'd ever consider licensing your code for reuse**, I'd be glad to talk.
   Entirely up to you, and "no" is a completely fine answer — nothing I've built
   depends on it.

Either way: thanks for the work, and for publishing what you learned.
```

---

## 中文版（如果你更想用中文发）

```markdown
你好，

这不是一个 bug 报告——我只是想说声谢谢，并且主动把我使用你公开成果的方式讲清楚。

### 感谢你把平台细节记录下来

这些东西你完全可以自己留着。但你选择了公开、并且详细地写下 App Lab 环境到底
给第三方应用什么：

- 一路已经合成好的视频流，而不是四个独立摄像头
- 竖排 `1280×5140`：四个 1280×1280 画面 + 5 条 4px 分隔带
- 横排 `5120×1280` 变体
- 约 28 Mbps / 约 200 MB/min 的录制表现
- HAL 可能只声明一个压扁的小尺寸提示，但送来的仍是同一份合成内容
- 只使用 HAL 真正声明过的尺寸、绝不臆造回退值这一原则
- 原厂 360°/倒车/泊车功能可能随时收回相机，第三方必须让路而不是去争

我也很欣赏你同时写明了「哪些还没被确立」以及关于共享资源的开放问题，
而不是把话说满。这很少见。

### 真正帮到我的那一条

`ProductHomeCameraPolicy.kt` 里的这段注释：

> "The old automatic preview crashed because it replaced the working producer
> with a GL-owned SurfaceTexture. The current path has been verified on the car:
> Camera2 feeds one ordinary TextureView and its parent redraws that same child
> into four cells."

我当时已经把 GL 版本写完了——是我自己写的代码，但架构上正是你发现会崩的那种做法。
我本会把它装上真车，然后完全搞不清预览为什么死掉。我把它删了，
改成由父容器重画一个普通 TextureView 的结构，也就是你验证可行的那套。

这一条注释比我读到的其他任何内容都有价值。谢谢你把它留在源码里，
而不是默默改掉就算了。

### 我用了什么，没用什么

我写了一个独立的应用，同样面向这路合成流。边界我想说清楚：

- 我**阅读**了你的 README 和源码。我理解源码公开可见的用途正是「供检查」，
  而可见并不等于授权。
- 我**没有复制你的任何代码**。我的实现是用 Java 独立编写的，你的是 Kotlin + Compose。
- 我**没有**再分发你的 APK、没有 fork 你的仓库，也没有以任何方式使用
  AVM Recorder 的名称或图标。
- 我使用的是上面那些**事实信息**：分辨率、排布、分隔带算法，以及你记录的行为约束。
- 我在项目 README、NOTICE 文件和应用内的致谢页面三处署你的名并附链接，
  写明你的 All rights reserved 条款，并明确声明未复制代码、
  与你没有隶属关系也未获你背书。

### 两件我很希望得到回应的事

1. **如果你认为我越界了，请直接告诉我。** 我尽量严格停在「事实而非表达」这一侧，
   但这是你的作品，由你判断。任何需要修改或撤下的地方，你说，我就做。
2. **如果你愿意考虑授权你的代码被复用**，我很乐意聊聊。完全由你决定，
   拒绝也完全没问题——我做的东西不依赖这件事。

无论如何：感谢你的工作，也感谢你把学到的东西公开出来。
```
