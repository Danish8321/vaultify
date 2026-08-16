# Cryptum design language

## Thesis

**Make the invisible visible.**

Every vault app hides its cryptography and asks to be trusted. Cryptum shows
it. The interface's job is to make the security model legible: what is sealed,
what is open, what the server can see, and what was destroyed.

This is not decoration. It is derived from three things that are already true
of this system and are currently invisible to the user:

| True of the system | Currently invisible | How the UI shows it |
|---|---|---|
| Each Item has its own DEK (ADR-0006) | User sees one undifferentiated list | Each Item carries its own visible seal and key identity |
| Reads unwrap a DEK server-side (ADR-0002) | Happens silently | An opened Item is visibly, temporarily open, and re-seals |
| Deletion destroys keys, not rows (ADR-0003) | A row slides away, as in any app | Deletion is shown as key destruction |

If a screen cannot be justified against something true about the architecture,
it is decoration and should be cut.

## The core metaphor: sealed and open

An Item is in exactly one of two states, and the state is never ambiguous.

**Sealed** is the resting state. Content is not merely hidden behind a "show"
toggle — it is rendered as a solid, opaque mass. The user sees that something
is there and that it is closed. Sealed is visually *heavy*.

**Open** is temporary and always visibly temporary. An open Item shows how long
it has been open and re-seals on a timer, on backgrounding, and on screen-off.
Open is visually *lighter* than sealed — opening is a removal of mass, not the
addition of a highlight.

Rules:

- Opening requires a sustained gesture (press and hold), never a tap. A tap is
  reversible by accident; revealing a credential in public should not be.
- The hold has a visible progress, and releasing early re-seals with no
  penalty. The gesture is the shoulder-surf control, so it must feel
  deliberate rather than fiddly.
- Nothing auto-opens. Not on navigation, not on search match, not on deep link.

## What the user is told, and when

Legibility has a limit: the user must never be *required* to understand
cryptography to use the app. The rule is that key detail is **available and
glanceable, never mandatory**.

- The list shows seal state and a short key identity. Nothing else technical.
- The detail view shows, secondarily: which key version protects this Item,
  when it was last opened, and how long until it re-seals.
- Audit and key history live one level deeper, for the user who wants it.

A first-time user who ignores every technical affordance must still be able to
store and read a password without confusion.

## Deletion

Account deletion is the moment the architecture is most honest and most apps
are least. It gets a dedicated screen, and the screen tells the truth as
ADR-0003 states it: the keys are destroyed, the ciphertext may persist in
backups, and it is unreadable regardless.

Deletion is animated as destruction of the key, not as removal of a row. The
one place a heavier motion treatment is justified — it is the only irreversible
action in the product.

## Motion

Motion carries state, never delight.

- Sealing and opening is the only motion with real weight (200–300ms, eased).
- Everything else is fast and unobtrusive (≤120ms) or absent.
- No parallax, no springy overshoot, no celebratory animation. A vault that
  bounces reads as a toy, and this app is asking to be trusted with the user's
  entire credential set.
- Reduced-motion settings collapse all of the above to instant state changes.
  The seal state must remain readable without any animation at all — motion is
  reinforcement, never the only signal.

## Colour and form

- Seal state must be distinguishable **without colour**: mass, texture and
  typography carry it. Colour is reinforcement. This is an accessibility
  requirement and a robustness one — the state has to survive a bad screen in
  sunlight.
- One accent colour, used only for the open state and for destructive
  confirmation. If everything is accented, the one moment that matters is not.
- Dark by default; light theme fully supported, not an afterthought.

## Explicitly forbidden

These are the tells of a generic build, and they are out:

- A stock Material list of `ListItem`s with a leading icon and a chevron.
- Card-with-shadow-on-white as the default container.
- An emoji or a coloured circle standing in for an item icon.
- A gradient hero header.
- A floating action button as the primary create affordance where a more
  specific one fits the metaphor.
- Lorem-ipsum-shaped empty states ("Nothing here yet!").
- Any screen that exists because apps usually have it (onboarding carousel,
  settings page full of toggles nobody asked for).

## Non-negotiables inherited from security requirements

These are not stylistic and cannot be traded for aesthetics:

- `FLAG_SECURE` on every screen that can render plaintext — no screenshots, no
  recents-screen thumbnail (task 2.13).
- The app lock gate (task 2.12) is unavoidable, on open and on resume.
- Plaintext is never written to disk, including as a draft or a saved
  scroll/state bundle.
- The list shows titles in plaintext. This is a documented, accepted trade
  (security-requirements) — the UI must not imply titles are encrypted.
