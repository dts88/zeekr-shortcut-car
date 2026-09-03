# Changelog

Notable changes only, newest first. Each version's section becomes the body of its
[GitHub release](../../releases).

## [Unreleased]

Nothing yet.

## [0.36.4-alpha] - 2026-09-03

- A forced surround-view stream size is now split into the 2×2 grid like the native one.
  3840×2160 carries the same four lanes as 1280×5140, but 16:9 never passed the strip test.
- Fixed: settings dialogs (plate number, dropdowns) had invisible OK and Cancel buttons.
- Removed the "stream probe result" row from Settings; Developer options → Camera
  capabilities covers the same ground properly.
- Camera detection still goes by aspect ratio alone, so a declared size cannot make a
  cabin camera look like the composite one.

## [0.36.2-alpha] - 2026-09-03

- Added an optional plate number, stamped into recordings and photos next to the app name.
  Up to 10 characters, uppercase letters and digits.
- Photo watermarks now carry the same information as video: app name, version, plate,
  date and time, and size.
- Fixed: the bitrate summary disappeared after changing the setting.
- The target bitrate is now computed from the size actually being encoded.
- Frame rate: the native option shows the rate the stream declares.
- Recording resolution is greyed out in the composite configuration, where it has no effect.

## [0.36.1-beta] - 2026-09-03

- Fixed: "native frame rate" passed 0 to the encoder and the camera, forcing the lowest
  bitrate and the slowest exposure range.
- The hardware frame-rate ceiling now comes from the camera instead of a hard-coded 25.
- The video watermark drops the target bitrate and keeps the measured one.
- Fixed: the surround-view stream size shown on screen was the probe result, not the size
  actually in use.
- Download and share failures are now visible instead of a toast that flashes past.

## [0.36.0-beta] - 2026-09-03

- "Native frame rate" now means no limit at all; the other options are a ceiling, not a target.
- Fixed: video playback was hidden behind the status bar.
- Developer options: the surround-view stream size can be overridden.

## [0.35.0-alpha] - 2026-09-03

- Fixed the real cause of low frame rates: our own throttle halved them whenever the camera's
  frame interval was not a multiple of the target.

## [0.34.0-alpha] - 2026-09-03

- Fixed: the resolution test counted no frames.
- The resolution test now measures frame rate as well.

## [0.33.0-alpha] - 2026-09-03

- Fixed: the measured frame rate was never actually computed.
- Fixed: the rate shown for the native option was a constant unrelated to the camera.
- Developer options: added a resolution test that opens each declared size for real.

## [0.32.1-alpha] - 2026-09-03

- Camera capabilities now measure the rate the camera delivers, with a glossary for each field.

## [0.32.0-alpha] - 2026-09-03

- Developer options: added a camera capability list.

## [0.31.3-alpha] - 2026-09-03

- Fixed: the share test screen had no way out.
- Fixed: the watermark showed the configured frame rate, not the recorded one.
- The share dialog explains the same-network requirement and which segment is sent.

## [0.31.2-alpha] - 2026-09-02

- Local network addresses and port availability are included in the diagnostics report.

## [0.31.1-alpha] - 2026-09-02

- Send to phone is available from photo and video playback.

## [0.31.0-alpha] - 2026-09-02

- Groundwork for send to phone, with a connectivity test screen in developer options.

## [0.30.0-beta] - 2026-09-02

- Update checks consider beta and stable releases only.
- Fixed: the rear-view mirror window size was never saved after a pinch.

## [0.29.1-alpha] - 2026-09-02

- Fixed: the mirror stayed blurry after the window was resized.

## [0.29.0-alpha] - 2026-09-02

- Fixed: "do not record without a USB drive" only covered one of nine entry points.
- English UI for everything outside Settings.

## [0.28.0-alpha] - 2026-09-02

- The first-launch guide now describes this app, in both languages.

## [0.27.0-alpha] - 2026-09-02

- English UI for Settings, with a language option (system / Chinese / English).

## [0.26.1-alpha] - 2026-09-02

- Check for updates moved to the top level of Settings.

## [0.26.0-alpha] - 2026-09-02

- Check for updates now reads this repository's releases, downloads the APK and opens the
  installer directly.

## [0.25.2-alpha] - 2026-09-02

- Restored the custom layout and preview-correction entry points lost in the settings rebuild.

## [0.25.1-alpha] - 2026-09-02

- Fixed: developer options did nothing when tapped, and appeared only after leaving Settings.

## [0.25.0-alpha] - 2026-09-02

- Fixed: going back from a settings sub-screen jumped all the way to the recording screen.

## [0.24.0-alpha] - 2026-09-02

- Fixed: cameras could fail to open at all in custom mode.

## [0.23.0-alpha] - 2026-09-02

- Fixed: the rear-view mirror switch could turn on without the window appearing.

## [0.22.0-alpha] - 2026-09-02

- Camera setup consolidated into a single path. No behaviour change.

## [0.21.1-alpha] - 2026-09-01

- Recording decisions moved out of the main screen. No behaviour change.

## [0.21.0-alpha] - 2026-09-01

- Settings rebuilt as two panes: sections on the left, content on the right.

## [0.20.0-alpha] - 2026-09-01

- Recording to internal storage is now developer-only; a USB drive is required otherwise.

## [0.19.2-alpha] - 2026-09-01

- Fixed: the rear-view mirror did not reappear after restarting the app.

## [0.19.1-alpha] - 2026-08-30

- Fixed: the low / medium / high bitrate setting never took effect.

## [0.19.0-alpha] - 2026-08-30

- Fixed: field of view did nothing while fisheye correction was off.
- Video watermark: live bitrate, plus the app name and version in the top-left corner.

## Earlier versions

0.1.0 through 0.18.0 built the app up from the EVCam fork: splitting the ZEEKR composite
stream into a 2×2 grid, the rear-view mirror window, recording and playback, storage handling,
and the move to a preference-based settings screen. Those releases are no longer published.
