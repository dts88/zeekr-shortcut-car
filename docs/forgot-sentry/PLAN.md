# Forgot Sentry — plan and ticket list

Sentry Mode off, car parked away from home, owner already walking away. The car switches it on
itself, or asks first and switches it on when nobody answers.

Read `DECISIONS.md` next to this file for the choices and what each one costs. The behaviour spec
lands in `docs/sentry-alerts-spec.md` §1.8 so it reads with the rest of the alerts feature; README
and `docs/ui-spec.md` gain their rows too.

## Why the timings are what they are

With Sentry off the head unit powers down 4–6 minutes after the owner leaves, and the process ends
3–5 s after that (`docs/lifecycle-spec.md:945`; the 2026-10-10 times and dates are in
`docs/sentry-alerts-spec.md` §7). That window is the whole feature:

| Constant | Value | Why |
|---|---|---|
| `GuardPolicy.ASK_AT_MS` | 5 s after leaving | the owner is still holding the phone |
| `GuardPolicy.AUTO_ON_MS` | 60 s after the ask | short on purpose: a long delay loses the car |
| `GuardPolicy.ACT_WINDOW_MS` | 180 s after leaving | conservative — well inside the measured 4–6 min, never at the edge |
| `GuardHome.RADIUS_M` | 200 m | GPS drift in a car park is tens of metres |
| `GuardHome.FIX_MAX_AGE_MS` | 10 min | an old fix is a guess, and a guess is `UNKNOWN` |

## Ground rules

- **Additive.** New files for the rules; shared files get mechanical edits only. Two other agents
  work in sibling worktrees (`wt-ntfy-alerts`, the polish run) and will edit `SentryAlerts`,
  `AppConfig`, `SettingsPreferenceFragment`, `preferences.xml` and the three `strings.xml` files.
- **Telegram only in v1.** Buttons need a callback channel; the ntfy work has none (its Phase 2 is
  unbuilt). One gate decides this, in one place.
- **Read back, never assume.** Every message quotes the value the car reported afterwards.
- Licence header on every new `.java`, GPL-3.0-only plus the `NOTICE.md` pointer.
- Gates, both, before any commit: `./gradlew :app:testDebugUnitTest --offline` and
  `./gradlew :app:assembleRelease --offline`.

## File ownership

| Slice | Files | Owner |
|---|---|---|
| Core rules | `alarm/GuardPolicy.java`, `alarm/GuardHome.java`, `alarm/GuardToken.java` (all new) + their tests | main |
| State + wiring | `alarm/SentryAlerts.java`, `AppConfig.java`, `button/SentryRemote.java` (new) | main |
| Telegram transport | `alarm/TelegramSender.java`, `alarm/TelegramPoller.java`, `alarm/PollerGate.java`, `alarm/TelegramCommand.java` + tests | worker A |
| Screen and copy | `res/xml/preferences.xml`, `settings/SettingsPreferenceFragment.java`, `res/values*/strings.xml`, `README.md`, `docs/sentry-alerts-spec.md`, `docs/ui-spec.md` | worker B |

Two rules that keep the tree mergeable: nobody touches another slice's files, and every shared-file
edit is appended at a named anchor rather than scattered.

## Tickets

### T1 `alarm/GuardPolicy.java` (new, pure)

The decision, and nothing else:

```java
enum Mode { OFF, ASK, ASK_THEN_ON, ON }              // stored 0..3, clamp() as ArmDelay.clamp
enum Verdict { IGNORE, ASK, ACT }
static Verdict decide(Mode mode, Integer sentry, boolean parked, boolean afterDrive,
                      GuardHome.Class where, long leftAtMs, long askedAtMs, long nowMs)
static boolean stillActionable(long leftAtMs, long nowMs)
static boolean autoOnDue(long leftAtMs, long nowMs)
```

`decide` refuses to act when: mode OFF; sentry null or not 0/1; not parked; no drive since the last
park (that is what separates "parked after a drive" from "parked since Tuesday"); home; a reminder
already sent for this park. Tests state each rule as a sentence.

### T2 `alarm/GuardHome.java` (new, pure)

`parse("lat,lon")`, haversine `distanceM`, and `classify(home, lat, lon, fixAgeMs)` →
`HOME / AWAY / UNKNOWN`. Unknown covers: no home set, no fix, stale fix, nonsense coordinates.

### T3 `alarm/GuardToken.java` (new, pure)

Mints the callback nonce and parses button data. `data` layout `g:<action>:<token>`, ≤64 bytes;
actions `on`, `no`, `off`, `st`. A press whose token is not the current one is not ours and returns
null — that is what stops a week-old button from acting.

### T4 `button/SentryRemote.java` (new)

Public facade over the package-private `CarMoves.run`, so the alarm package can switch sentry without
reaching into `button` internals. `on(Context, Callback)`, `off(Context, Callback)`, the callback on
the main thread, the result carrying `CarMoves.Result`.

### T5 `AppConfig` — four keys, inert by default

`alerts_guard_mode` (0..3, default 0 = off), `alerts_guard_home` (`"lat,lon"`, empty), and the two
getters/setters the settings screen needs. Plus `getAlertsGuardHomeDistanceM()` for the probe line.

### T6 `SentryAlerts` — one block, two call sites

- A new `// ---- 忘了开哨兵` block at the end of the class: edge detection for "owner left", the ask,
  the 60 s auto-on, the receipts, `onGuardButton`, `onGuardCommand`, `guardReportLine`.
- `onReadingsChanged`: one line calling the block, after the existing bookkeeping.
- The poller handler: `onButton` and the `GUARD` verb, both one line each, handed to the block.
- All network work on the existing `sender` executor; the auto-on timer on its own scheduler thread
  (a `stop()`-with-join never runs here). Every teardown path (`refresh()` when the feature is
  switched off) clears the pending timer and the state.

### T7 Telegram transport (worker A)

- `TelegramSender.getUpdates`: `allowed_updates=["message","callback_query"]`.
- `TelegramSender.answerCallbackQuery(token, id, text)`: POST, returns `Result`, text may be null.
- `TelegramPoller.dispatch`: `update.callback_query` → ack first (clear the spinner), persist the
  offset, then `Handler.onButton(data, sentAt)`. A callback carries no text, so the command parser
  never sees it.
- `PollerGate.verdictForCallback(...)`: same two-identity rule, same `MAX_AGE_MS`.
- `TelegramCommand`: `guard`, `guard on`, `guard off`, `guard auto on|off|ask` → `Verb.GUARD` plus a
  `GuardAction`.

### T8 Screen and copy (worker B)

One row `pref_alerts_guard` (four values, appended to the commands group), one action row
`pref_alerts_guard_home` ("Use this position as home"), both hidden when alerts are off; strings in
all three locales in the same order; README bullet; spec §1.8; the `ui-spec.md` row.

## Merge path

1. `feat/forgot-sentry` (this worktree, based on `feat/parked-alerts-pr` @ `984c3258`) → both gates.
2. Cherry-pick onto `feat/parked-alerts-pr` **after** the ntfy agent has finished there; expect
   conflicts only in the six shared files, all at named anchors (`getUpdates`, `dispatch`,
   `bindAlerts` append, `strings.xml` tail, `preferences.xml` commands group).
3. Cherry-pick the same commits onto `feat/fork-build`, then the usual "Testing only:" version bump
   commit on that branch only. Never bump on the PR branch (`KNOWLEDGE.md` §1).

## What this plan does not do

- Wake a sleeping car. Nothing can (measured: no RTC lever, the wake lock does not hold power-down).
- Enable sentry when the process was not running at all — see `DECISIONS.md` D7.
- Touch the ntfy transport, or move the transport choice out of the developer section.
