# Alert transports: one interface, one factory, one rule

> 原创设计与实现：Fab (Fabbbrrr) — https://github.com/Fabbbrrr。首次发布于 PR #211；按本仓库的惯例，引用这份文档里的事实时请标注来源。

> Original design and implementation: Fab (Fabbbrrr) — https://github.com/Fabbbrrr. First released in PR #211; per this repo's convention, cite the source when referencing facts from this document.

How a parking alert reaches the owner, and what adding a second route costs.

## The three layers

| Layer | Where | What it does |
|---|---|---|
| Decide | `alarm/policy/AlertPolicy`, `alarm/ntfy/NtfyRisk` | whether this alert may be sent at all |
| Orchestrate | `alarm/SentryAlerts` | capture, caption, backoff, blackbox, local notification |
| Exit | `alarm/AlertChannel` (interface) | send one photo or one line, translate the failure |

`AlertChannel` is a strategy; `AlertChannels.of(config)` is the factory. The one exit is a thin
adapter and holds no policy:

- `alarm/ntfy/NtfyChannel.java` wraps `alarm/ntfy/NtfySender`

Under the channel sits `alarm/net/HttpCall`: connecting, timeouts (15 s connect / 30 s read), writing
a body, and reading the reply once (4 MiB cap, UTF-8, `errorStream` on 4xx, null stream reads as
empty). The sender calls it and carries no copy of its own.

## The rule

> A new transport ships as **one package, one `AlertChannel`, one line in `AlertChannels`, one setup
> dialog**. `SentryAlerts` and `AlertPolicy` change by not one line.

This is not a convention, it is a test: `alarm/ProviderWiringTest`.

- every `implements AlertChannel` class in `alarm/` must appear in `AlertChannels.java`
- `SentryAlerts.java` and `policy/AlertPolicy.java` may not name a concrete exit

A second exit left unwired fails the first check. Naming one in the orchestrator to save a deadline
fails the second. Both failures are cheap to see and expensive to discover a year later.

## Why not inheritance

A base class over one exit buys nothing: the interface is already the shared shape, the shared
`HttpCall` and the shared `Result`/`Kind` cover the real duplication, and a second adapter to an
unrelated protocol would only have to reconcile differences that exist on purpose (constructors,
failure vocabulary, pre-send gates such as the free-topic warning).

## Boundary, stated

Pinned by the rule is the **sending** path. The wizard's live probe names its sender inside
`settings/NtfySetupDialog` — it asks the ntfy account whether the digits arrived. That answers "is
this configured", not "where does it go", and forcing it into `AlertChannel` would hand the exit a
method it cannot answer. When a second transport's probe needs a home, give it its own interface. Do
not pile it on in place.

## The return path

There is none, on purpose: ntfy is one-way, the car publishes and receives nothing. If a transport
ever grows a return path (commands, buttons), that difference is a property of the transport, not a
capability flag on an adapter.

## Explicitly not done yet

Deferred until a second transport is actually on the table — one copy is not evidence:

1. a transport descriptor (name + channel factory + `hasReturnPath()`) so the orchestrator's arms
   could become `transport.hasReturnPath()`
2. a place for route names that is not `ntfy/NtfyGate`

## Tests

| Test | Pins |
|---|---|
| `alarm/net/HttpCallTest` | body/method/headers leave; 4xx body comes back; reply read once, capped, UTF-8; null stream is empty |
| `alarm/AlertChannelsTest` | the factory always hands out the ntfy exit, and the exit names the step it is missing |
| `alarm/ProviderWiringTest` | the rule above, plus a fake second exit that needs nothing from the existing one |
| `alarm/ntfy/NtfyTopicRotationTest` | rotation mints a legal new name, kills the old one and clears consent; the gate is wired to settings |
| `alarm/ntfy/NtfyRequestTest` | topic shape, tail length, and different seeds giving different names |
