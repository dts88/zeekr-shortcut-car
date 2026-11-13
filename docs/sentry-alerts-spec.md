# 停车警报（Telegram）

车停着、哨兵模式开着的时候，车上的动静把四个摄像头的一张照片发到车主配好的 Telegram 会话。
这一份写清楚**什么会触发、什么不会**、发到哪、以及令牌存在哪。改动只许照着这里写。

## 1. 做什么

车停着、哨兵模式开着、设置在「停车警报」里开着、令牌和会话号都填了 —— 这四条同时成立时，
下面任意一件事发生，就发一条：四个摄像头的一张照片，配一句「发生了什么 + 时间 + 哨兵模式状态」。

| 触发 | 信号 | 说明 |
|---|---|---|
| 双闪亮起 | `Signal.INDICATOR == 3`（上升沿） | 车报时闪灯 |
| 远光闪一下 | `Signal.HIGH_BEAM_FLASH`（上升沿） | 拨杆闪灯 |
| 车门 / 前备箱 / 后备箱从关到开 | `Signal.DOOR_*`（上升沿） | 六个门各算一个 |

判断在 `alarm.AlertPolicy`（纯逻辑，`AlertPolicyTest`）：停着 + 哨兵开着才发；一条之后 60 秒静默；
每小时最多 6 条。冷却和上限是为了让「一次事件触发好几个信号」变成一条消息，
也是为了压住误判（夜里双闪被反复拨、门开着又关）。

## 2. 不做什么（车上看不到的信号）

这台车的车辆接口没有撞击、气囊、喇叭、防盗 / 倾斜 / 震动触发的信号，
`telemetry.Signal` 里就没有这几项（`storage/AutoLock.java` 的注释也写了喇叭读不到）。
所以：

- 「有人蹭了一下车，没亮双闪、没开门」—— **看不见**，这一版不发。
- 像素级的移动检测是下一步，不在这一个改动里：要拿到帧得在编码那条 GL 线程上读回像素，
  是另一份改动、要在车上量。

照片是环视四路重排成的 2×2（`zeekr.CompositeBitmapComposer#toGrid`），不是前 / 后座舱摄像头。

## 3. 照片从哪来

`alarm.SnapshotTaker` 走的是拍照键那条路：`CameraKeeper#takePhoto` →
`MultiCameraManager#takePhoto` → 各路 `SingleCamera#takePicture` → 写盘成
`<时间戳>_surround.jpg`。不重开相机、不重启录像。

`PhotoCallback#onPhotoResult` 只回报存了几路、不给文件名，所以拍完是在照片目录里找**这一趟新写下的**
那张环视图（文件名带时间戳，后缀 `CameraSlots.suffixFor(KEY_SURROUND)`），找不到就退回只发文字。
照片写到 1280 px 宽、JPEG 80 再发；超过 9 MB 不发（Telegram `sendPhoto` 上限 10 MB）。

## 4. 发到哪

Telegram Bot API，`api.telegram.org`，不需要自建服务器、不需要公网地址，车用 4G 往外发就行
（车在运营商 NAT 后面，本来也接不进来）。

- 令牌：@BotFather 给的，`alarm.TelegramRequest#looksLikeToken` 只认「数字:一串字符」的外形。
- 会话号：`getUpdates` 里最近给机器人发过话的会话；设置有「找会话号」自动取，多个会话时让车主选。
- 发不出去时：黑匣子记一行，本地通知说一次（同一句 10 分钟内不重复），照片发不成就只发文字。

## 5. 令牌存在哪

**单独一个 SharedPreferences 文件**：`AppConfig.SECRETS_PREF_NAME`（`app_config_secrets`）。
不进 `app_config`，所以不进设置导出（`SettingsBackup.snapshot` 只读 `app_config`）、
不进诊断报告、不进黑匣子。设置页只显示末四位。

任何要写下来的字符串先过 `TelegramRequest#redact`：Bot API 把令牌放在路径里，
异常和日志最容易把它带出去。`AlertSecretsTest` 钉住两件事：导出里没有令牌；令牌不在 `app_config` 里。

## 6. 什么时候不发

- 设置在「停车警报」里关着，或者令牌 / 会话号缺一个：`AlertPolicy.Decision.OFF`，什么都不做。
- 车不在 P 挡：`NOT_PARKED`。
- 哨兵模式没开（`Signal.SENTRY_MODE` 是 0 或者读不到）：`NOT_ARMED`。
- 60 秒冷却里，或者这一小时已经 6 条：`COOLDOWN` / `HOURLY_CAP`。这些都在黑匣子里记一行，只是不发。

## 7. 人为的悬崖

- 环视那一路的 JPEG 出帧口在会话配置失败时是第一个被让出去的（`camera/ChannelRules.java`
  `onConfigFailed`）。那种状态下拍照会退回预览取帧，需要主界面在前台 —— 长时间熄屏的哨兵录像碰上它，
  照片可能拍不出来。拍不出来就只发文字，不假装发了照片。
- 令牌是明文存在应用私有目录里（没有引入加密库的做法，也没有假加密）。谁能读到这辆车的应用数据，
  谁就有这个机器人；机器人只能往你配的那个会话发话，也读不到你的聊天。
