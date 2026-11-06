# Australian 7X: recirculation

Research contributed by Calvin Bui for [#138](https://github.com/dts88/zeekr-shortcut-car/issues/138),
tested through App Lab on one Australian-market ZEEKR 7X, reported firmware **2.1.1c**,
31 October-3 November 2026. The App Lab version was not recorded.
Zeekr Shortcut's ECARX integration was the starting point for a separate, small diagnostic APK.

This follows the distinction in [the platform notes](zeekr-platform-notes.md):
**on-car observation**, **implementation inspection**, and **inference / unverified behaviour**.
The car results below come from that diagnostic, not a Zeekr Shortcut release.

Related findings: [second-row AC](australian-7x-rear-ac-findings.md) and
[auto high-beam](australian-7x-auto-high-beam-findings.md).

## 1. Result and reset trigger

**Verified on this car:** Auto → Recirculation was mapped using factory controls, then changed
by an explicit API command with matching readback and factory-screen confirmation. One
subsequent climate off/on test restored Recirculation and rear AC, using one command each.
Both settings still matched about 91 seconds later.

**On-car observation:** both climate preferences were retained when returning to the car.
The reproducible reset was switching the whole climate system off, so the fan reached zero,
then switching it on. Circulation already read Auto in the off snapshot. The separate cooling
button labelled A/C was not tested as an independent trigger. This corrects the initial
drive-start assumption in #138.

Permanent preference options (for example, remember-last or explicit defaults), trigger
policy and product integration remain open. This does not establish unattended startup or
compatibility with other firmware/markets.

## 2. ECARX access and exact mapping

**On-car observation:** reflection through the runtime ECARX API worked inside App Lab.
No ECARX classes were bundled in the diagnostic APK. The connection path was:

```java
Class<?> factory = Class.forName("com.ecarx.xui.adaptapi.car.Car");
Object car = factory.getMethod("create", Context.class).invoke(null, context);
Class<?> carApi = Class.forName("com.ecarx.xui.adaptapi.car.ICar");
Object functions = carApi.getMethod("getICarFunction").invoke(car);
Class<?> functionApi = Class.forName("com.ecarx.xui.adaptapi.car.base.ICarFunction");
```

| Purpose | Property / scope | Observed values and calls |
|---|---|---|
| Circulation | `IHvac.HVAC_FUNC_CIRCULATION = 0x10030100`, default overload | `CIRCULATION_AUTO = 0x10030103`; `CIRCULATION_INNER = 0x10030101`. Read: `getFunctionValue(0x10030100)`. Verified write: `setFunctionValue(0x10030100, 0x10030101)`. |
| Front climate power | `IHvac.HVAC_FUNC_POWER = 0x10010100`, `VehicleZone.ZONE_ROW_1_ALL = 0x08` | Read `1 → 0 → 1` during the factory power cycle. No front-power write tested. |
| Front fan | `IHvac.HVAC_FUNC_FAN_SPEED = 0x10020100`, zone `0x08` | Read `FAN_SPEED_LEVEL_1 = 0x10020101 → 0 → LEVEL_1` in that cycle. No fan write tested. |

`IHvac` is `com.ecarx.xui.adaptapi.car.hvac.IHvac`;
`VehicleZone` is **`com.ecarx.xui.adaptapi.vehicle.VehicleZone`**, outside the `car` package.
These IDs, zone names and values were checked against the car's runtime constants. Zone
meanings here apply to these HVAC properties; they should not be generalized to other properties.
The calls above are verified arguments, not an unguarded startup recipe. The attended test's
dispatch conditions are in §4.

Circulation was first mapped by factory Auto → Recirculation → Auto changes, then an explicit
write and matching factory screen. The setter returned `true`, but acceptance alone was not
counted as successful control.

**Read pitfalls on this car:**

- Default/global power stayed `0` while climate was both on and off; it is not a front-power signal.
- Default and front-left/right fan reads returned `255`; individual front-left/right power
  stayed `0`. The useful front pair was the explicitly named **row-one ALL** scope.
- A custom float fan read returned `1.4E-45`; it was not interpreted as a physical fan speed.
- Supported-value/zone lists did not provide a usable enumeration. An `active` support flag,
  an empty list, or a zero alone did not prove a factory mapping.
- Inspection of the installed adapter showed missing backend power data can become `0`.
  The test therefore required positive ON evidence and corroborating power/fan changes.

## 3. Factory power-cycle comparison

**On-car observation, diagnostic v0.20, 3 November 2026:** the owner recorded these three
factory states in Park at zero speed, using the same climate power control.
Times throughout this note are Sydney time (UTC+11).

| Reading | AC1: on with preferences, 11:39:06.953 | AC2: off, 11:39:19.203 | AC3: on again, 11:39:33.462 |
|---|---|---|---|
| Circulation, default | INNER `0x10030101` | AUTO `0x10030103` | AUTO `0x10030103` |
| Front power, zone `0x08` | `1` | `0` | `1` |
| Front fan, zone `0x08` | LEVEL_1 `0x10020101` | `0` | LEVEL_1 `0x10020101` |

These were sequential snapshots, not atomic frames. They establish the settled states,
not the precise reset instant or a generally safe initialization delay.
[Rear power and fan readings](australian-7x-rear-ac-findings.md#3-factory-power-cycle-comparison)
come from the same three snapshots.

## 4. One-cycle restoration test

**On-car observation, diagnostic v0.21, 3 November 2026:** the owner explicitly started
one test, switched climate off and on using the factory control, and confirmed both factory
settings changed as requested. The test began with circulation already Auto and rear power
already off; §3 independently establishes how that state is produced.

| Event | Time | Result |
|---|---|---|
| Explicit test started | 12:15:10.705 | One arm; no automatic re-arm. |
| Front ON first observed | 12:15:11.801 | Power `1`, fan LEVEL_1. |
| Front OFF confirmed | 12:15:26.179 | Two OFF samples at least one second apart. |
| Stable front ON confirmed | 12:15:36.522 | Power `1`, same known fan value across two samples. |
| Circulation command | 12:15:36.580 | Accepted; INNER readback at 12:15:36.609. |
| Combined final check | 12:15:38.043 | INNER and rear power `1`; `READBACK_MATCHED`. |
| Later snapshot | 12:17:09.065 | Both still matched. |

There was **one setter call per setting**, with no write retry and no further restoration
events through the later snapshot. Factory-screen confirmation was supplied by the owner
afterward; the diagnostic could not inspect the factory UI itself. The
[rear-power command and delayed readback](australian-7x-rear-ac-findings.md#4-one-cycle-restoration-test)
were part of this same test.

The prototype's constraints applied to both climate settings:

- A 90-second absolute limit from the button press, one explicit arm, and no automatic
  startup/re-arm. It first observed ON, then two OFF samples, then two stable ON samples
  about one second apart. This timing worked in this test; it is not a universal readiness rule.
- Runtime constants and active support checked; Park and exactly zero speed required.
  Front/max/automatic-front defrost readings had to be known zero. Unknown targets stopped
  the test. All those conditions were re-read before each command; a condition sample taking
  more than two seconds to collect was rejected. Already-correct targets were skipped.
- The arm was durably consumed before any write. A failed/uncertain acknowledgement stopped
  the remaining work. Readbacks were bounded; the rear command needed a delayed readback,
  not another write. A final combined check followed a one-second wait.
- Stop, screen-off and expiry cancel future calls; an in-flight Binder call cannot be undone.
  Vehicle access remains busy until it returns. Process death never resumes the arm.
  The test used a non-sticky foreground service, with no wake locks, alarms or new observers.

**Unverified:** there was no Activity pause during the active test, so it does not demonstrate
background-Activity survival. Sleep/wake delivery, head-unit-restart startup, driving-session
detection, a later manual override, and interaction with active automatic windshield clearing
were not established. Inactive defrost readings alone are not evidence for that last case.

## 5. Evidence retained for follow-up

The contributor retains the original reports and diagnostic source. These hashes identify
the cumulative reports; the tables above compare their labelled samples, not whole-file diffs.
Full device logs and exported vehicle software are not included in this documentation change.
The same reports also support the second-row AC finding.

| Evidence | Diagnostic | Report SHA-256 |
|---|---|---|
| AC1/AC2/AC3 comparison (final cumulative report) | v0.20 | `074dd6e90ed092f0608d5ebca3226442ecebdbbfa836d0d60de1eeb27a7ad85d` |
| One-cycle restore and later snapshot | v0.21 | `26f0897da5f072c3fec743635434e123142de452624482baedb5be0828d39197` |

## 6. Zeekr Shortcut notes (2026-11-06)

- Remembering this setting between drives ([#138](https://github.com/dts88/zeekr-shortcut-car/issues/138)) will be
  built into Zeekr Shortcut's upcoming geofence feature (project owner, 2026-11-06).
- What Zeekr Shortcut has already verified about deep sleep, power-down and restarts on its own 7X is summarised in
  [Comfort acceleration, section 8](australian-7x-acceleration-findings.md#8-zeekr-shortcut-notes-2026-11-06).
