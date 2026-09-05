# Changelog

Notable changes only, newest first. Each version's section becomes the body of its
[GitHub release](../../releases).

## [Unreleased]

- Fixed: the four camera labels on the main screen stayed Chinese in an English interface.
  They were written into the code instead of the string table, as were the names in the
  recording-camera setting, the fullscreen preview, the profile editor and the playback zoom.
- The recording resolution setting still pointed at a developer option removed in 0.37.9.

## [0.37.9-alpha] - 2026-09-05

- Removed the developer-only surround-view stream size. Every resolution now lives in the
  profile editor, and having a second place to set the same thing could only disagree.
- Fixed: a surround-view photo on "max" came out 7680x1080. Max picked the size with the
  most pixels, but on this camera every size holds the same four views, so 3840x2160 stores
  each square view squashed to 3840x540. Max now picks by how much detail a lane actually
  keeps, which is 1280x5140.
- The editor shows what "auto" and "max" resolve to, instead of leaving it to be guessed.
- Release descriptions are generated in English. The heading and the install line were
  still Chinese while everything around them had been translated.

## [0.37.8-alpha] - 2026-09-05

- Photos now carry EXIF again: capture time, make and model, size, the app version, and
  which camera took them. Stamping the badge means decoding and re-encoding the camera's
  JPEG, which discards whatever the camera wrote, so it is written back.
- The frame-rate options in the profile editor use the same wording as the setting: a plain
  number, without "up to".
- Clearing photo test samples also clears the folder used before 0.36.8, which the button
  could not reach.

## [0.37.7-alpha] - 2026-09-05

- Restored the changelog entries for 0.37.0 through 0.37.6. They were written
  against an anchor that had stopped matching, so seven releases went out with an
  empty description.

## [0.37.6-alpha] - 2026-09-05

- Fixed: cabin photos were rearranged into the four-up grid. The composer assumed every
  picture came from the surround-view camera; it is now told which camera took it.
- Fixed: cameras added to a profile had nowhere to appear. The screen layout and the camera
  limit were still chosen from the stored car model rather than the profile.
- Fixed: surround-view photos came out 1080x1080 whatever the settings said. The composer
  assumed the four lanes were square, which they are only at 1280x5140.
- The photo row in the editor says when the image channel is off, because the photo
  resolution has no effect in that case.

## [0.37.5-alpha] - 2026-09-05

- Splitting is now decided by the camera alone. The surround-view stream carries the same
  four-lane content at every resolution - confirmed at 1280x5140, 3840x2160 and 1600x900 -
  so resolution only changes sharpness, never the arrangement.
- Fixed: the profile editor reported "not split" for any size outside the old table while
  the preview split it anyway.

## [0.37.4-alpha] - 2026-09-05

- Fixed: recording and photo resolution had no effect, and changing the preview resolution
  moved all three. Only the preview stream was wired to the profile.
- The save check no longer opens a dialog just to say the check passed.
- Replaced the measured frame rate, which was always zero because the main screen is paused
  while the editor is open, with a real preview of the new configuration.
- The editor can add and remove cameras, and set frame rate, bitrate, segment length,
  rotation and mirroring.

## [0.37.3-alpha] - 2026-09-05

- Developer options -> Edit profile: cameras and their three streams, each stream showing
  its own parameters and whether it will be split into the four-up grid.
- Saving is checked first, then confirmed against a live preview with a countdown that
  expires. A configuration that leaves you without a picture cannot be cancelled by hand,
  so the default is to discard.
- Switching the stream configuration now switches profile.

## [0.37.2-alpha] - 2026-09-05

- Which camera setup runs is decided by the profile, not by the stored car model.
- Each camera's preview size comes from its profile entry.

## [0.37.1-alpha] - 2026-09-05

- Fixed: Current profile kept showing the first translation, so switching the stream
  configuration appeared to do nothing.
- The custom stream configuration is no longer labelled as the ZEEKR 7X preset.

## [0.37.0-alpha] - 2026-09-05

Groundwork for making camera and stream settings a profile you can edit and save.

- Camera and stream parameters now have a data model: which cameras are used, what each of
  the three streams (preview, recording, photo) is set to, and where each pane sits.
- Existing settings are translated into a default profile, viewable under developer options.
- Removed an ImageReader field that was declared and closed but never given a surface or
  attached to a session. Photos never went through it.

## [0.36.10-alpha] - 2026-09-04

- Developer options: added a preview resource sampler. It records per-camera frame counts
  and how many cameras are open once a second, tagged with which screen was on top, and
  reports it grouped by screen — so whether the preview stream keeps running in the
  background can be measured rather than timed by hand.

## [0.36.9-alpha] - 2026-09-04

- Photos can now be taken through the camera's own JPEG channel at each camera's largest
  size, instead of grabbing the preview. Off by default, under developer options, because
  it keeps an extra output stream open per camera.
- Fixed: the plate number was stamped but clipped off the badge, which is a fixed-width
  strip. The badge is now sized from the text.
- Fixed: settings dialogs with buttons (plate number, storage limits, recording cameras)
  showed no visible Save button. They are now built the same way as every other dialog in
  the app instead of relying on a theme attribute.
- The night-mode theme was a full rewrite of the day theme and had drifted; it now carries
  the same dialog and preference attributes.
- Photo test samples go next to the photos on the USB drive, and can be cleared from the
  test screen.
- Removed the send-to-phone connectivity test. The feature works on the vehicle; the
  diagnostics report still lists the local addresses.

## [0.36.8-alpha] - 2026-09-04

- Developer options: added a photo capture test. It takes a real JPEG from every camera at
  every declared size and reports what came back and which EXIF tags it carries.
- Removed the camera capability list and the resolution test. Every declared size runs at
  about 30 fps, so both tools have answered their question; the diagnostics report still
  lists what each camera declares.
- Removed the per-lane crop inset. It had a setter and nothing that ever called it.

## [0.36.7-alpha] - 2026-09-03

- The split table now lists camera and size together, and only the two combinations
  confirmed on the vehicle: the composite camera at 1280×5140 and at 3840×2160.
  Two sizes this head unit never declares were removed.
- Combinations outside the table are never split. Aspect ratio is used only to identify
  which camera is the composite one, never to decide how to split a frame.
- No frame is split until the composite camera has been identified.
- Fixed: the 2×2 grid size assumed square lanes. At 3840×2160 a lane is 3840×540,
  so the grid is now sized from the lane's real width and height.

## [0.36.6-alpha] - 2026-09-03

- Splitting the surround-view stream is now decided by one thing only: which camera the
  frame came from and what size it is. 3840×2160 is split into four equal lanes on the
  composite camera and left whole on the cabin cameras, which declare the same size.
- The lane order is the same at every size: front, rear, left, right.

## [0.36.5-alpha] - 2026-09-03

- Unlocking developer options now asks for the password on the first tap instead of
  after twenty.
- Every remaining dialog passes an explicit theme, so none of them can end up with
  invisible buttons — the settings dialogs were fixed in 0.36.4, this covers the rest.

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
