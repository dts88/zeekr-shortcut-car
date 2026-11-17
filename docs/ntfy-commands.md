# ntfy commands: off by default, paid only, and why the topic is rotated

The maintainer accepted ntfy as a **one-way** transport: alerts leave the car, nothing comes back.
This fork keeps the return path, because it was built and tested and some owners want it, but it is
off unless three separate things are true at once — and the switch that turns it on does not appear
until developer options are unlocked.

## The rule, in one place

`alarm/ntfy/NtfyCommands` is the only place that decides. It is a pure function over three booleans:

| developer options | ntfy plan | switch | state | what it means |
|---|---|---|---|---|
| closed | any | any | `HIDDEN` | the feature does not exist for this owner |
| open | free | any | `NEEDS_PAID_PLAN` | a public topic must never accept commands |
| open | paid | off | `OFF_BY_OWNER` | one-way, as upstream ships it |
| open | paid | on | `ON` | the five-minute command window after each alert |

Order matters and is pinned by `NtfyCommandsTest`: developer options closed beats "paid and on", so
closing developer options cannot leave a poller running that nobody can see or stop.

`KEY_ALERTS_NTFY_COMMANDS` is listed in `AppConfig.DEVELOPER_KEYS`, so with developer options closed
it reads as off — the same gate as every other developer setting. There is no second stored flag.

## Why paid only

A free ntfy topic has no access control. The name is the password, and the name is printed on the
phone's lock screen and in the app. Anyone who sees it can publish to the topic, so on a free topic a
command channel means **anyone who saw one notification can ask the car for a picture, or ask it to
switch Sentry Mode**. That is not a permission decision an owner can make for a stranger who
shoulder-surfs their phone.

A paid account adds an access token, so publishing requires a credential. The poller still trusts the
topic, but at least the topic is not writable by whoever read the name.

The consequence for the price of the feature, written down rather than discovered: commands need a
paid ntfy account **and** a token saved, and the switch explains that on the free tier instead of
silently refusing.

## The comment the owner reads when picking ntfy

A non-selectable row under the ntfy group (`pref_alerts_ntfy_commands_note`, visible while the free
tier is selected) says it plainly: the topic name is the address, anyone holding it can read the
pictures and post to the topic, so commands stay off and ntfy carries one-way alerts only. When the
owner flips the paid switch, that row disappears and the command switch becomes usable — the two
rows are the same sentence in two states, not two features.

## Rotating the topic

`pref_alerts_ntfy_rotate` mints a new topic, stores it on the car, and immediately shows the
`ntfy://` QR code so the phone can subscribe to the new name. Three things happen together, and they
are deliberate:

1. **the old name stops being used** — the car publishes only to the stored topic;
2. **the risk acceptance is cleared** — `NtfyRisk` records consent per topic, so a new name asks
   again. Consent to publish to one public name is not consent to publish to another;
3. **the rotation is logged as a rotation**, with the last four characters only.

Rotating is the answer to "someone saw my lock screen": a new name, a new QR, and the old one is dead
as far as this car is concerned.

### Where the new name comes from

`NtfyRequest.generateTopic(nowMillis, installMillis, round, buildSalt)` mixes four inputs into a
SHA-256 digest and streams 40 characters of a 62-symbol alphabet out of it, six bits at a time with
rejection sampling (so every character is uniform):

| input | what it buys |
|---|---|
| 16 bytes from `SecureRandom` | the secrecy — 238 bits of it |
| install timestamp (first recorded rotation of this install) | two cars installed at different times differ even if a platform RNG were broken |
| current timestamp | a rotation is never a repeat of an earlier one, even within the same millisecond |
| rotation counter | the Nth rotation of one car cannot equal the (N−1)th |
| build fingerprint/model | two cars on the same build and the same install second still differ |

The timestamps are **not** secrecy; they are anti-collision. That distinction is the honest one: 238
random bits are what stops a guess, and the clock is what stops two cars landing on the same name
and reading each other's alerts. The output shape is unchanged — `zeekrsc_` + 40 characters, which
`NtfyRequest.looksLikeTopic` already required.

The count of collisions this design can produce between two cars on ntfy.sh is about 2^-238 per pair
per rotation, which is why the mix is defensible rather than clever: rotating the name is a security
control, and a security control that can collide with a stranger's car is not one.

## What is not claimed

- The poller still has no identity. ntfy does not say who published a message; a paid topic raises the
  cost of publishing but does not name the publisher. The five-minute window and the topic name are
  the whole defence.
- The paid tier is read from a stored switch, not from `/v1/account`. An owner can tick "paid" with a
  free account and get a switch that does nothing — the first real command then fails with 401/403,
  which the blackbox records. Verifying the tier on save is possible; it is not done here because it
  would mean a network call from the settings screen for a switch that only gates a developer
  feature.
