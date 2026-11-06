# Australian 7X: Comfort acceleration

Research contributed by Calvin Bui for [#138](https://github.com/dts88/zeekr-shortcut-car/issues/138),
tested through App Lab on one Australian-market ZEEKR 7X, reported firmware **2.1.1c**,
3 November 2026. The App Lab version was not recorded.
Zeekr Shortcut's ECARX integration was the starting point for a separate diagnostic APK.

This follows the distinction in [the platform notes](zeekr-platform-notes.md):
**on-car observation**, **implementation inspection**, and **inference / unverified behaviour**.

## 1. Result and requested default

**Verified on this car:** one explicit ECARX Comfort command returned `true`, and the
owner confirmed that the factory Acceleration setting changed to **Comfort**. This verifies
attended manual control. A later explicitly armed warm off/on test also selected Comfort
automatically, with API acceptance and owner confirmation. Available-state API readback,
long sleep and head-unit restart reliability remain unverified.

**Owner observation:** Acceleration defaults to **Standard** when the car starts. The desired
behaviour is Comfort once at the beginning of each drive, preserving later manual changes.
The owner was instructed to start this command test from factory Standard; the diagnostic
did not read or verify the pre-command mode.

## 2. ECARX access and exact command

The diagnostic used `Car.create(context)` followed by `getICarFunction()` through reflection.
The command matches Zeekr Shortcut's existing driving-mode path in
[`Telemetry.setDriveMode`](../app/src/main/java/com/kooo/evcam/telemetry/Telemetry.java) and
[`EcarxSource.write`](../app/src/main/java/com/kooo/evcam/telemetry/EcarxSource.java).

| Purpose | Runtime mapping / call |
|---|---|
| Setting property | `IDriveMode.DM_FUNC_DRIVE_MODE_SELECT = 0x22010100` |
| Tested selection | `IDriveMode.DRIVE_MODE_SELECTION_ECO = 0x22010101` |
| Factory name | **Comfort**, as mapped by [`Signal.DRIVE_MODE_COMFORT`](../app/src/main/java/com/kooo/evcam/telemetry/Signal.java) |
| Tested command | `setFunctionValue(0x22010100, 0x22010101)` |

`IDriveMode` is `com.ecarx.xui.adaptapi.car.vehicle.IDriveMode`. The exact two-argument
boolean setter uses the default scope. Runtime constants were checked before dispatch.
The ECARX name ECO must not be confused with the factory label Comfort; the SDK's
COMFORT selection is a different value, labelled Standard by upstream.

**Implementation inspection:** the installed adapter translates the tested selection to
raw value `1` for `DRIVE_ZEEKR_MODE_PROPULSION_SYS` (`0x22400F21`). Its ordinary ECARX
mode getter can write a default when the underlying value is zero, so the diagnostic did
not call that getter before or after this command.

## 3. Command and factory result

Times are Sydney time (UTC+11), 3 November 2026, converted from the report's epoch fields.
They identify the attempt and separate observation starts, not the exact setter-dispatch time.

| Event | Time | Result |
|---|---|---|
| Command record / attempt start | 14:14:06.907 | Runtime constants checked; property and gear/speed sensor support `active`. |
| Explicit command result | Not separately timestamped | One app setter call returned `true`; no app retry. Whole attempt elapsed 1080 ms. |
| Separate observation start | 14:14:08.345 | Alias payload `1`; status before and after both `1` (`UNAVAILABLE`). |

The guards recorded Park (`0x00200230`) and finite speed `0.0`; guard collection took
**14 ms**. The test required a two-second maximum guard age and a ten-second call-start
deadline. Cancellation stopped future calls; a dispatched command could not be recalled.
The later observation started after the command worker released its shared gate.

After uploading the report, the owner confirmed: "done, car changed to comfort".
The report's `factoryUiConfirmed=false` records the diagnostic's own observation limit;
the factory confirmation was supplied separately by the owner.

## 4. Readback and integration limits

The separate read used `setting_zeekr`, alias `ID_ZONED_DRIVE_MODE_SELECT` (`0x00004005`),
at its advertised area `0x80000000`. The typed getter returned integer payload `1`, consistent
with the inspected raw translation, but both surrounding status samples were **UNAVAILABLE**.
The status/value/status calls are non-atomic, and the typed getter does not expose the
payload's own status or timestamp. This is uncertain supporting evidence, **not valid
available-state readback** or an independently verified Standard/Comfort read mapping.
Earlier comparison batches did not capture acceleration values either.

The standard `CarPropertyManager` route did not advertise raw configuration `0x22400F21`,
so no raw property read was attempted. Permission filtering versus unsupported access
remains indeterminate.

The warm vehicle off/on observation and follow-up below establish one successful automatic
Comfort command during the owner's short routine. Settings-initialization timing across
other starts, long sleep, head-unit restart and preservation of later manual changes remain
unverified. This finding does not establish permanent startup reliability or a reliable getter.

## 5. Vehicle off/on signal observation

**On-car observation, diagnostic v0.30:** during one normal door-and-seat off/on routine,
the same two vehicle inputs used by Shortcut changed together while Android remained awake.
All reads reported active support, and runtime constants were checked first.

| Samples | Usage mode `0x20259000` | Screen-power event `0x20290300` |
|---|---|---|
| 1-28 | DRIVING (`0x00200107`) | `0` |
| 29-33 | ON (`0x00200105`) | `0` |
| 34-38 | ACC (`0x00200104`) | `1` |
| 39-53 | ON (`0x00200105`) | `0` |

The capture lasted about 54 seconds. Android reported `interactive=true` throughout,
and elapsed time minus uptime did not increase, indicating no measured CPU suspend.
USG labels are the runtime constants; DRIVING here is not a gear or motion measurement.
The default-scope climate-power read stayed zero and did not identify the transition.

This matches [`VehicleScreen`](../app/src/main/java/com/kooo/evcam/screen/VehicleScreen.java)'s
combined vehicle-state logic and the vehicle-lit handling in
[`ScreenState`](../app/src/main/java/com/kooo/evcam/screen/ScreenState.java).
The earlier diagnostic's Android-suspend requirement was the wrong condition for this routine;
its failure to select Comfort was not a rejected setter. The initial lit state and the
DRIVING-to-ON change must not count as a new drive. The useful observed edge is ACC/screen-off
to ON/screen-on. Sequential one-second polling does not establish subsecond ordering.

**No Comfort command was issued in this capture.** It establishes the input transition for
a subsequent one-shot test, not automatic restoration or cold-start reliability.

## 6. Automatic Comfort on one warm vehicle wake

**On-car observation, diagnostic v0.31:** one explicitly armed trial observed ON/screen0,
then four samples of ACC/screen1, then ON/screen0 again. After two fresh returning samples
at least one second apart, it prepared the same guarded Comfort command. Initial awake
state did not trigger a command.

| Event | Sydney time, 3 November 2026 | Result |
|---|---|---|
| First observed off state | 15:34:48.091 | ACC/screen1 |
| First observed return | 15:34:52.268 | ON/screen0 |
| Command helper start | 15:34:53.314 | Exact runtime constants and fresh Park/zero-speed guards |
| Command result | 1,052ms after first observed return, measured by elapsed clock | One setter attempted, returned `true` |

The complete report contains 15 samples, one preparation and one setter attempt. Property
and sensor support were active; gear was Park and speed was 0.0. Guard collection took 3ms,
and the command helper took 5ms. No ordinary ECARX mode getter or write retry was used.
The final phase was FINISHED. The owner then confirmed that it worked.

This verifies automatic Comfort selection for one attended warm door-and-seat cycle while
the process survived. The app used no wake lock, foreground service, alarm or scheduled job
for this trial. It did not require Android CPU sleep or a 60-second wait. The diagnostic's
own `factoryUiConfirmed=false` and `readbackPerformed=false` fields remain accurate about
what the app measured; factory confirmation was supplied separately by the owner.

The armed trial is one-shot and stops after dispatch. A subsequent manual mode change was
not separately tested. Long sleep, process death, a head-unit restart and permanent session
handling remain unverified. This result should feed Shortcut's existing vehicle wake logic,
not be presented as full startup reliability from a short-lived diagnostic.

## 7. Evidence retained for follow-up

The contributor retains the report and diagnostic source. Full device logs, vehicle
binaries and decompiled source are not included in this documentation change.

| Evidence | Diagnostic | Report SHA-256 |
|---|---|---|
| AM4 accepted Comfort command and separate observation | `0.26-comfort-command-test` | `d7c312ea426fdcf2e2ed26ed2dcee320da2a1e7068b240f6a77a86345cebd0ec` |
| Door-and-seat off/on signals and provider metadata | `0.30-combined-checks` | `e012d1660c384470da846da7db21b21efe55ce8918ab0c85fc8d35f1d2ca1bc8` |
| Automatic Comfort on one warm off/on cycle | `0.31-comfort-vehicle-wake` | `35b4eeb27b116f80269cf82e0cfb06dfd6ea15132ef1c52625d671c427a9ec57` |
