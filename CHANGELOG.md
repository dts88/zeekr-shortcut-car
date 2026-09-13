# Changelog

Notable changes only, newest first. Each version's section becomes the body of its
[GitHub release](../../releases).

## [Unreleased]

Nothing yet.

## [0.45.0-alpha] - 2026-09-13

- One floating button instead of two. It shows whether recording is running -- red while
  it records, a hollow ring when idle -- and what a tap and a long press do is yours to
  set: open the app, start/stop recording, take a photo, or toggle the mirror. Both
  default to opening the app, because brushing a button should never stop a dashcam
  recording. Drag, size, opacity and the recording time beside it all stay; the time is a
  switch now. Anyone who had only the old open-the-app button keeps a button.

## [0.44.1-alpha] - 2026-09-13

- Fixed: rotating a lane made the neighbouring lanes appear beside it. A lane keeps its
  own shape inside its cell, so there is empty space at the sides -- and that space holds
  the pixels of the lanes above and below it on the same strip. Upright, the strip runs
  vertically and the side margins happen to be empty; rotate it a quarter turn and the
  strip runs across, filling them. Clipping to the cell never helped: the leak was already
  inside the cell. Each lane is now clipped to its own window in the strip, which no
  rotation can widen, and the margin is painted.
- Crop no longer moves the picture. The cell used to be re-fitted to whatever was left
  after cropping, so trimming the top made the whole lane shrink and shift -- which reads
  as crop being broken rather than as a trimmed bumper. The frame now stays where it was
  and the remainder fills it, the excess centre-cropped rather than stretched, the way the
  rear-view mirror fills its window. Trimming top and bottom therefore also trims a little
  from the sides.
- Fixed: rotation still did nothing to the cabin cameras. An inherited rule mirrors the
  "back" slot unconditionally and writes the matrix straight onto the view, which erased
  the one built from the profile -- and in the three-stream profile that slot is the first
  cabin camera. That rule now applies only where the profile does not drive the lane. The
  transform is also recomputed when the view is re-laid out, which it is right after the
  aspect ratio is set.
- The status bar sits on the picture again, along its bottom edge, on a soft dark gradient
  -- on the main screen and in video review, the two screens whose content is the picture.
  As its own row it cost them 48dp of height, and in video review it had pushed the
  transport controls down. Settings keeps it as a row: there it sits below text, not on a
  picture.
- Diagnostics reports what the encoders actually allow: the sizes and bitrate range each
  one declares, whether it will take the surround grid's size, and the profile/levels it
  advertises. The ceiling in the code was inherited, never checked against the hardware,
  and an encoder that disagrees does not complain -- it quietly lowers the quality.
- Removed the inherited fisheye correction: a second camera pipeline, about 1800 lines,
  behind a flag that nothing could ever set. It rendered the camera into an intermediate
  GL surface, which is the very thing that crashes on this head unit -- which is why the
  rear-view mirror's own correction was written the way it was, as a mesh over the existing
  view. That one stays and is the basis for anything we do with distortion later. The
  fullscreen preview keeps its zoom, centre and rotation sliders, which work; the two that
  fed the deleted shader are gone.

## [0.44.0-alpha] - 2026-09-13

- Bitrate has four tiers instead of three, and the whole table moved up: very low 2.7,
  low 5.4, medium 10, high 20 Mbps on the surround grid. Medium is the default and is
  double what it was. The old ceiling was 8 Mbps for a 6.6-megapixel frame -- 0.033 bits
  per pixel, about a seventh of what an ordinary dashcam spends on 1080p. The detail was
  being recorded and then compressed away, which is why the picture looked soft rather
  than small.
- One bitrate formula. There were two: the encoder's, and one computed next to it whose
  result was thrown away unless "force H.264" was on -- so that compatibility switch was
  quietly the sharpest setting in the app.
- Recording no longer declares HEVC Level 4, which tops out at 1920x1080. The surround
  grid is three times that, and an encoder that believes the declaration may hold its own
  rate control down to match.
- Fixed: re-preparing after a forced camera reopen built the recorder from the preview
  size with no four-up rearrangement and the frame rate hard-coded to 25. One reopen and
  the rest of the session recorded a preview-sized strip, with nothing in the log to say
  so. Both paths build the recorder the same way now.
- Fixed: crop cut the wrong edge on a rotated lane. Crop, zoom and pan were read in the
  source picture's axes while the user sets them looking at the rotated one, so after a
  quarter turn "crop 20% off the top" took it off the left -- which on the vehicle is
  indistinguishable from crop not working at all. The mapping is its own tested function
  now, shared by both drawing paths.
- Fixed: rotation and mirroring had no effect on the two cabin cameras. Their transform
  came from the old per-camera preview correction and nothing read their lane in the
  profile, so the editor showed 90 degrees while the picture never moved. Rotation,
  mirroring, crop, zoom and pan now all apply; position and size still wait for the main
  screen layout to come from the profile, and the editor says so.
- The confirm-before-save preview applies the same transform, so what it shows is what the
  main screen will show.

## [0.43.2-alpha] - 2026-09-13

- The last four places that set a text size in code now read the scale: the profile preview check's note, the update dialog's progress line, the lane map's labels and the phone-share page. Sizes still set in code are either burned into a frame or chosen by the user.
- Removed an unused layout left over from upstream, and added a test that fails on a plain Button or an android:backgroundTint in a core screen -- the shape of what sat unnoticed in the large-screen layout for four months.

## [0.43.1-alpha] - 2026-09-12

- Photo and video review keep their actions in the title bar after all. The action rail 0.43.0 moved them to costs 380dp on a screen whose content is the picture, and the title bar had the room. Both screens still share one layout for them.
- The first-launch guide was set in the page-title size, which read oversized and cramped; it is body text with proportional line spacing now. Its content is current too: the diagnostics report comes from the drawer, and the button side and the mirror's gestures each get a line.

## [0.43.0-alpha] - 2026-09-12

- Settings has a title bar again: the menu key sits where every other screen keeps it, and turns into back inside a sub-screen, whose name it shows. The status bar from the main screen runs along the bottom.
- Photo and video review moved their actions -- refresh, multi-select, home -- into an action rail on the right, and the selection actions with them. Both screens include the same rail, so they cannot drift apart. Transport controls stay under the video, where the picture they act on is.
- Video review shows the status bar too, so recording state and free space are visible while watching.
- The status bar itself is now one layout instead of a copy per main layout.
- The UI spec lives in the repo (docs/ui-spec.md): the five zones, which screens use which, every token value, and a table saying plainly what does not follow it yet.

## [0.42.1-alpha] - 2026-09-12

- Fixed: photo review's list was a sliver. The layout that survived yesterday's cleanup sized that list for a phone (200dp fixed); it now uses the same width as the settings and video-review lists.
- Photo review's text steps line up with the rest: lane badges match the ones on the main screen, and the empty-state hint is no longer larger than a page title with its own subtitle bigger still.

## [0.42.0-alpha] - 2026-09-12

- Fixed: photo review was still showing its pre-redesign toolbar on the vehicle. The head unit picks the large-screen copy of that layout, and only the base copy had been redesigned — so the vector icons and rounded tiles landed in 0.38.0 for everyone except the car. Those stale copies are gone; there is one layout now.
- Fixed: the stale copy also carried hardcoded Chinese, which the test missed because it only scanned the base layout folder. It scans every variant now.
- About rewritten: thanks first, then what the app is built on, where the source is, what happens to your recordings and the three times the app goes online, storage advice, and the safety notice.

## [0.41.1-alpha] - 2026-09-12

- Settings text is back to its previous size. The section is a dense list read while parked, so it sits one step below the rest of the scale; every other screen keeps 0.41.0's sizes.
- Fixed: video review, About and Diagnostics ignored the language setting and stayed in the system language. The app's language reaches AppCompat activities only, and those three were plain ones. About and Diagnostics also still carried the system theme, so their colours now match as well.

## [0.41.0-alpha] - 2026-09-12

- Text and icons are a size up: the scale moves 12/13/15/18/22/24 -> 14/16/18/21/26/28 and icons follow, after comparing against the head unit's own settings screens.
- Switches: white thumb, orange track when on. Material's own switch tints the thumb and washes the track, which is where the pale orange came from.
- Fixed: dialogs looked reddish. Material 3 tints elevated surfaces with the primary colour, and ours is Zeekr orange.
- Fixed: switching day/night dropped you back to the main screen and made the playback screens rescan. The screen you had open comes back, and clip durations are remembered, so there is nothing to rescan.
- Video review has the same controls as photo review: the menu button where the main screen keeps it, plus refresh, multi-select and home. Multi-select deletes several recordings at once.
- Developer options: the photo-channel test and the preview resource sampler are gone. Each was built to answer one question about a feature that has since shipped, and both questions are answered.

## [0.40.0-alpha] - 2026-09-12

- Text sizes come from one named scale of six steps. Core screens had eight sizes, three pairs of them one sp apart. Page titles are now larger than the section headings inside the page; they used to be the same size.
- Settings -> Interface -> Button side is a row with both choices on it: one tap to switch, and you can see what the other option is without opening anything.
- Settings lists deal their rows in when a section opens, and the status bar rolls a number over instead of swapping it. Both are decorative, so both stop while recording and when the system has animations turned off.
- The floating record button and the preview window's button follow the palette: neutral ground, red only on the dot that marks recording. They were iOS red/green and blinking green, neither of which is in the palette, and both of which said "red means button" instead of "red means recording".

## [0.39.1-alpha] - 2026-09-12

- Fixed: settings dialogs had no confirm button (video and photo limits, licence plate, button side, and the first-launch driver-side question). They were framework dialogs, whose button bar this head unit does not draw. Every dialog is now a Material dialog - what the dialogs that always worked (camera mapping, device name) were already using. A test keeps it that way.
- Dropdown settings (video stream profile, storage location, button side) now ask for confirmation. They used to apply the moment you touched an entry, with no way back.
- Fixed: dialog buttons were clipped along the top, so only their bottom corners looked rounded.
- Text inputs in dialogs share one style (licence plate, storage limits, device name, problem description). They were two different-looking boxes. Filled buttons all use the same orange.

## [0.39.0-alpha] - 2026-09-12

- First launch asks which side the driver sits on and puts the action rail on that side. Existing installs are asked once.
- Settings -> Interface: button side, and "Reduce motion while recording" (on by default).
- Record button is disabled when there is nowhere to record (no USB drive). The status bar shows the fps cap, bitrate tier and free space.
- Settings rows redesigned: section icons, values on the right, switches, chevrons.
- Stream profile editor in two panes (streams / lanes) with a lane map.
- Drawer regrouped; the rear-view switch toggles in place.
- Dialogs share one style; destructive buttons are red.
- About, Diagnostics and Timeline use the shared title bar. Fixed white-on-light text in day mode.
- Four-up: the tapped lane grows out of its cell.
- English UI: playback, notifications, diagnostics, image adjustment, the profile editor's save checks, update errors and the developer section no longer show Chinese. A test blocks new hardcoded text.
- Removed unused layouts and code.

## [0.38.0-alpha] - 2026-09-11

- New look aligned with Zeekr OS: warm greys, Zeekr orange accent, flat cards. Day and night follow the system.
- Main screen rebuilt on one skeleton: title bar (menu top-left), preview, action rail, status bar.
- Record button: red only on the dot and the clip ring. The dot morphs circle to square; the ring fills per clip.
- Status bar shows clip progress; the recording chip shows elapsed time and clip number.
- Vector icons replace the text glyphs.
- Settings -> System -> Button side: action rail on the left or right.
- Transitions: settings sections move along the list, sub-pages and full screens zoom in.
- Fixed: in the surround + cabin layout, single view hid the cabin labels and lane placement could move them.
- One theme for day and night (the night copy had drifted); a test keeps the two colour sets in step.

## [0.37.13-alpha] - 2026-09-05

- Fixed: the four lane labels stayed pinned to the corners of the screen when a lane was
  moved or resized, so a label could sit on someone else's picture. Each label now follows
  its own lane.
- Fixed: opening the editor before the cameras had started described the surround-view
  stream as not split, which hid the per-lane controls. Whether a stream splits is a
  property of the camera, not of whether it happens to be open.

## [0.37.12-alpha] - 2026-09-05

- The stream profile editor has moved out of developer options into Settings -> Recording,
  and is built like the rest of Settings. So has "Photos through the image channel", which
  is what makes the photo resolution mean anything.
- Fixed: rotation, mirroring, position, size, crop and pan were stored per lane and read by
  nobody. The four-up view drew a hard-coded 2x2 and ignored all of it, so changing them in
  the editor did nothing. They now draw what they say.
- Each lane of a split stream is edited on its own - front, rear, left and right each have
  their own placement, rotation, mirroring, crop and pan. They were one setting for the
  whole camera, which cannot express "mirror the rear view only".
- "auto" and "max" now print the size they resolve to, everywhere a resolution is shown or
  chosen, next to what a split stream lands as on disk.
- Removed the recording layout choice. A stream that splits is always stored as the 2x2
  grid; the strip loses half the detail and playback zoom assumes the grid anyway.

## [0.37.11-alpha] - 2026-09-05

- Camera and stream parameters now live in one place. Frame rate, bitrate, codec, segment
  length, which cameras record and the recording layout each had a setting of their own
  while the profile editor showed its own copy of the same thing. Recording now reads the
  profile, per camera, and those settings are gone from Settings.
- Fixed: frame rate, bitrate, segment length and codec in the profile editor had no effect
  at all. Recording still read the old global settings, so the editor showed one value and
  the camera used another.
- Removed "Low-resolution preview". Each camera's preview size is set in the profile, and
  that switch could silently shrink it below what the editor showed.
- A camera that is enabled in the profile is the camera that records. Choosing them
  separately allowed a camera that was open, and holding a stream, for nothing.
- Fixed: "auto" for recording copied the preview size, so setting the preview to 640x480
  recorded 1280x240. The surround-view stream's "auto" is now the size that keeps the most
  detail per lane, whatever the preview is set to.
- Fixed: photo resolution had no effect unless a developer option was on. Photos now go
  through the camera's JPEG channel by default; if the session cannot take that extra
  stream, it is the first thing dropped, so the picture is never lost for a photo.
- Fixed: "max" as a preview resolution resolved to nothing and fell back to the old rule.
- The profile editor prints what a size lands as: the per-lane size, and the size the file
  will actually be after the four lanes are rearranged into the 2x2 grid.

## [0.37.10-alpha] - 2026-09-05

- Fixed: the four camera labels on the main screen stayed Chinese in an English interface.
  They were written into the code instead of the string table, as were the names in the
  recording-camera setting, the fullscreen preview, the profile editor and the playback zoom.
- The recording resolution setting still pointed at a developer option removed in 0.37.9.
- Fixed: with three cameras, a photo group showed only two of them until you refreshed and
  then switched to another photo and back. A refresh rebuilt the list, but the preview still
  held the photo group from the previous scan.
- Photos now reach the drive as soon as they are taken. Each camera slept its own camera
  thread for up to two seconds after decoding the picture, so the third photo landed 2.6
  seconds after the shutter - late enough for the photo screen to miss it, and long enough
  to hold up that camera's session callbacks.

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
