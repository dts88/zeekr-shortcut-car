# 停车警报（Telegram）

Parking Alerts (Telegram)

> 原创设计与实现：Fab (Fabbbrrr) — https://github.com/Fabbbrrr。首次发布于 PR #211；按本仓库的惯例，引用这份规格里的事实时请标注来源。

> Original design and implementation: Fab (Fabbbrrr) — https://github.com/Fabbbrrr. First released in PR #211; per this repo's convention, cite the source when referencing facts from this spec.


车停着、哨兵模式开着的时候，车上的动静把四个摄像头的一张照片发到车主配好的 Telegram 会话。
这一份写清楚**什么会触发、什么不会**、发到哪、以及令牌存在哪。改动只许照着这里写。

##### English

When the car is parked with sentry mode armed, activity in the car sends one photo from the four
cameras to the owner's configured Telegram chat. This document spells out **what triggers an alert
and what does not**, where it goes, and where the token lives. Changes must follow what is written
here.

## 1. 做什么

车停着、**哨兵模式已布防（即锁车，读数 2）**、设置在「停车警报」里开着、令牌和会话号都填了 —— 这四条同时成立时，
下面任意一件事发生，就发一条：四个摄像头的一张照片，配一句「发生了什么 + 时间 + 哨兵模式状态」。

| 触发 | 信号 | 说明 |
|---|---|---|
| 双闪亮起 | `Signal.INDICATOR == 3`（上升沿） | 车报时闪灯 |
| 远光闪一下 | `Signal.HIGH_BEAM_FLASH`（上升沿） | 拨杆闪灯 |
| 车门 / 前备箱 / 后备箱从关到开 | `Signal.DOOR_*`（上升沿） | 六个门各算一个 |
| 车灯自亮 | `DRL` / `FRONT_POSITION_LAMP` / `REAR_POSITION_LAMP` / `LOW_BEAM`（任一从灭到亮） | 停车锁车后这几盏该是灭的 |
| 中控屏亮起 | `SCREEN_POWER_EVENT == 0`（从黑到亮） | 哨兵被触发时屏幕会亮 —— 车主回来时也会 |

判断在 `alarm.AlertPolicy`（纯逻辑，`AlertPolicyTest`）：停着 + 哨兵开着才发；一条之后 60 秒静默；
每小时最多 6 条。冷却和上限是为了让「一次事件触发好几个信号」变成一条消息，
也是为了压住误判（夜里双闪被反复拨、门开着又关）。

##### English

When all four hold at once — the car is parked, **sentry mode is armed (i.e. locked, reading 2)**,
the "Parking alerts" setting is on, and the token and chat id are filled in — any one of the
following events sends one message: one photo from the four cameras with a line of "what happened +
time + sentry mode state".

| Trigger | Signal | Note |
|---|---|---|
| Hazard lights turn on | `Signal.INDICATOR == 3` (rising edge) | The car flashes when it alarms |
| High beam flashes once | `Signal.HIGH_BEAM_FLASH` (rising edge) | Stalk flash |
| Door / frunk / tailgate opens from closed | `Signal.DOOR_*` (rising edge) | Each of the six doors counts on its own |
| Lights turn on by themselves | `DRL` / `FRONT_POSITION_LAMP` / `REAR_POSITION_LAMP` / `LOW_BEAM` (any from off to on) | These should be off after parking and locking |
| Center screen turns on | `SCREEN_POWER_EVENT == 0` (from off to on) | The screen turns on when sentry fires — and when the owner returns |

The decision lives in `alarm.AlertPolicy` (pure logic, `AlertPolicyTest`): it sends only when parked
and sentry is on; 60 seconds of silence after one message; at most 6 messages per hour. The cooldown
and cap turn "one event firing several signals" into a single message, and also suppress false
positives (hazards toggled repeatedly at night, a door opened and closed).

## 1.5 车主自己选哪几项算「有事」

设置 → 停车警报 →「什么算有事」里勾。存的是名字（`alarm.TriggerSet`），认不出来的名字当没写，
一项都不剩时按全开算 —— 存坏的设置不该把警报变成哑巴。

| 名字 | 界面上说 | 从哪来 |
|---|---|---|
| `motion` | 画面里有东西在动 | 编码器的 GL 回读（见 §1.6） |
| `hazard` | 双闪亮起 | `INDICATOR == 3` |
| `flash` | 远光闪一下 | `HIGH_BEAM_FLASH` |
| `doors` | 车门 / 前备箱 / 后备箱被打开 | `DOOR_*` |
| `lamps` | 车灯自己亮起 | `DRL` / 位置灯 / 近光 |
| `screen` | 中控屏亮起 | `SCREEN_POWER_EVENT` |

##### English

Checkboxes under Settings → Parking alerts → "what counts as an event". Names are stored
(`alarm.TriggerSet`); an unrecognized name counts as not written, and when nothing is left
everything is treated as on — a broken setting must not silence the alerts.

| Name | Shown in the UI as | Where it comes from |
|---|---|---|
| `motion` | Something is moving in the frame | GL readback from the encoder (see §1.6) |
| `hazard` | Hazard lights turn on | `INDICATOR == 3` |
| `flash` | High beam flashes once | `HIGH_BEAM_FLASH` |
| `doors` | Door / frunk / tailgate opens | `DOOR_*` |
| `lamps` | Lights turn on by themselves | `DRL` / position lamps / low beam |
| `screen` | Center screen turns on | `SCREEN_POWER_EVENT` |

## 1.6 画面移动：从编码器里读，而不是再拍一张

环视的每一帧都经过 `EglSurfaceEncoder` 的 GL 上下文。每 250 ms 把当前帧画进一张 128×72 的离屏缓冲，
`glReadPixels` 读回亮度，交给 `alarm.FrameDiff` 过三道：整幅平均亮度跳变（当曝光）、平均差、
显著变化像素占比。任何一步出错就把这条采样彻底关掉 —— 它是附加功能，不能因为它把录像折了。

只在**停着、布防、在录、且 `motion` 选中**时取样：开车时画面本来就在动。

##### English

Every surround-view frame passes through the GL context of `EglSurfaceEncoder`. Every 250 ms the
current frame is drawn into a 128×72 off-screen buffer, `glReadPixels` reads the brightness back,
and `alarm.FrameDiff` runs three checks: whole-frame average brightness jump (as exposure), mean
difference, and the share of significantly changed pixels. Any error turns this sampling off
entirely — it is an add-on and must not break recording because of it.

Sampling happens only when **parked, armed, recording, and `motion` is selected**: the frame moves
anyway while driving.

## 1.7 锁车之后等一会儿

锁车、关车门、往车位外面走的那一分钟，车会亮灯、屏幕会亮、画面里一直有人在动 —— 都是车主自己。
所以警报要等一会儿才开始报：设置里可选**立刻 / 1 分钟 / 3 分钟**，默认 1 分钟（`alarm.ArmDelay`）。

判断依据是「这一次布防从什么时候开始」：看到哨兵从没布防变成布防那一刻起算；进程刚起来、车本来就锁着时
用信号变化的记录（`SignalAges`），仍然不知道就**照常发** —— 宁可偶尔吵一下，也不能因为不知道就把真事吞掉。
被这一段挡下的警报在黑匣子里写一行（`alerts: armed N s ago, waiting for M min`）。

##### English

In the minute after locking, closing the door, and walking away from the space, the car turns on
lights, the screen lights up, and people keep moving in the frame — all the owner. So the alerts
wait a bit before starting: the setting offers **immediately / 1 minute / 3 minutes**, default
1 minute (`alarm.ArmDelay`).

The basis is "when this arming started": count from the moment sentry is seen to change from
unarmed to armed; when the process just started and the car was already locked, use the record of
signal changes (`SignalAges`), and if still unknown, **send as usual** — better to occasionally be
noisy than to swallow a real event because it was unknown. Alerts blocked by this wait write one
line in the black box (`alerts: armed N s ago, waiting for M min`).

## 1.9 第一张只有 100 kB，要看更多再点

车主 2026-11-15 定的：**第一条消息只发一张压到 100 kB 以下的照片**，看得见发生什么，小到可以完全不管；
要看清楚、要看那几秒，在聊天里点一下再传。所以车上往上传的量只有他真的想看的那一份。

| 做什么 | 多大 | 怎么做的 |
|---|---|---|
| 警报第一张 | **≤ 100 kB** | `PictureBudget`：先降画质（70 → 45），再降尺寸（1.0 → 0.85 → 0.7），一档档试到进预算 |
| 照片下面的 **HD 一张** | 400–600 kB | 老样子那一档（`AlertPicture.jpeg`，上限 900 kB），车牌更清楚 |
| 照片下面的 **片段 · 哪一路** | **≤ 10 MB** | 从那一路自己的样本环里切 5 或 10 秒（设置里那一档），超了不发、回一句实际大小 |

- 每颗按钮带这一次警报的号（`AlertButton`，`a:<action>:<token>`），只对这一次有效；一周前那条消息上的
  按钮按下去什么都不做。按下先 ACK（`answerCallbackQuery`），再动手。
- **片段不再跟着照片自动发**：切视频要走那一路的录制器，按需才值得。长度用「短视频」那一档，
  关掉就只剩 HD 那颗按钮。
- **片段多大由录制那边定**：哨兵 / 熄屏那一段的帧率设置（「跟随视频流配置」或固定 10 fps 之类）就是
  让这件事可控的旋钮；按需切出来的片段直接来自那段录像，不重新编码。
- 一次只切一段：样本环是共享的，连点两下不该起两个切片的活。

###### English

The owner's rule (2026-11-15): **the first message carries one picture compressed below 100 kB** — enough
to see what is happening, small enough to ignore. Sharper, or those few seconds, is one tap away, so the
car uploads only what the owner actually wants to look at.

| What | Size | How |
|---|---|---|
| The alert's first picture | **≤ 100 kB** | `PictureBudget`: quality down first (70 → 45), then scale (1.0 → 0.85 → 0.7), step by step into the budget |
| **HD picture** under it | 400–600 kB | The old ladder (`AlertPicture.jpeg`, capped at 900 kB) for readable plates |
| **Clip · one camera** under it | **≤ 10 MB** | 5 or 10 seconds cut from that camera's own sample ring (the setting decides); over the limit it is refused with the real size |

Each button carries this alert's token (`AlertButton`, `a:<action>:<token>`), so a button on a week-old
message does nothing, and the tap is acknowledged before any work starts. Clips no longer ride along with
the picture: cutting one needs that camera's recorder, and on demand is when it is worth doing. The clip's
size is decided on the recording side — the sentry frame-rate setting is the knob — and nothing is
re-encoded. One clip at a time, because the sample ring is shared.

## 1.10 事件清单：每一项都能当场发一条

设置 → 停车警报 → **事件清单**（`alarm.AlertEventsActivity`）把车能发给机器人的**每一条消息**列成一张表，
一行一条，下面一句写清楚「真的什么时候会发」，点一下就用真的发送路发一条出去。触发器是人点的，消息本身
不是假的：一样的照片、一样的按钮、一样的文案。

覆盖：六项触发器各自那一条警报（双闪 / 远光 / 车门 / 车灯 / 中控屏 / 画面移动）、`pic` 那一张、HD 那一张、
四路片段、状态一句话、帮助。
表在 `alarm.AlertEvent` 里（标题 + 描述各一个字符串资源），`AlertEventTest` 钉住「每行都有自己的一句」和
「六项触发器一行不少」。表是配好之后「它到底发什么」；配好之前那三步（令牌 → 找到聊天 → 试一张）
每一屏的截图在 `docs/setup-wizards.md`。

###### English

**Events the car can send** (Settings → Alert parking → Events, `alarm.AlertEventsActivity`) lists every
message the car can send, one row each, with a line saying when it really goes out; a tap sends that exact
message through the real path. Only the trigger is simulated — same picture, same buttons, same wording.
The table lives in `alarm.AlertEvent`, and `AlertEventTest` pins one description per row and all six
triggers present. It answers "what does it send" once configured; the three setup steps before that
(token → find the chat → one picture) are in `docs/setup-wizards.md`, one picture per screen.

## 2. 不做什么（车上看不到的信号）

这台车的车辆接口没有撞击、气囊、喇叭、防盗 / 倾斜 / 震动触发的信号，
`telemetry.Signal` 里就没有这几项（`storage/AutoLock.java` 的注释也写了喇叭读不到）。
所以：

- 「有人蹭了一下车，没亮双闪、没开门」—— **看不见**，这一版不发。
- 像素级的移动检测是下一步，不在这一个改动里：要拿到帧得在编码那条 GL 线程上读回像素，
  是另一份改动、要在车上量。

照片是环视四路重排成的 2×2（`zeekr.CompositeBitmapComposer#toGrid`），不是前 / 后座舱摄像头。

##### English

This car's vehicle interface has no impact, airbag, horn, or anti-theft / tilt / vibration trigger
signals, so `telemetry.Signal` has none of these entries (the comment in `storage/AutoLock.java`
also notes the horn cannot be read). Therefore:

- "Someone bumped the car without turning on the hazards or opening a door" — **cannot be seen**,
  not sent in this version.
- Pixel-level motion detection is a next step, not part of this change: getting frames requires
  reading pixels back on the encoding GL thread, which is a separate change to be measured on the
  car.

The photo is the surround-view four channels rearranged into 2×2
(`zeekr.CompositeBitmapComposer#toGrid`), not the front / rear cabin cameras.

## 3. 照片从哪来

`alarm.SnapshotTaker` 走的是拍照键那条路：`CameraKeeper#takePhoto` →
`MultiCameraManager#takePhoto` → 各路 `SingleCamera#takePicture` → 写盘成
`<时间戳>_surround.jpg`。不重开相机、不重启录像。

`PhotoCallback#onPhotoResult` 只回报存了几路、不给文件名，所以拍完是在照片目录里找**这一趟新写下的**
那张环视图（文件名带时间戳，后缀 `CameraSlots.suffixFor(KEY_SURROUND)`），找不到就退回只发文字。
照片写到 1280 px 宽、JPEG 80 再发；超过 9 MB 不发（Telegram `sendPhoto` 上限 10 MB）。

##### English

`alarm.SnapshotTaker` uses the same path as the shutter button: `CameraKeeper#takePhoto` →
`MultiCameraManager#takePhoto` → each `SingleCamera#takePicture` → written to disk as
`<timestamp>_surround.jpg`. It does not reopen the camera or restart recording.

`PhotoCallback#onPhotoResult` only reports how many channels were saved, not the file name, so
after the shot the code looks in the photo directory for **the one newly written this round**
surround view (the file name carries a timestamp, suffix
`CameraSlots.suffixFor(KEY_SURROUND)`); if not found it falls back to text only. The photo is
written 1280 px wide, JPEG 80, then sent; over 9 MB is not sent (Telegram `sendPhoto` limit is
10 MB).

## 3.5 附不附图、发几张、哪几路、掰不掰直

- **附一张照片**（默认开，`pref_alerts_send_picture`，车主 2026-11-17）：关掉之后每条警报只发那句话，
  车<b>根本不去拍</b> —— 不走四路相机、不拼 2×2、不压到 100 kB。理由是实的：一次几百毫秒和一块位图，
  一晚上十几次；有人只想知道「双闪亮了」。关掉不影响<b>要</b>图：聊天里的 `pic`、HD、片段照旧，那是车主
  自己开口要的；「一张测试照片」那一行也照旧，它本来就写着是一张照片。下面三行跟着这个开关收起来。
- **一张 2×2**（默认）：四个画面在一起，一眼看全。
- **每一路各一张**：从四宫格裁出来单发，手机上每张占满屏；设置里勾哪几路就发哪几路，一路都不勾按四路算。
- **掰直**：和回看界面同一个鱼眼校正（`FisheyeCorrector.correctGrid`，视野和投影跟着「超级镜子」那一项），
  默认关。
- 每张压到 900 kB 以下（80 → 70 → 60 → 50 一档档降），车上 4G 上传越小越不容易被掐断。
- 四张分开发时一条条按顺序发，不并发，免得撞上 Telegram 的频率限制。

##### English

- **Attach a picture** (on by default, `pref_alerts_send_picture`, owner 2026-11-17): with it off each
  alert sends the line alone and the car **never takes the picture** — no four lanes, no 2×2 stitch, no
  squeeze under 100 kB. The reason is concrete: a few hundred milliseconds and a bitmap, a dozen times a
  night, for somebody who only wants to know the hazards came on. Asking still works: `pic` in the chat,
  HD and clips are the owner asking, and the row named "a test picture" still sends one. The three rows
  below this switch collapse with it.
- **One 2×2** (default): all four views together, the whole picture at a glance.
- **One per channel**: cropped out of the 2×2 and sent separately, each fills the phone screen; the
  channels checked in settings are sent, and with none checked all four are sent.
- **Dewarp**: the same fisheye correction as the playback screen (`FisheyeCorrector.correctGrid`,
  field of view and projection follow the "Super mirror" item), off by default.
- Each photo is compressed to under 900 kB (80 → 70 → 60 → 50 step by step); over the car's 4G,
  smaller uploads are less likely to be cut off.
- When four photos go out separately they are sent one by one in order, not concurrently, to avoid
  Telegram's rate limit.

## 4. 发到哪

Telegram Bot API，`api.telegram.org`，不需要自建服务器、不需要公网地址，车用 4G 往外发就行
（车在运营商 NAT 后面，本来也接不进来）。

- 令牌：@BotFather 给的，`alarm.TelegramRequest#looksLikeToken` 只认「数字:一串字符」的外形。
- 会话号：`getUpdates` 里最近给机器人发过话的会话；设置有「找会话号」自动取，多个会话时让车主选。
  那三步（令牌 → 找到聊天 → 试一张）每一屏的截图在 `docs/setup-wizards.md`。
- 发不出去时：黑匣子记一行，本地通知说一次（同一句 10 分钟内不重复），照片发不成就只发文字。

##### English

Telegram Bot API, `api.telegram.org` — no self-hosted server and no public address needed; the car
just sends out over 4G (the car is behind the carrier's NAT, so nothing could reach in anyway).

- Token: from @BotFather; `alarm.TelegramRequest#looksLikeToken` only accepts the shape
  "digits:some characters".
- Chat id: the chat that most recently messaged the bot in `getUpdates`; settings has "find chat
  id" to fetch it automatically, and with several chats the owner picks one. Screenshots of the three
  steps (token → find the chat → one picture) are in `docs/setup-wizards.md`.
- When sending fails: one line in the black box, one local notification (the same message not
  repeated within 10 minutes), and if the photo fails, text only is sent.

## 5. 令牌存在哪

**单独一个 SharedPreferences 文件**：`AppConfig.SECRETS_PREF_NAME`（`app_config_secrets`）。
不进 `app_config`，所以不进设置导出（`SettingsBackup.snapshot` 只读 `app_config`）、
不进诊断报告、不进黑匣子。设置页只显示末四位。

任何要写下来的字符串先过 `TelegramRequest#redact`：Bot API 把令牌放在路径里，
异常和日志最容易把它带出去。`AlertSecretsTest` 钉住两件事：导出里没有令牌；令牌不在 `app_config` 里。

##### English

**A single separate SharedPreferences file**: `AppConfig.SECRETS_PREF_NAME`
(`app_config_secrets`). It does not go into `app_config`, so it is not in the settings export
(`SettingsBackup.snapshot` reads only `app_config`), not in diagnostic reports, not in the black
box. The settings page shows only the last four characters.

Any string to be written down first passes through `TelegramRequest#redact`: the Bot API puts the
token in the path, where exceptions and logs most easily carry it out. `AlertSecretsTest` pins two
things: no token in the export; the token is not in `app_config`.

## 6. 什么时候不发

- 设置在「停车警报」里关着，或者令牌 / 会话号缺一个：`AlertPolicy.Decision.OFF`，什么都不做。
- 车不在 P 挡：`NOT_PARKED`。
- 哨兵模式没开（`Signal.SENTRY_MODE` 是 0 或者读不到）：`NOT_ARMED`。
- 60 秒冷却里，或者这一小时已经 6 条：`COOLDOWN` / `HOURLY_CAP`。这些都在黑匣子里记一行，只是不发。

##### English

- The setting is off under "Parking alerts", or the token / chat id is missing:
  `AlertPolicy.Decision.OFF`, do nothing.
- The car is not in P: `NOT_PARKED`.
- Sentry mode is off (`Signal.SENTRY_MODE` is 0 or unreadable): `NOT_ARMED`.
- Inside the 60-second cooldown, or this hour already has 6: `COOLDOWN` / `HOURLY_CAP`. These all
  write a line in the black box but send nothing.

## 6.5 车主自己回来不发：解锁会先把「布防」打掉

`Signal.SENTRY_MODE` 是 0 关、1 开、2 布防。车上记下来的顺序（2026-10-03 / 10-12，时刻见 §7「测量出处」）：

- 开哨兵 0 → 1；**锁车**一分半后 1 → 2；
- **解锁** 2 → 1（`0x20240300` 1 → 0 之后 10 毫秒）；
- 挂 D 1 → 0。

所以判断只认 2（`alarm.SentryFacts#armed`，`SentryFactsTest` 钉住）。车主回来时解锁那一下，
屏幕还没亮、车灯还没亮，读数已经是 1 了，后面的上升沿全部落在“没布防”上，一条也不发。
有人碰车时车还锁着，2 不动，灯 / 屏 / 双闪亮起来 —— 发。

这正是“锁车就是布防”这条事实的用处，而不是我们另加的一个猜测。

**还不采用的**：`0x20240300`（VSTD_ALRM_STS）据称是“锁车后进入防盗”，解锁 1 → 0，但只有一次观察
（2026-10-03，§7）、主项目明确没采用。等有交叉验证再拿它当第二重证据。

##### English

`Signal.SENTRY_MODE` is 0 off, 1 on, 2 armed. The order recorded on the car
(2026-10-03 / 10-12, times in §7, "where the numbers come from"):

- Sentry on 0 → 1; **locked**, a minute and a half later 1 → 2;
- **Unlock** 2 → 1 (10 ms after `0x20240300`);
- Shift to D 1 → 0.

So the check accepts only 2 (`alarm.SentryFacts#armed`, pinned by `SentryFactsTest`). At the
unlock when the owner returns, before the screen or lights have turned on, the reading is already
1, so all the rising edges after that land on "not armed" and nothing is sent. When someone
touches the car it is still locked, 2 holds, and the lights / screen / hazards turn on — send.

That is the point of the fact "locked means armed", not an extra guess we added.

**Not adopted yet**: `0x20240300` (VSTD_ALRM_STS) is said to be "entered anti-theft after locking",
1 → 0 on unlock, but there is only a single observation (2026-10-03, §7) and the main project
explicitly did not adopt it. It will become a second line of evidence only after cross-validation.

## 6.6 反向命令：给机器人发 pic

设置里打开「回应 Telegram 命令」之后，车会去问 Telegram「有人找我吗」—— 长轮询
（`getUpdates`，25 秒一条请求）。车在运营商 NAT 后面接不进来，但发得出去，消息就塞在这条请求的
回复里回来。

- `pic` / `photo` / `snap`：现在拍一张，按「发几张」那一项来；后面可以跟 `front` / `rear` /
  `left` / `right` 只拍那一路。
- `status`：回一句哨兵状态和挡位，不拍。
- **只在哨兵模式开着时回应**：哨兵模式是车机保持醒着的唯一原因（平台笔记 §3.6）。哨兵关掉之后
  车机会下电、进程结束，那时既问不到也没法拍 —— 所以哨兵一关，这条轮询当场停掉，不留一条
  必然要死的线程。
- **只认配好的那个会话**：别的会话发来什么都不回，只在黑匣子记一笔。回了就是一个谁都能要照片的相机。
- **停着才拍**（挡位 P），30 秒一张、一小时 6 张（`RequestPolicy`）。
- **处理过就不再处理**：`update_id` 存进设置（运行状态档），重启不会重放上一条命令。
- **一个机器人只有一个轮询者**：Telegram 对同一个机器人并发轮询回 409。别处（脚本、Home
  Assistant）不能再轮询同一个机器人，否则车上这条会一直退避。

**要叫醒一辆睡着的车**，只有两条路，都不在这一版里：让车自己的云端发一条远程命令把它唤醒
（要一个常驻的免费 Worker，已经有了雏形），或者拿住唤醒锁不让车机睡（12 V 电池的账要算，
最多加时长上限）。哨兵模式开着时不需要任何一条 —— 车机本来就醒着。

##### English

After "Respond to Telegram commands" is enabled in settings, the car asks Telegram "has anyone
messaged me" — long polling (`getUpdates`, one request every 25 seconds). The car sits behind the
carrier's NAT so nothing can reach in, but it can send out; messages come back inside the reply to
that request.

- `pic` / `photo` / `snap`: take one now, following the "how many to send" setting; may be
  followed by `front` / `rear` / `left` / `right` to shoot only that channel.
- `status`: reply with the sentry state and gear, no photo.
- **Replies only while sentry mode is on**: sentry mode is the only reason the head unit stays
  awake (platform notes §3.6). Once sentry is off the head unit powers down and the process ends,
  so it can neither be asked nor take a photo then — so the polling stops on the spot when sentry
  turns off, leaving no thread that is bound to die.
- **Only the configured chat is honored**: messages from any other chat get no reply, only a line
  in the black box. Replying to everyone would make it a camera that anyone could ask for photos.
- **Takes a photo only when parked** (gear P), one every 30 seconds, 6 per hour (`RequestPolicy`).
- **Processed means never processed again**: `update_id` is saved in settings (runtime state), so
  a restart does not replay the last command.
- **One poller per bot**: Telegram returns 409 for concurrent polling of the same bot. Nothing else
  (scripts, Home Assistant) may poll the same bot, or the car's poller keeps backing off.

**To wake a sleeping car** there are only two routes, neither in this version: have the car's own
cloud send a remote command to wake it (needs an always-on free Worker, a prototype already
exists), or hold a wake lock to keep the head unit from sleeping (the 12 V battery cost has to be
accounted, at most add a time limit). With sentry mode on neither is needed — the head unit is
already awake.

## 6.7 短视频：数据量

`alerts_clip_seconds` 为 5 或 10 时，照片发出去之后再发一段同样长度的短视频，取的是环里已经编码好的样本
（`ClipWindow` 选窗口、`CodecVideoRecorder.writeClipSeconds` 自己 mux），不重开相机、不重新编码、
不碰正在写的文件。声音跟着画面走，`alerts_clip_audio` 关掉就是静音。

每次警报的数据量：照片 < 1 MB；5 秒约 2–4 MB；10 秒约 5–8 MB。全部走车的 4G。Telegram 的上限是 50 MB，
所以尺寸不是问题，**弱网才是**：所以照片永远是第一条，视频是附加的，上传失败只记一行、不重试到天亮。

##### English

When `alerts_clip_seconds` is 5 or 10, after the photo is sent a short video of the same length
goes out, taken from samples already encoded in the ring (`ClipWindow` picks the window,
`CodecVideoRecorder.writeClipSeconds` muxes it itself) — no camera reopen, no re-encode, no
touching the file being written. Sound follows the picture; `alerts_clip_audio` off means silent.

Data volume per alert: photo < 1 MB; 5 seconds about 2–4 MB; 10 seconds about 5–8 MB. All of it
goes over the car's 4G. Telegram's limit is 50 MB, so size is not the issue, **weak network is**:
so the photo is always first, the video is an add-on, and a failed upload writes one line and does
not retry all night.

## 7. 人为的悬崖

- **测量出处**（这些数字原本记在平台笔记里；平台笔记 2026-11-15 起不再放在仓库里，所以数字搬到这里）：
  哨兵没开、车主离车之后，车机会拿走座舱相机 1，约四分钟后下电 —— 2026-10-10 三次：09:16 → 09:20、
  14:23 → 14:27、17:07 → 17:11；安卓熄屏广播之后 3.3–4.6 秒进程被信号结束或者冻住（冻住那一次的最后
  一行在熄屏后 3.4 秒）。2026-10-12 又一次离车，熄屏广播 4–6 分钟才来。哨兵模式的值：0 关、1 开、
  2 布防 —— 开哨兵 0 → 1，锁车一分半后 1 → 2（2026-10-12 那次是屏幕黑了 99 秒后 1 → 2），解锁 2 → 1，
  挂 D 1 → 0。本规格里的窗口（5 s、60 s、180 s）都比这段寿命短。
- 环视那一路的 JPEG 出帧口在会话配置失败时是第一个被让出去的（`camera/ChannelRules.java`
  `onConfigFailed`）。那种状态下拍照会退回预览取帧，需要主界面在前台 —— 长时间熄屏的哨兵录像碰上它，
  照片可能拍不出来。拍不出来就只发文字，不假装发了照片。
- 令牌是明文存在应用私有目录里（没有引入加密库的做法，也没有假加密）。**拿到令牌的人能做的事比「发几条消息」多**：
  他可以调 `getUpdates` 读到**发给这个机器人的每一条消息**（包括你发的 `pic` / `status`，以及机器人被拉进的群里别人发的内容），
  也可以冒充机器人往那个会话发消息。所以：机器人只给这辆车用，令牌不进设置导出、不进诊断报告、不进日志、
  不在屏幕上显示；APK 一旦外流就换一个令牌。谁能读到这辆车的应用数据，谁就有这个机器人。

##### English

- **Where the numbers come from** (they used to live in the platform notes; those notes left the
  repository on 2026-11-15, so the numbers moved here): with sentry off the head unit takes the rear
  cabin camera and powers down about four minutes after the owner leaves — 2026-10-10, three times:
  09:16 → 09:20, 14:23 → 14:27, 17:07 → 17:11 — and the process is killed or frozen 3.3–4.6 s after
  the Android screen-off broadcast (the frozen run's last line was 3.4 s after it); on 2026-10-12
  the broadcast came 4–6 minutes after leaving. Sentry mode values: 0 off, 1 on, 2 armed — sentry on
  0 → 1, locked and a minute and a half later 1 → 2 (on 2026-10-12 it was 99 s after the screen went
  dark), unlock 2 → 1, shift to D 1 → 0. Every window in this spec (5 s, 60 s, 180 s) is shorter
  than that life.
- The surround channel's JPEG output is the first thing given up when the session configuration
  fails (`camera/ChannelRules.java` `onConfigFailed`). In that state taking a photo falls back to
  preview capture, which needs the main UI in the foreground — sentry recording with the screen off
  for a long time can hit it, and the photo may not come out. If it doesn't, send text only, never
  pretend a photo was sent.
- The token is stored in plaintext in the app's private directory (no encryption library was
  introduced, and no fake encryption either). **Whoever gets the token can do more than "send a few
  messages"**: they can call `getUpdates` to read **every message sent to this bot** (including
  your `pic` / `status`, and what others post in groups the bot was added to), and can impersonate
  the bot to send messages to that chat. So: the bot serves only this car, the token never enters
  the settings export, diagnostic reports, logs, or the screen; if the APK ever leaks, replace the
  token. Whoever can read this car's app data owns this bot.
