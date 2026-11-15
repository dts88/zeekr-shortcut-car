# Forgot Sentry — decisions and what each one costs

Every choice here has a downside written against it. If a decision has no downside, it was not a
decision.

## D1 — It belongs inside "Alert while parked", not beside it

The trigger uses the same telemetry listener, the same Telegram token and chat, the same send
executor, the same cooldown style. One section, one switch, one doc.

**Cost:** the alerts section grows by two rows, and the maintainer's "setup must be simple" rule is
under pressure. Mitigation: mode defaults to **off**, and an owner who never opens the row keeps
today's behaviour exactly.

## D2 — The trigger is the moment the owner leaves, not "parked with sentry off for X minutes"

Living edge: screen-black rising (`SCREEN_POWER_EVENT 0 → 1`, which on this car happens in the same
millisecond as ignition ON → ACC, `Signal.java:143`) or the driver seat going from occupied to empty,
either of them while parked.

**Cost:** two signals instead of one, and the seat sensor can lie (a bag on the seat). The screen
edge is the better one and is the default; the seat is a second chance, not a requirement.

**Alternative rejected:** "sentry off for 10 minutes while parked". By then the head unit is gone —
the reminder would be sent by a process that no longer exists.

## D3 — Conservative timing: ask at once, act after 60 s, give up after 3 minutes

- ask immediately (5 s after the leaving edge, so the owner's own door-close noise has passed);
- switch on 60 s after the ask unless a button says no;
- any button pressed more than 180 s after the leaving edge is refused with a reason.

**Cost:** an owner who takes two minutes to read the message may find the car already on — that is the
intended trade. 180 s is deliberately shorter than the measured 4–6 min window so the refusal happens
before the window can close, never after. A late press therefore gets an honest answer instead of
silence.

**Alternative rejected:** a 10-minute or 30-minute button life. It would look generous and work
never.

## D4 — Four modes, one code path

`Off / Ask / Ask then on / On` stored as 0..3. "Ask" means the button is the only way; "Ask then on"
is the default once the feature is switched on; "On" is silent auto-guard with a receipt.

**Cost:** four values to explain. Mitigation: the summary line under the row says what each does in
one clause, and the two "ask" modes differ only by the 60 s.

## D5 — Home is a point on the car, and unknown is allowed to act

"Set home here" stores the current fix; the gate compares with 200 m. No home, no fix, a fix older
than 10 minutes, or nonsense coordinates → `UNKNOWN`.

Rule: `HOME` never acts; `AWAY` and `UNKNOWN` may ask; only `AWAY` **or no home set at all** may
switch on by itself.

**Cost:** a wrong `AWAY` after GPS drift switches sentry on in the owner's driveway. Bounded by 200 m
and by "once per park". The opposite mistake — refusing to act because the fix is unknown — costs the
whole feature, which is worse in a theft-protection context.

**Amended 2026-11-15, at the owner's request:** one switch, "Also at home" (`alerts_guard_everywhere`,
off by default), lifts the home rule so the car asks and switches on wherever it is parked. It is a
separate switch rather than a fifth mode because the two questions are independent — *when* to act
(timeout) and *where* it counts.

**Its cost, plainly:** with it on, every park in the owner's own driveway switches sentry on, and the
head unit then stays awake there on the 12V battery. That is the load that made the project owner
delete the "prevent sleep" switch, which is exactly why this one defaults to off and says so in its
summary instead of hiding the price.

## D6 — Nothing is claimed before the car confirms it

Every message quotes the read-back value (`Sentry Mode: on (1)`), never the requested one. The receipt
for a refused write says the car did not accept it. Remote off is offered on the receipt and refused
when the value is 2 (armed), because `CarMoves.sentryOff` will not disarm an armed car — the honest
answer is "unlock it once".

**Cost:** one extra read-back round trip (up to 1 s) before the confirmation goes out, and a receipt
that sometimes says "not confirmed" on a car that actually did it. That is the correct direction for
the error to point.

## D7 — If the process was not running, nothing happens

The feature lives in the app process. A car that booted and never started the app cannot be reminded.

**Cost:** real. `KNOWLEDGE.md` records the keep-alive switches: boot receiver + the 15-minute
WorkManager task make this unlikely, not impossible. Written down in the spec so nobody promises
otherwise.

## D8 — Telegram only, in this change

Buttons need `callback_query`, and only the Telegram transport has one today. The ntfy work
(unbuilt Phase 2) has no inbound channel.

**Cost:** an owner who switches the transport to ntfy later keeps the reminder text but loses the
buttons — which the row's summary must say. One gate function decides this, so nothing else has to
know.

## D9 — Three identities, checked twice

A button press is accepted only when `callback_query.from.id` **and** `callback_query.message.chat.id`
both equal the configured owner, and the message is younger than the existing 6 h cap. On top of
that, the nonce must match the current ask.

**Cost:** a group-chat owner has to have the owner id saved (already required for text commands).
A second, older ask is invalid by construction, which is the point.

## D10 — Ack first, then act

`answerCallbackQuery` fires before the write, with no text: it only clears the client spinner. The
outcome arrives as a normal message.

**Cost:** the tap feedback is a message, not a toast. Acceptable, and it leaves a record in the chat.

## D11 — What we still do not know, and how we will find out

| Unknown | Test | If it fails |
|---|---|---|
| The car accepts `VSTD_MODE_STS = 1` while locked and arms to 2 | 20 minutes on the car: park with sentry off, `/guard on`, watch the value | Mode degrades to Ask only, and the copy says so |
| The head unit grants location at all (`LocationSource.java:21-26` says this is unmeasured) | one drive with the info bar on | Home gate stays `UNKNOWN`; asking continues, silent auto-on needs a home set by hand |
| Whether the picture path still works right after a remote enable | send a test picture with the receipt | Alerts keep working, the guard is a drain only |
| Cost of a whole night in sentry | one overnight sample with `Signal.BATTERY` in the heartbeat | add an hours cap, default 10 h |
