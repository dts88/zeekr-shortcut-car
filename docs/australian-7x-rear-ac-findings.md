# Australian 7X: second-row AC

Research contributed by Calvin Bui for [#138](https://github.com/dts88/zeekr-shortcut-car/issues/138),
tested through App Lab on one Australian-market ZEEKR 7X, reported firmware **2.1.1c**,
31 October-3 November 2026. The App Lab version was not recorded.
Zeekr Shortcut's ECARX integration was the starting point for a separate, small diagnostic APK.

This follows the distinction in [the platform notes](zeekr-platform-notes.md):
**on-car observation**, **implementation inspection**, and **inference / unverified behaviour**.
The car results below come from that diagnostic, not a Zeekr Shortcut release.

Related findings: [recirculation](australian-7x-recirculation-findings.md) and
[auto high-beam](australian-7x-auto-high-beam-findings.md).

## 1. Result and reset trigger

**Verified on this car:** rear HVAC power OFF → ON was changed by an explicit API command,
with matching scoped readback and factory-screen confirmation. This is rear HVAC power,
not the rear climate-control lock. Rear airflow was not separately measured.

Both climate preferences were retained when returning to the car. Switching the whole
climate system off, so the fan reached zero, then on reset recirculation and left rear power
off. The separate cooling button labelled A/C was not tested as an independent trigger.
This corrects the initial drive-start assumption in #138.

One explicitly armed climate off/on test restored rear AC and Recirculation, with one command
each. Both still matched about 91 seconds later. Permanent preference options, trigger policy
and product integration remain open; unattended startup and other firmware/markets are unverified.

## 2. ECARX access and exact mapping

The diagnostic used reflection through `Car.create(context)` → `getICarFunction()` inside
App Lab, without bundling ECARX classes. The connection code is recorded in the
[recirculation finding](australian-7x-recirculation-findings.md#2-ecarx-access-and-exact-mapping).

| Purpose | Property / scope | Observed values and calls |
|---|---|---|
| Second-row power | `IHvac.HVAC_FUNC_POWER = 0x10010100`, `VehicleZone.ZONE_ROW_2_ALL = 0x80` | OFF `0`, ON `1`. Read: `getFunctionValue(0x10010100, 0x80)`. Verified write: `setFunctionValue(0x10010100, 0x80, 1)`. |
| Rear fan, read only | `IHvac.HVAC_FUNC_FAN_SPEED = 0x10020100`, zone `0x80` | AUTO `0x1002010A` when on in these samples, `0` when off. No fan or AUTO-mode write tested. |

`IHvac` is `com.ecarx.xui.adaptapi.car.hvac.IHvac`;
`VehicleZone` is **`com.ecarx.xui.adaptapi.vehicle.VehicleZone`**, outside the `car` package.
The three-argument setter order is **property, zone, value**; its return type is `boolean`.
The property, zone and values were checked against the car's runtime constants. Zone meanings
here apply to these HVAC properties; they should not be generalized to other properties.
These calls are verified arguments, not an unguarded startup recipe.

At the time of this contribution, [`EcarxSource`](../app/src/main/java/com/kooo/evcam/telemetry/EcarxSource.java)
has zoned reads, but its `write(int, int, Consumer<Boolean>)` helper resolves only the
two-argument setter. Rear AC needs the three-argument overload and a readback from the
same rear scope; passing rear power through the existing unzoned write helper is not equivalent.

Rear power was mapped using the second-row switch on the front factory screen, scoped
callbacks/reads and a separate explicit write. The setter returned `true`, but acceptance
alone was not counted as successful control.

Supported-value/zone lists did not provide a usable enumeration. An `active` support flag,
an empty list, or a zero alone did not prove a factory mapping. Inspection of the installed
adapter showed missing backend power data can become `0`. The restoration test used positive
front ON evidence and corroborating power/fan changes. Default/global power and individual
front-left/right reads did not identify front climate power; the useful front scope was
`VehicleZone.ZONE_ROW_1_ALL = 0x08`. More read pitfalls are in the
[recirculation finding](australian-7x-recirculation-findings.md#2-ecarx-access-and-exact-mapping).

## 3. Factory power-cycle comparison

**On-car observation, diagnostic v0.20, 3 November 2026:** the owner recorded these three
factory states in Park at zero speed, using the same climate power control.
Times throughout this note are Sydney time (UTC+11).

| Reading | AC1: on with preferences, 11:39:06.953 | AC2: off, 11:39:19.203 | AC3: on again, 11:39:33.462 |
|---|---|---|---|
| Rear power, zone `0x80` | `1` | `0` | `0` |
| Rear fan, zone `0x80` | AUTO `0x1002010A` | `0` | `0` |
| Front power, zone `0x08` | `1` | `0` | `1` |

These were sequential snapshots, not atomic frames. They establish the settled states,
not the precise reset instant or a generally safe initialization delay.
[Circulation and front fan readings](australian-7x-recirculation-findings.md#3-factory-power-cycle-comparison)
come from the same snapshots.

## 4. One-cycle restoration test

**On-car observation, diagnostic v0.21, 3 November 2026:** the owner explicitly started one
test, switched climate off and on using the factory control, and confirmed both factory
settings changed as requested. It began with rear power already off and circulation Auto;
the factory cycle above independently establishes how that state is produced.

| Event | Time | Result |
|---|---|---|
| Explicit test started | 12:15:10.705 | One arm; no automatic re-arm. |
| Stable front ON confirmed after OFF | 12:15:36.522 | Power `1`, same known fan value across two samples. |
| Rear-power command | 12:15:36.689 | Accepted; first readback still `0`, next readback `1` at 12:15:36.994. |
| Combined final check | 12:15:38.043 | Rear power `1` and circulation INNER; `READBACK_MATCHED`. |
| Later snapshot | 12:17:09.065 | Both still matched; rear fan read AUTO. |

There was **one rear-power setter call**, with a delayed readback rather than a write retry,
and no further restoration events through the later snapshot. Factory-screen confirmation
was supplied by the owner afterward; the diagnostic could not inspect the factory UI itself.

The shared test required runtime constants and support, Park, zero speed, and known inactive
front/max/automatic-front defrost readings before each command. It had one durable arm,
a 90-second limit, bounded reads, no automatic re-arm and no wake locks. Its full dispatch,
cancellation and lifecycle constraints are in the
[one-cycle test record](australian-7x-recirculation-findings.md#4-one-cycle-restoration-test).

**Unverified:** there was no Activity pause during the active test. Background-Activity
survival, sleep/wake delivery, head-unit-restart startup, driving-session detection, later
manual overrides and interaction with active automatic windshield clearing were not established.

## 5. Evidence retained for follow-up

The contributor retains the original reports and diagnostic source. These are the same
cumulative reports used for the recirculation finding; this was one combined restoration test.
Full device logs and exported vehicle software are not included in this documentation change.

| Evidence | Diagnostic | Report SHA-256 |
|---|---|---|
| AC1/AC2/AC3 comparison (final cumulative report) | v0.20 | `074dd6e90ed092f0608d5ebca3226442ecebdbbfa836d0d60de1eeb27a7ad85d` |
| One-cycle restore and later snapshot | v0.21 | `26f0897da5f072c3fec743635434e123142de452624482baedb5be0828d39197` |

## 6. Zeekr Shortcut notes (2026-11-06)

- Remembering this setting between drives ([#138](https://github.com/dts88/zeekr-shortcut-car/issues/138)) will be
  built into Zeekr Shortcut's upcoming geofence feature (project owner, 2026-11-06).
- What Zeekr Shortcut has already verified about deep sleep, power-down and restarts on its own 7X is summarised in
  [Comfort acceleration, section 8](australian-7x-acceleration-findings.md#8-zeekr-shortcut-notes-2026-11-06).
