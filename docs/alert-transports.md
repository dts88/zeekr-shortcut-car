# Alert transports: one interface, one factory, one rule

> 原创设计与实现：Fab (Fabbbrrr) — https://github.com/Fabbbrrr。首次发布于 PR #211；按本仓库的惯例，引用这份文档里的事实时请标注来源。

> Original design and implementation: Fab (Fabbbrrr) — https://github.com/Fabbbrrr. First released in PR #211; per this repo's convention, cite the source when referencing facts from this document.

How a parking alert reaches the owner, and what adding a third route costs.

## The three layers

| Layer | Where | What it does |
|---|---|---|
| Decide | `alarm/policy/AlertPolicy`, `alarm/ntfy/NtfyRisk` | whether this alert may be sent at all |
| Orchestrate | `alarm/SentryAlerts` | capture, caption, backoff, blackbox, local notification |
| Exit | `alarm/AlertChannel` (interface) | send one photo or one line, translate the failure |

`AlertChannel` is a strategy; `AlertChannels.of(config)` is the factory. Both exits are thin
adapters and hold no policy:

- `alarm/TelegramChannel.java` wraps `alarm/telegram/TelegramSender`
- `alarm/ntfy/NtfyChannel.java` wraps `alarm/ntfy/NtfySender`

Under the channels sits `alarm/net/HttpCall`: connecting, timeouts (15 s connect / 30 s read), writing
a body, and reading the reply once (4 MiB cap, UTF-8, `errorStream` on 4xx, null stream reads as
empty). Both senders call it. Neither carries its own copy.

## The rule

> A new transport ships as **one package, one `AlertChannel`, one line in `AlertChannels`, one setup
> dialog**. `SentryAlerts` and `AlertPolicy` change by not one line.

This is not a convention, it is a test: `alarm/ProviderWiringTest`.

- every `implements AlertChannel` class in `alarm/` must appear in `AlertChannels.java`
- `SentryAlerts.java` and `policy/AlertPolicy.java` may not name a concrete exit

A third exit left unwired fails the first check. Naming one in the orchestrator to save a deadline
fails the second. Both failures are cheap to see and expensive to discover a year later.

## Why not inheritance

A base class over the two exits would have to reconcile three things that differ on purpose:

- **constructors**: `(token, chatId)` vs `(server, topic, token, paid, riskAck)`
- **failure vocabulary**: ntfy separates `NOT_ACKNOWLEDGED` / `TOO_LARGE` / `NOT_FOUND`; Telegram
  collapses to `rejected ? REJECTED : NETWORK`
- **pre-send policy**: ntfy refuses to send until the free-topic warning is accepted; Telegram has
  never had that gate

Java gives one superclass. Spending it on two adapters to unrelated protocols buys nothing and loses
the distinctions above. Composition (shared `HttpCall`, shared `Result`/`Kind`) covers the real
duplication.

## Boundary, stated

Pinned by the rule is the **sending** path. The setup wizards' live probes still name their sender
inside `SentryAlerts` — ask the ntfy account, ask the bot's name, list chats. That answers "is this
configured", not "where does it go", and forcing it into `AlertChannel` would hand every exit a
no-op method.

The price of keeping that boundary: a third transport's probe will want to add code to
`SentryAlerts`. When it does, give it its own interface. Do not pile it on in place.

## The return path, per exit

Sending is one interface. Coming back is not, and the difference is a property of the transport, not
a capability flag on an adapter:

- **Telegram** answers in a chat with an identity: the poller only accepts messages from the
  configured chat and owner id.

## Explicitly not done yet

Deferred until a third transport is actually on the table — two copies are not evidence:

1. a transport descriptor (name + channel factory + `hasReturnPath()` + capabilities) so the
   `isAlertsNtfy()` arms in `SentryAlerts` become `transport.hasReturnPath()`
2. rename `ntfy/NtfyGate` to `AlertTransports` — it holds the `TELEGRAM` constant and is nobody's
   ntfy-only concern

## Tests

| Test | Pins |
|---|---|
| `alarm/net/HttpCallTest` | body/method/headers leave; 4xx body comes back; reply read once, capped, UTF-8; null stream is empty |
| `alarm/AlertChannelsTest` | stored route to exit, unknown value stays Telegram, each exit names the step it is missing, clip capability |
| `alarm/ProviderWiringTest` | the rule above, plus a fake third exit that needs nothing from the existing two |
| `alarm/ntfy/NtfyTopicRotationTest` | rotation mints a legal new name, kills the old one and clears consent; the gate is wired to settings |
| `alarm/ntfy/NtfyRequestTest` | topic shape, tail length, and different seeds giving different names |
