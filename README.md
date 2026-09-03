# Zeekr Shortcut (Car Version)

A surround-view dash cam for the ZEEKR 7X head unit.

[中文说明](README.zh-CN.md) · [Changelog](CHANGELOG.md) · [Credits](NOTICE.md) · [Platform notes](docs/zeekr-platform-notes.md)

> [!WARNING]
> Experimental, unofficial software. Not affiliated with, approved by, or endorsed by ZEEKR,
> and not certified for any vehicle safety function. It does **not** replace the factory dash cam,
> reversing camera, or blind-spot monitor. Do not operate it while driving.
> Full safety notice: [安全须知](README.zh-CN.md#安全须知).

---

## What it does

ZEEKR's App Lab exposes the four surround-view cameras as **one pre-stitched stream** — four
1280×1280 squares packed into a single frame, which previews as a distorted vertical strip.
This app splits that stream back into a proper 2×2 grid, and builds three things on top of it.

### 1. Surround-view dash cam

Records all four views at once. H.264 / H.265, 1–10 minute segments with the oldest files cleaned
up automatically when the disk fills, adjustable frame rate, bitrate and layout (2×2 grid or the
raw strip). Starts on boot, keeps recording with the screen off and after the car is locked.

Recording goes to a **USB drive only**. Writing to the head unit's built-in flash sits behind
developer options, because continuous writes wear out storage that cannot be replaced.

### 2. Electronic rear-view mirror

A floating, dockable window showing any one camera enlarged. Pinch to zoom; swipe left or right to
rotate through the cameras; swipe up or down in the middle third to raise or lower the framing.
Fisheye correction and field of view are adjustable, and the window resizes to any shape without
distorting the picture. Push it half off-screen and it hides at the edge — one tap brings it back.
The rear view is mirrored horizontally, like a real mirror.

### 3. Send to your phone

While viewing a photo or a video segment, tap **send to phone**: the app shows a QR code, your
phone's browser opens that file, and you save it. Nothing to install on the phone.

The file is served over your local network only while the dialog is open, from a random one-off
address, and only that one file. Both devices need to be on the same network — the easiest way is
to turn on the phone's hotspot and connect the car to it.

---

## Install

Download `ZeekrShortcut-*.apk` from [Releases](../../releases) and sideload it through App Lab.

Then open **Settings → Recording → Stream configuration**, pick *ZEEKR 7X (surround-view
composite)*, and restart the app when prompted. If it reports that no composite stream was found,
this head unit or firmware version does not declare one and the app will not work on it.

Building it yourself needs JDK 17+ and the Android SDK (compileSdk 36):

```bash
git clone https://github.com/dts88/zeekr-shortcut-car.git
cd zeekr-shortcut-car && ./gradlew assembleRelease
```

The repo ships a public AOSP test signing key (password `android`). Override it with your own
through `ZEEKR_KEYSTORE`, `ZEEKR_KEYSTORE_PASSWORD`, `ZEEKR_KEY_ALIAS` and `ZEEKR_KEY_PASSWORD`.

---

## Limitations

- Built for the ZEEKR 7X composite stream. Other models or firmware with a different layout fall
  back to an even four-way split, and the picture may be offset.
- Factory features come first: the built-in 360° view, reversing camera and parking cameras can
  reclaim the camera at any time, and this app gets out of the way.
- Roughly 200 MB per minute of continuous writing — which is why it records to USB.
- On-vehicle validation is ongoing. Automated tests cover the pure logic only (geometry, camera
  selection, gesture model); test in a stationary vehicle first.

---

## Credits and license

**GPL-3.0**, inherited from [EVCam](https://github.com/suyunkai/EVCam) by suyunkai — the code base
this app is forked from. The first commit in this repository is EVCam's complete working tree, so
every change since then is visible as a diff.

That App Lab exposes only one composite stream, along with its dimensions and layout, was first
documented publicly by [openavm-recorder](https://github.com/Dantenothing/openavm-recorder).
**No code from that project was copied** — only its published interface facts were used; the grid
splitting and rendering here are an independent implementation.

Full attribution, third-party licenses and boundaries: [NOTICE.md](NOTICE.md).
