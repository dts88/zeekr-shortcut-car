# Zeekr Shortcut (Car Version)

An app for the ZEEKR 7X head unit that puts the car's own surround cameras to work: it records them as a
dash cam and shows them where they help while driving.

**[Download](../../releases/latest)** · [Features (wiki)](../../wiki) · [Discussions](../../discussions) ·
[中文说明](README.zh-CN.md) · [Credits](NOTICE.md)

---

## What it is

Through App Lab, a third-party app gets the 7X's four surround cameras as one pre-stitched stream, which on
its own previews as a distorted strip. Zeekr Shortcut splits it back into four views and builds on that:

- **Dash cam** — records the surround view, and the cabin cameras if you turn them on, to a USB drive.
- **Super mirror** — a floating electronic rear-view mirror showing any one camera.
- **Blind spot view** — the side camera on the center screen or the HUD while the turn signal is on.
- **Playback** — every camera together, in sync; send a photo or clip to your phone.

Every feature, setting and tip is described in the **[wiki](../../wiki)**.

## Alerts while parked

With the car parked and Sentry Mode on, the app can send you a picture when something happens to it.
Off by default; set it up once, from the car screen, and nothing needs rebuilding.

- One picture of all four cameras, taken by the same photo path the shutter button uses, so it looks
  like a photo you would have taken yourself.
- It is published to a private ntfy topic when: something moves in view, the hazards come on, the high
  beams flash, a door / frunk / trunk opens, the car lights come on by themselves, or the centre screen
  lights up while parked. Pick which of those count under **Settings → Alerts → What counts**.
- **Settings → Alerts → Set up ntfy** runs the wizard: it generates the topic, shows it as a QR code
  for the phone's ntfy app to scan, and asks for four digits it sends to that topic — the digits coming
  back is the proof the phone really receives.
- One alert per minute at most, six an hour. The cooldown is there because one event trips several
  signals at once.
- Coming back to the car does not send anything: unlocking drops Sentry Mode out of its armed value
  before the screen or the lamps come back on, and the alert needs the armed value.
- The topic name stays on this car, in its own store: it is not in the exported settings, not in a
  diagnostics report, and not in the log. The name is the address, so treat it like a password.

This car has no impact, horn, airbag or tamper signal on the bus that this app can read, so a knock
that never lights the hazards and never opens a door will not be seen. The picture is the four
surround cameras stitched into the 2×2 grid, not the cabin cameras.

## Sentry alerts

With Sentry Mode on, the car already keeps recording. Turn this on and it also tells you: one picture
of the four surround cameras is published to your ntfy topic when something happens to the car while
you are away.

Off by default. Settings → **Alerts**.

### Setting up ntfy, step by step

The guided wizard's every screen, with one picture per step, is in
**[docs/setup-wizards.md](docs/setup-wizards.md)**. The list below is the same path written out.

1. **Accept the honest warning.** A free topic is public: the name is the address, so anyone holding
   it can read the pictures and publish to it. Setup says so first and refuses to send until that is
   accepted.
2. **Scan the QR code.** The car generates the topic and shows it as a QR code; the phone's ntfy app
   scans it and subscribes.
3. **Prove the path.** The car sends four digits to that topic; type them back into the wizard. The
   digits coming back is the proof the phone really receives — if they arrive, everything works:
   the car, its 4G, the topic, the phone.
4. **Choose what counts** under **What counts as something**: movement in view, hazards, high beams, a
   door, the car lights, the centre screen. By default only the three the car itself reacts with are on.
5. **Park, lock, walk away.** Unlock the car and nothing is sent — unlocking drops Sentry Mode out of
   its armed value before the lights come back on.

Optional, in the same section:

- **A picture, or just the line**: on by default. Turn it off and each alert publishes the sentence
  alone and the car never takes the picture.
- **Pictures**: one 2×2 of all four cameras, or one picture per camera so each fills the phone screen.
- **Straighten**: the same fisheye correction playback uses.
- **The picture is small.** Each alert is one picture compressed on the car to under 100 kB: enough to
  see what is happening, small enough to ignore. Data per alert, by default: 100 kB.
- **Start after locking**: right away, 1 minute or 3 minutes after the car locks, so walking away
  does not send you a picture of yourself.
- **Quiet hours**: no alerts between two times, for the nights the car is at home.
- **Change the topic** with one row. It mints a new name (238 random bits, mixed with the install time,
  the current time, the rotation count and the model so two cars never collide), kills the old one, asks
  for the risk warning again, and shows the QR code so the phone can subscribe. Do this if someone saw
  your lock screen.
- **Paid tier.** A paid ntfy plan allows protected topics; the paid switch tells the car the topic is
  protected and lifts the free tier's message size cap.

**What it cannot see.** The car reports no impact, horn or tamper signal, so a knock that lights nothing
and opens nothing stays invisible. Cameras also need Sentry Mode to keep the head unit awake.

---

## Download
---

## Download

Get the APK from **[Releases](../../releases/latest)**. Once installed, the app can check for newer
versions itself: Settings → Updates & about → Check for updates.

- The first install goes through App Lab (sideloading): see [First install](../../wiki/First-install) in the wiki,
  or brotatochip's [video guide](https://youtu.be/m2o2HcqdsOY).
- When the installer finishes, tap **Back** at the top left, not **Open**. Otherwise the head unit's
  installer gets stuck and later installs or updates fail.

Building from source: see the [wiki](../../wiki/Build-from-source).

Questions and ideas: [Discussions](../../discussions). Problems: [open an issue](../../issues). Please don't post
the diagnostics report in public; send it to dts88 by private message in the owners' group chat.

---

## Recommended

**[Zeekr 7X Shortcut Dashcam App Installation & Usage Guide](https://youtu.be/m2o2HcqdsOY)** is brotatochip's complete, detailed getting-started video, from how to sideload to how to use the app. Highly recommended if you're new to sideloading or to Zeekr Shortcut.

**[Zeekr Companion](https://github.com/wysie/zeekr-shortcut-car)** is wysie's version of Zeekr Shortcut, optimised for the ZEEKR 009 head unit. Wysie has also shared a great deal of basic information about Zeekr cars, and it's a pleasure to explore what the Zeekr head unit can do together.

**[CAMCORDER](https://github.com/FerixzTanz/CAMCORDER)** is FerixzTanz's version of Zeekr Shortcut, with a series of features ahead of Zeekr Shortcut. This project's blind spot view started from FerixzTanz's code and on-car research, and showing it on the HUD comes from CAMCORDER: the approach, the HUD settings and his careful on-car calibration. Thanks to FerixzTanz for the support and contributions to this project.

**[CarPiano](https://github.com/jlunnn/CarPiano)** plays sound through the car's exterior speaker at any speed — no longer limited to Park like the factory feature. It's still early in development, but very promising.

Check your local rules before using the exterior speaker on public roads.

---

> [!WARNING]
> Experimental, unofficial software. Not affiliated with, approved by, or endorsed by ZEEKR,
> and not certified for any vehicle safety function. It does **not** replace the factory dash cam,
> reversing camera, or blind-spot monitor. Do not operate it while driving.
> Full safety notice: [安全须知](README.zh-CN.md#安全须知).

---

## Credits and license

**GPL-3.0**, inherited from [EVCam](https://github.com/suyunkai/EVCam) by suyunkai — the code base
this app is forked from. The first commit in this repository is EVCam's complete working tree, so
every change since then is visible as a diff.

Splitting App Lab's composite stream into a grid and rendering it is implemented here from scratch.

The simulated engine sound (its synthesis model, the voices and their tuning) is dts88's original work. CarPiano and
EngineV1 only supplied facts (see [NOTICE.md](NOTICE.md)).

### Contributors

- **[wysie](https://github.com/wysie)** — a great deal of design and code.
- **[FerixzTanz](https://github.com/FerixzTanz)** — the original code and on-car research for Blind spot view, and
  showing it on the HUD (from his CAMCORDER). Its default angles and window size, and the HUD picture defaults, are the
  ones he tuned on his own 7X. Many thanks for his careful parameter tuning.
- **[jlunnn](https://github.com/jlunnn)** — the Rear fog lamp flash action for smart buttons (from his 7X-U) and a
  fix that lets the unit tests pass on Windows. Also the author of CarPiano.
- **[calvinbui](https://github.com/calvinbui)** — on-car findings from his Australian 7X: climate, driving and safety
  settings.
- **[lelelolo](https://github.com/lelelolo)** — two Blind spot view improvements: no black window while the car is
  using the camera, and an edge in the mirror light's colour while that light is on. Many thanks.
- **[Fabbbrrr](https://github.com/Fabbbrrr)** — the innovative idea of decentralised ntfy and Telegram alerts that go
  straight to the owner's phone and never through a developer's server, and the design and logic of the ntfy alerts
  ([#211](https://github.com/dts88/zeekr-shortcut-car/pull/211)). Many thanks.
- **[brotatochip](https://www.youtube.com/@equinoxau)** — the video
  [Zeekr 7X Shortcut Dashcam App Installation & Usage Guide](https://youtu.be/m2o2HcqdsOY): a detailed walkthrough of
  sideloading as it works today, and an introduction to Zeekr Shortcut. Many thanks.
- **SW** — active testing and feedback. Many thanks.

Origins, third-party licences and boundaries: [NOTICE.md](NOTICE.md).

### Using this project's code

Zeekr Shortcut is GPL-3.0, with two additional terms under GPL-3.0 section 7 ([NOTICE.md](NOTICE.md),
section 8). If your project uses its code, modified or not, you must:

1. **Credit it in your app's About or credits screen**, visible to users:
   `Based on Zeekr Shortcut by dts88 (https://github.com/dts88/zeekr-shortcut-car)`,
   or `Zeekr Shortcut © 2026 dts88` for an unmodified copy.
2. **Keep the notices**: the copyright headers in the source files and the GPL-3.0 licence. Release your
   source under GPL-3.0 when you distribute it.
3. **Mark it as modified**: a modified version must not be presented as the original or as coming from
   dts88. Give it its own name, or label it as modified.

Please also put the same credit near the top of your README, and name the version or commit you started
from. Code from EVCam or a contributor keeps its own credits too (see [NOTICE.md](NOTICE.md)).

Findings first published here can't be licensed. Crediting them the same way is a request — the same
courtesy this project gives the sources it learned from.
