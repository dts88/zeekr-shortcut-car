# ZEEKR 7X: car functions a head unit app can trigger

[中文](#中文) · From [Zeekr Shortcut](https://github.com/dts88/zeekr-shortcut-car) (dts88), as of 3.5.1

These are the native car functions that Zeekr Shortcut lets a smart button (灵动贴) trigger on the ZEEKR 7X head
unit. They work just as well from a floating button or any other shortcut. Facts only, no code: free to use; a credit
is appreciated. Unofficial and untested by ZEEKR: use at your own risk.

## How Zeekr Shortcut does it

- Talks to the car through the head unit's vehicle interface (ECARX), which apps inside App Lab can reach. Create the
  car object with a lifecycle listener: without one, the system kills the process.
- Looks up every function by the platform's constant name at run time. No hard-coded numbers.
- Reads the current state first, then writes the next value from the rules below.
- Confirms by reading back every 100 ms for up to 1 s. No change: tells the user the car may not have this function.
  Functions this car lacks stay in the list, because cars for other markets have them.
- Checks before acting:
  - **Super Power Saving mode:** no seats, steering wheel, volume or One-touch Sport.
  - **Seat restore, side mirrors, charging port cover:** only when the car is stationary (speed known, 1 km/h or
    less).
  - **Fragrance:** only when a cartridge is installed.
- Doesn't check the gear and doesn't undo anything afterwards.

## Functions

| Group | Function | Each press |
|---|---|---|
| Driving | Comfort / Standard / Sport mode | Switches to that mode |
| | Drive mode up / down | One step through Comfort, Standard, Sport; stops at the ends. In any other mode it says the mode didn't change |
| | One-touch Sport | Not in Sport: remembers the mode and switches to Sport. In Sport: back to the remembered mode (Standard if none) |
| Voice and media | Voice assistant | Opens the system's voice entry |
| | Play / pause, previous track, next track | Media keys |
| | Mute / unmute, volume up, volume down | Media volume; the system volume bar shows |
| Seats and steering wheel | Steering wheel heating; driver / passenger seat ventilation; driver / passenger / rear left / rear right seat heating; rear seat heating (both sides) | Off → 3 → 2 → 1 → off |
| | Driver / passenger seat massage | Off: on at level 3. Then one level down per press; at level 1, off |
| | Seat restore | Restores the seat positions (the seats move) |
| Climate | Climate on / off; front defrost; rear defrost | On / off |
| | Air circulation | Auto → fresh air → recirculation → auto |
| Body | Central locking | Locks / unlocks |
| | Fold / unfold side mirrors | Folds / unfolds. This function's read and write values look inverted, so it writes the value it reads, and a changed read-back counts as done |
| | Charging port cover | Opens / closes |
| | Factory 360 view | Shows / hides |
| Lights and fragrance | Welcome / charging / driving light show | On / off |
| | Fragrance | Off: on at the lightest level. Then one level up per press; after the strongest, off |
| Scenes | Theater mode (driver), theater mode (passenger), camping mode, Sentry Mode | On / off |

## Tried on our 7X (Singapore, 2026-11-04)

- **Works:** media keys, mute, driver seat ventilation, driver seat massage, climate on / off, central locking, factory
  360 view, driving light show, theater mode (passenger), Sentry Mode.
  - Seat ventilation: the read-back lags one step; at level 1 the car switches it off by itself after about 3 s.
  - Theater mode (passenger): the car's own scene also turned on maximum cooling, fragrance and seat ventilation.
- **This car doesn't have it:** seat heating, fragrance.
- **Not tried yet:** everything else, including the side mirrors, charging port cover, seat restore and camping mode.

## Where to look

Zeekr Shortcut's source (GPL-3.0): the actions and their rules are in
[`button/CarMoves.java`](https://github.com/dts88/zeekr-shortcut-car/blob/main/app/src/main/java/com/kooo/evcam/button/CarMoves.java),
the lookup by constant name in
[`telemetry/CarNames.java`](https://github.com/dts88/zeekr-shortcut-car/blob/main/app/src/main/java/com/kooo/evcam/telemetry/CarNames.java),
reading and writing in
[`telemetry/CarAccess.java`](https://github.com/dts88/zeekr-shortcut-car/blob/main/app/src/main/java/com/kooo/evcam/telemetry/CarAccess.java).

## Credits

Collected by Zeekr Shortcut. LEO (Bilibili Zeekr 7X owners' circle) provided important code data used by
Chinese-market head units. Tried on a 7X by the Zeekr Shortcut lab.

---

## 中文

来自[极氪即刻（Zeekr Shortcut）](https://github.com/dts88/zeekr-shortcut-car)（dts88），截至 3.5.1。

下面是极氪即刻的灵动贴在极氪 7X 车机上能触发的原车功能，放在悬浮按钮或别的快捷入口上也一样能用。只有事实，没有代码：
可以随意使用，注明出处更好。非官方，极氪没有测试过，使用风险自负。

### 极氪即刻是怎么做的

- 通过车机的车辆接口（ECARX）和车通讯，App Lab 里的应用能用。创建车辆对象时要带生命周期监听，不带的话进程会被系统结束。
- 每个功能都按系统里的常量名在运行时取号，不写死数字。
- 先读现在的状态，再照下面的规则写下一个值。
- 写完每 0.1 秒读回一次，1 秒内变了算成功；没变就提示这台车可能没有这个功能。这台车没有的功能也留着，别的地区的车有。
- 执行前的检查：
  - **超级省电模式**：座椅、方向盘、音量、一键运动不执行。
  - **座椅恢复、后视镜、充电口盖**：车要停稳才执行（读得到车速、不超过 1 km/h）。
  - **香氛**：装了香氛才执行。
- 不看挡位，执行后也不恢复。

### 功能

| 分组 | 功能 | 每按一下 |
|---|---|---|
| 驾驶 | 切换到舒适 / 标准 / 运动模式 | 切到这个模式 |
| | 驾驶模式向上 / 向下 | 舒适、标准、运动之间走一级，到头不循环；不在这三种模式时提示没有切换 |
| | 一键运动 | 不在运动模式：记下现在的模式，切到运动；在运动模式：回到记下的（没记过回标准） |
| 语音与媒体 | 语音助手 | 打开系统的语音入口 |
| | 播放 / 暂停、上一首、下一首 | 媒体键 |
| | 静音 / 取消静音、音量加、音量减 | 媒体音量，显示系统的音量条 |
| 座椅与方向盘 | 方向盘加热；主驾 / 副驾座椅通风；主驾 / 副驾 / 二排左侧 / 二排右侧座椅加热；二排座椅加热（两侧一起） | 关 → 3 档 → 2 档 → 1 档 → 关 |
| | 主驾 / 副驾座椅按摩 | 关着时打开、调到 3 档，之后每按一下降一档，1 档时关掉 |
| | 座椅恢复 | 座椅会动 |
| 空调 | 空调开关；前除霜；后除霜 | 开 / 关 |
| | 循环模式 | 自动 → 外循环 → 内循环 → 自动 |
| 车身 | 中控锁 | 锁、开互换 |
| | 后视镜折叠 / 展开 | 折叠 / 展开。这个功能读、写的值像是反着的，所以照读到的值写，读回变了就算成功 |
| | 充电口盖 | 开 / 关 |
| | 原厂 360 画面 | 显示 / 收起 |
| 灯光与香氛 | 迎宾灯语、充电灯语、行车灯语 | 开 / 关 |
| | 香氛 | 关着时打开、调到最淡的一档，之后每按一下浓一档，最浓之后关掉 |
| 场景 | 观影模式（主驾）、观影模式（副驾）、露营模式、哨兵模式 | 开 / 关 |

### 在我们的 7X 上试过的（新加坡，2026-11-04）

- **能用**：媒体键、静音、主驾座椅通风、主驾座椅按摩、空调开关、中控锁、原厂 360 画面、行车灯语、观影模式（副驾）、哨兵模式。
  - 座椅通风：读回慢一拍；到 1 档约 3 秒后车自己关掉。
  - 观影模式（副驾）：车自己的场景同时开了最大制冷、香氛和座椅通风。
- **这台车没有**：座椅加热、香氛。
- **还没试**：其余的，包括后视镜、充电口盖、座椅恢复、露营模式。

### 去哪看

极氪即刻的源码（GPL-3.0）：动作和规则在 `button/CarMoves.java`，按常量名取号在 `telemetry/CarNames.java`，
读写在 `telemetry/CarAccess.java`（链接见上面英文部分）。

### 致谢

由极氪即刻整理。LEO（Bilibili 极氪 7X 车友圈）提供了国内车机使用的重要代码数据。
由极氪即刻的实验项目在 7X 上实测。
