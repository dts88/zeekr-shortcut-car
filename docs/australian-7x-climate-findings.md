# Australian 7X: climate restoration and auto high-beam findings

Research contributed by Calvin Bui for [#138](https://github.com/dts88/zeekr-shortcut-car/issues/138),
tested through App Lab on one Australian-market ZEEKR 7X, reported firmware **2.1.1c**,
31 October–3 November 2026. The App Lab version was not recorded.
Zeekr Shortcut's ECARX integration was the starting point for a separate, small diagnostic APK.

This follows the distinction in [the platform notes](zeekr-platform-notes.md):
**on-car observation**, **implementation inspection**, and **inference / unverified behaviour**.
The car results below come from that diagnostic, not a Zeekr Shortcut release.

## 1. Result and scope

| Target | Result on this car |
|---|---|
| Recirculation | Auto → Recirculation command verified by API readback and factory-screen confirmation. |
| Second-row AC | Rear power OFF → ON command verified by scoped readback and factory-screen confirmation. This is rear HVAC power, not the rear-control lock. |
| Restore both after climate restarts | One explicitly armed on → off → on test restored both settings, with one command each. Both still matched about 91 seconds later. |
| Automatic high-beam (AHB) | No usable selection control verified and no lighting write attempted. Lamp output was distinguishable; AHB selection was not. The deeper API findings are in §5. |

**On-car observation:** both climate preferences were retained when returning to the car.
The reproducible reset was switching the whole climate system off, so the fan reached zero,
then switching it on. Circulation already read Auto in the off snapshot; rear power remained
off when front climate restarted. The separate cooling button labelled A/C was not tested
as an independent trigger. This corrects the initial drive-start assumption in #138.

The contribution records the controls and experiment, leaving permanent preference options
(for example, remember-last or explicit defaults), trigger policy and product integration open.
It does not establish unattended startup or compatibility with other firmware/markets.

## 2. ECARX access and exact mappings

**On-car observation:** reflection through the runtime ECARX API worked inside App Lab.
No ECARX classes were bundled in the diagnostic APK. The connection path was:

```java
Class<?> factory = Class.forName("com.ecarx.xui.adaptapi.car.Car");
Object car = factory.getMethod("create", Context.class).invoke(null, context);
Class<?> carApi = Class.forName("com.ecarx.xui.adaptapi.car.ICar");
Object functions = carApi.getMethod("getICarFunction").invoke(car);
Class<?> functionApi = Class.forName("com.ecarx.xui.adaptapi.car.base.ICarFunction");
```

The following are method signatures and verified arguments, not an unguarded startup recipe.
The attended test's dispatch conditions are in §4.

| Purpose | Property / scope | Observed values and calls |
|---|---|---|
| Circulation | `IHvac.HVAC_FUNC_CIRCULATION = 0x10030100`, default overload | `CIRCULATION_AUTO = 0x10030103`; `CIRCULATION_INNER = 0x10030101`. Read: `getFunctionValue(0x10030100)`. Verified write: `setFunctionValue(0x10030100, 0x10030101)`. |
| Second-row power | `IHvac.HVAC_FUNC_POWER = 0x10010100`, `VehicleZone.ZONE_ROW_2_ALL = 0x80` | OFF `0`, ON `1`. Read: `getFunctionValue(0x10010100, 0x80)`. Verified write: `setFunctionValue(0x10010100, 0x80, 1)`. |
| Front climate power | Same `HVAC_FUNC_POWER`, `VehicleZone.ZONE_ROW_1_ALL = 0x08` | Read `1 → 0 → 1` during the factory power cycle. No front-power write tested. |
| Front fan | `IHvac.HVAC_FUNC_FAN_SPEED = 0x10020100`, zone `0x08` | Read `FAN_SPEED_LEVEL_1 = 0x10020101 → 0 → LEVEL_1` in that cycle. No fan write tested. |

`IHvac` is `com.ecarx.xui.adaptapi.car.hvac.IHvac`;
`VehicleZone` is **`com.ecarx.xui.adaptapi.vehicle.VehicleZone`**, outside the `car` package.
The three-argument setter order is **property, zone, value**; its return type is `boolean`.
These IDs, zone names and values were checked against the car's runtime constants.
Zone meanings here apply to these HVAC properties; they should not be generalized to other properties.

At the time of this contribution, [`EcarxSource`](../app/src/main/java/com/kooo/evcam/telemetry/EcarxSource.java)
has zoned reads, but its `write(int, int, Consumer<Boolean>)` helper resolves only the
two-argument setter. Rear AC needs the three-argument overload and a readback from the
same rear scope; passing rear power through the existing unzoned write helper is not equivalent.

Circulation was first mapped by factory Auto → Recirculation → Auto changes, then an explicit
write and matching factory screen. Rear power was mapped using the second-row switch on the
front factory screen, scoped callbacks/reads and a separate explicit write. Both setters
returned `true`, but acceptance alone was not counted as successful control.
Rear airflow was not separately measured.

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
| Rear power, zone `0x80` | `1` | `0` | `0` |
| Rear fan, zone `0x80` | AUTO `0x1002010A` | `0` | `0` |

These were sequential snapshots, not atomic frames. They establish the settled states,
not the precise reset instant or a generally safe initialization delay.

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
| Rear-power command | 12:15:36.689 | Accepted; first readback still `0`, next readback `1` at 12:15:36.994. |
| Combined final check | 12:15:38.043 | INNER and rear power `1`; `READBACK_MATCHED`. |
| Later snapshot | 12:17:09.065 | Both still matched; rear fan read AUTO. |

There was **one setter call per setting**, with no write retry and no further restoration
events through the later snapshot. Factory-screen confirmation was supplied by the owner
afterward; the diagnostic could not inspect the factory UI itself.

The prototype's constraints, useful when evaluating an implementation:

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

## 5. Auto high-beam: useful negative results

### 5.1 Factory selection versus lamp output

**On-car observation:** seven owner-labelled states were compared after using the physical
high-beam stalk: neither high-beam mode selected, auto, manual, manual in Park, neither,
neither in Drive, auto. The owner clarified that from the untouched Drive state the first
stalk action selected manual high-beam and the second selected auto.

`BCM_FUNC_LIGHT_MAIN_BEAM = 0x21050200` read `0, 0, 1, 1, 0, 0, 0`.
It distinguished lamp output, not AHB selection. Across the 64 captured numeric readings,
the auto samples did not differ from the untouched Drive samples. This is a finding about
the queried API signals, not a full vehicle-bus capture.

Exterior-light mode `0x20040E00` remained `LAMP_EXTERIOR_LIGHT_CONTROL_AUTOMATIC = 0x20040E03`.
The generic SDK's `LAMP_EXTERIOR_LIGHT_CONTROL_AHBC = 0x20040E04` is only an enum candidate; it was **not written** or
verified as the stalk-selected preference. Other AHB-labelled candidates returned unknown values.

### 5.2 Android vehicle-service route

**On-car observation, diagnostic v0.19:** the public
`Car.createCar(Context, Handler, long, CarServiceLifecycleListener)` factory with timeout `0`
connected successfully and obtained the `property` and `light_zeekr` managers. The check
completed and disconnected normally. This is separate from the ECARX connection above.
The earlier deprecated factory had returned null, and the app-visible automotive feature
was false; changing connection method resolved that part of the experiment.

| Raw property | Meaning from the installed backend | Read permission reported | Configuration returned |
|---|---|---|---|
| `0x21408E18` | AHB-switch candidate | `android.car.permission.CAR_VENDOR_EXTENSION` | `null` |
| `0x11400E11` | Standard high-beam switch | `android.car.permission.CONTROL_CAR_EXTERIOR_LIGHTS` | `null` |
| `0x11400E01` | Standard high-beam lamp state | `android.car.permission.CAR_EXTERIOR_LIGHTS` | `null` |

All three permissions were ungranted in the local app context and reported protection level
`0x12` (signature/privileged). The diagnostic stopped before value reads because the exact
configurations were unavailable: **no getter denial or getter `SecurityException` was observed**.

**Implementation inspection:** the installed `CarService` builds its permission map from
runtime HAL properties and filters generic configurations by read permission. Combined with
the non-null permission metadata, this strongly suggests the candidates exist but configuration
access is filtered. **Inference:** restricted access is the likely obstacle; App Lab's local
permission result does not establish the backend caller identity, and transport errors can
also produce null. It is not proof that every integration route is unavailable.

### 5.3 Custom lighting alias collision

**Implementation inspection:** `LightHalZeekrService` maps raw AHB candidate `0x21408E18`
to legacy ID **16402 (`0x4012`)**, then maps goodbye-light request `0x21408E2C` to the same ID.
The later forward-map entry wins: outgoing calls to 16402 select **goodbye lighting**.
Passing the raw AHB ID to that manager is also not a route: its lookup rejects an unmapped
manager ID. These are source-derived results; no such write was attempted.

**On-car observation:** the custom manager returned 56 configurations, 54 unique IDs,
including two entries for 16402 with WRITE and READ_WRITE access. The public configuration
objects had already replaced the raw IDs with the shared alias, so neither access flag could
be assigned to AHB. ID16408 was a read-only lamp-output configuration, not an AHB preference.
No distinct usable AHB-selection route was identified in the returned configurations, and
no custom lighting values or writes were attempted.

Further progress needs an accessible control path or an appropriate OEM/App Lab integration
mechanism. Repeating the same stalk sequence would not resolve this access/mapping problem.

## 6. Evidence retained for follow-up

The contributor retains the original reports and diagnostic source. These hashes identify
the cumulative reports; the tables above compare their labelled samples, not whole-file diffs.
Full device logs and exported vehicle software are not included in this documentation change.

| Evidence | Diagnostic | Report SHA-256 |
|---|---|---|
| AC1/AC2/AC3 comparison (final cumulative report) | v0.20 | `074dd6e90ed092f0608d5ebca3226442ecebdbbfa836d0d60de1eeb27a7ad85d` |
| One-cycle restore and later snapshot | v0.21 | `26f0897da5f072c3fec743635434e123142de452624482baedb5be0828d39197` |
| Lighting managers/configurations | v0.19 | `c3ed5aaf282a21144ccbbb91fb0383b314dd1c4ae95423e258c6d52dc999c394` |

Backend inspection used the car's exported `CarService` version 12 (version code 32).
Relevant implementations were `CarPropertyService`, `PropertyHalService`, `PropertyHalServiceBase`,
`HalServiceBase`, and `LightHalZeekrService`. Their method behaviour is summarized above;
vendor binaries and decompiled source are not part of this PR.
