# Plan — sync Android Compose UI to claude.ai/design prototype

Source: "Cryptum Android Prototype.dc.html", project `0143bbbc-3787-4f3f-aac8-9de8f1776e50`.
Full prototype markup/logic already captured in this session's transcript (dc.html + styles.css).
Verification: `cd android && ./gradlew --console=plain -q test` (JVM unit tests) plus
`./gradlew --console=plain -q compileDebugKotlin` (or `assembleDebug` where a task touches
`app`/`feature-*` main sources) per task. `check.sh` is .NET-only and does not cover Android.

Naming decision (resolved): keep `Seal` semantic token names (`Ground`, `Mass`, `Ink`, `InkDim`,
`Open`, …), update hex values, add missing tokens (`InkDim` maps to the prototype's `secondary`
role directly — no separate `Secondary` token, `Tertiary`, `CardBg`, `ToastBg`, `Divider`,
`AccentDim`) and a `SealLight` counterpart — no renaming to `bg/ink/…`.

## Task 1 — `SealTheme.kt`: retoken + light theme
File: `android/feature-lock/src/main/kotlin/com/cryptum/lock/SealTheme.kt`
- Update dark values: `Ground=#171515`, `Mass=#3a3737` (was `Grain`/`Mass` swap — keep `Grain` as a
  distinct texture tone slightly off `Mass`, e.g. derive from existing grain-vs-mass contrast ratio
  applied to new base), `Ink=#F3F2F2`, add `Secondary=#8F8B8B`, `Tertiary=#605D5D`,
  `Divider=Color(0x1FF3F2F2)` (12% alpha), `Open`(accent)=`#FF563C`, `AccentDim=#C94B39`,
  `CardBg=#211F1F`, `ToastBg=#211F1F`.
- Add `object SealLight` mirroring the same property names: `Ground=#F3F2F2`, `Mass=#D7D3D3`,
  `Ink=#201E1D`, `Secondary=#605D5D`, `Tertiary=#7D7979`, `Divider=Color(0x29201E1D)` (16% alpha),
  `Open=#EC3013`, `AccentDim=#AE1800`, `CardBg=#FFFFFF`, `ToastBg=#EAE9E9`.
- Set `HoldToOpenMillis = 800` (was 600) — matches prototype; no design-language.md conflict (doc
  only specifies the *sealing/opening* 200–300ms cross-fade, not the hold duration).
- Keep `SealTransitionMillis = 260` (already within doc's 200–300ms range — no change needed).
- Add `object SealRadius { val Button = 26.dp; val Card = 18.dp; val ChipLarge = 16.dp;
  val ChipMedium = 9.dp; val ChipSmall = 6.dp }`.
- Do not touch `Gutter`, `TitleSize`, `TitleTracking`, `BodySize`, `CaptionSize` — unaffected.
Verify: `./gradlew --console=plain -q compileDebugKotlin` (feature-lock compiles); grep confirms no
remaining old hex values (`0B0C0E`, `1A1D22`, `23272E`, `E6E8EC`, `7C838F`, `4ADE9B`) in the file.

## Task 2 — Seal chip glyph system
File: `android/feature-lock/src/main/kotlin/com/cryptum/lock/SealChip.kt` (new)
- Port `hashGlyph(id: String): Pair<String,String>` (2-char hex halves of a simple string hash,
  deterministic — mirror the prototype's `h = h*31 + charCode` rolling hash, `abs`, 8-hex-digit
  pad, first two / last two chars).
- Composable `SealChip(id: String, state: SealState, size: Dp, modifier: Modifier = Modifier)`
  where `SealState` is a new sealed/enum type: `Sealed, Unwrapping, Open, Failed, Shredding`.
  Render box at `SealRadius.ChipLarge/Medium/Small` keyed off `size >= 40.dp / >= 24.dp / else`,
  background/border per state matching prototype's `buildSeal` (sealed=`Mass` fill + `Divider`
  border; unwrapping=`Mass` fill + rotating accent border stroke, 900ms linear infinite — use
  `rememberInfiniteTransition`; open=transparent fill + accent border + accent glow shadow;
  failed=transparent + dashed `AccentDim` border, glyph replaced with `—`/`—`; shredding=accent
  fill, alpha+scale dissolve over 1200ms). Glyph text only rendered when `size != 16.dp`.
- This is a leaf component with no I/O — export `SealState` from this file since `VaultRepository`
  et al. will need it in later tasks.
Verify: `./gradlew --console=plain -q compileDebugKotlin :feature-lock:compileDebugKotlin`; add
`SealChipTest.kt` under `feature-lock/src/test` asserting `hashGlyph("itm-001")` is deterministic
and stable across two calls; run via `./gradlew --console=plain -q :feature-lock:test`.

## Task 3 — Lock screen: PIN grid + biometric toggle
Files: `android/feature-lock/src/main/kotlin/com/cryptum/lock/LockGate.kt` (edit),
new `PinPad.kt` in same package.
- Extend `SealScreen` (or split into `SealScreen`/`LockScreen`) to support two modes per prototype:
  biometric (existing hold-to-open fingerprint affordance — reuse `SealMass`/hold gesture already
  present, restyle colors to new tokens) and PIN (new `PinPad` composable: 3×4 button grid,
  6-slot dot row above showing filled/unfilled per `pin.size`, digits 1–9/0/⌫, empty gap where
  prototype has a blank cell). Add `togglePin()` state + "Use fingerprint instead" /
  "Use PIN instead" toggle text row, both monospace caption per prototype copy.
- Keep existing `LockGate` gate semantics (content only renders unlocked) — do not weaken the
  "real gate, not overlay" guarantee documented in the existing docstring.
- Retint `SealMass`/text colors from `Seal.Mass`/`Seal.Ink`/etc (values already updated by Task 1,
  so this task is markup/structure, not new hex values).
- `BiometricGate.kt`: no change needed — the PIN path is a separate in-app affordance,
  `promptToUnlock` remains the platform biometric entry point wired to the biometric-mode button.
Verify: `./gradlew --console=plain -q :feature-lock:test`; existing `AppLockTest.kt` still green
(state machine untouched); manual check against docs/design-language.md "Explicitly forbidden"
list — no stock Material `ListItem`, no FAB for pin digits (plain composable buttons only).

## Task 4 — Vault list restyle
File: `android/feature-vault/src/main/kotlin/com/cryptum/vault/VaultScreen.kt` (edit `SecretList`)
- Add per-row `SealChip(item.id, state=Sealed, size=16.dp)` leading element (currently no chip at
  all — plain text row).
- Row background `Seal.CardBg` at `SealRadius.Card` corner radius (prototype rows are pill/rounded
  cards, not flush blocks) — this is a deliberate departure from the current flush-block rows;
  confirm still reads as "no cards" per design-language's forbidden list by keeping shadow at zero
  (no `elevation`/`shadow` modifier) — flat fill only, matching prototype's `cardBg` without the
  `.elev-*` shadow classes.
- Add monospace hint-line under title (prototype's `item.hintLine`) — needs a hint field on
  `SecretSummary`; if absent on the domain type, extend `SecretSummary` in
  `feature-vault/.../VaultRepository.kt` or wherever it's declared (check before editing) and thread
  through `VaultRepository.list()`. Do not touch `core-*` modules for this.
- Keep existing `HoldToOpen` gesture wrapper as-is (already correct interaction model).
Verify: `./gradlew --console=plain -q :feature-vault:test`; existing vault tests (check for
`VaultScreenTest.kt` under androidTest — instrumented, not run by test-fast.sh, note but don't
block on it) still compile.

## Task 5 — Item detail: hold-to-unwrap, reseal countdown, offline failure
File: `android/feature-vault/src/main/kotlin/com/cryptum/vault/VaultScreen.kt` (edit
`OpenedSecret`), new state additions to `Screen.Open` or a small view-state holder.
- Replace ad-hoc `revealed: Boolean` with `SealState` per field (or per-screen — prototype reveals
  whole item at once, not per-field: simplify to one `SealState` for the opened secret, matching
  prototype's single `sealState` per item rather than the current code's separate per-field
  boolean). This is a behavior change from today's code (today reveals password only, leaves
  username/url always visible) — new behavior: username/url/password all covered until the single
  hold-to-unwrap fires, consistent with prototype and with design-language's "nothing auto-opens"
  rule applying to the whole Secret, not just the password.
- Wire `SealChip` at 40dp at the top of the screen next to title.
- Add reseal countdown: on `Open`, start a 45s countdown (`Seal` gains
  `AutoResealSeconds = 45`), rendered as a thin progress bar (already have `HoldToOpen`'s progress
  bar pattern to reuse); on expiry, auto-transition back to `Sealed`/covered — call the existing
  `onClose`-style reseal, not full screen close (stay on detail screen, fields re-cover).
- Add "Reseal now" manual button, visible only while `Open`.
- Add failed state: if repository read throws/returns failure, show `SealState.Failed` chip +
  offline-style copy ("Can't reach the key service…") — needs `VaultRepository.read` to expose a
  failure path; check current signature (`repository.read(id)` in `VaultScreen.kt:120`) before
  deciding whether this is a `Result<SecretPayload>` change (cross-tier — coordinate with
  `core-api`/`VaultRepository.kt` only if the current signature can't express failure; if it
  already throws, catch at the call site instead of touching the repository contract).
Verify: `./gradlew --console=plain -q :feature-vault:test`; new unit test for the 45s countdown
using a fake clock/`kotlinx.coroutines.test` `runTest` + `advanceTimeBy`, asserting auto-reseal
fires and does not fire early.

## Task 6 — Activity log screen
File: `android/feature-vault/src/main/kotlin/com/cryptum/vault/ActivityScreen.kt` (new),
domain type `android/feature-vault/src/main/kotlin/com/cryptum/vault/ActivityEntry.kt` (new)
- `ActivityEntry(group: String, time: String, title: String, verb: String, failed: Boolean,
  anomaly: Boolean)`. Data source: for this task, accept a `List<ActivityEntry>` as a screen
  parameter (no new persistence/API — if there's no backing audit-log endpoint yet, wire this
  screen to take data from `VaultRepository` only if such a method already exists; otherwise leave
  the screen taking an explicit list and note in the task's PR/commit that wiring to a real audit
  source is out of scope — do NOT invent a fake network call).
- Render grouped-by-day header rows, each entry with `SealChip(24.dp)`, red-left-border + accent
  `triangle-alert`-equivalent (use a simple drawn triangle or an existing icon set already in the
  app — check `libs.versions.toml`/existing deps before adding an icon library; if none present,
  draw a minimal triangle glyph rather than pulling in a new dependency) when `anomaly == true`.
Verify: `./gradlew --console=plain -q :feature-vault:compileDebugKotlin`; unit test grouping logic
(given entries out of day-order... actually keep input pre-grouped per prototype, just test that
`showHeader` is computed correctly for consecutive same-group entries).

## Task 7 — Settings screen
File: `android/feature-vault/src/main/kotlin/com/cryptum/vault/SettingsScreen.kt` (new)
- Rows: "Auto-reseal window" → `Seal.AutoResealSeconds`s label, "Clipboard clear" → 30s (add
  `Seal.ClipboardClearSeconds = 30` constant), "Appearance" → current theme label, "Delete account"
  row in accent color with chevron, tapping navigates to Task 8's screen.
- Each row `Seal.CardBg` background, `SealRadius.Card` corners, per prototype — read-only display
  for auto-reseal/clipboard-clear in this task (no settings persistence/editing — that's a further
  slice if wanted; flag this scope cut explicitly rather than silently building a fake toggle).
Verify: `./gradlew --console=plain -q :feature-vault:compileDebugKotlin`.

## Task 8 — Delete account screen + confirm gate
File: `android/feature-vault/src/main/kotlin/com/cryptum/vault/DeleteAccountScreen.kt` (new)
- Reuse copy verbatim from prototype: "Delete your vault." / "This destroys your key. Every item
  becomes permanently unreadable — by you, by us, by anyone holding a backup. There is no
  recovery, no grace period, and no support ticket that undoes it." — matches
  docs/design-language.md's Deletion section almost word for word; keep consistent with that doc,
  don't rephrase.
- Text field bound to typed confirmation string; primary "Delete permanently" button disabled
  unless input == "DELETE" (case-sensitive, matches prototype).
- On confirm, this task only invokes a caller-supplied `onConfirmDelete: () -> Unit` callback — do
  NOT wire actual account-deletion API call here unless `core-api`/`VaultRepository` already
  exposes one; check before assuming. If no deletion endpoint exists yet, note that as a follow-up
  ticket in `.scratch/cryptum/issues/`, don't fabricate a call.
Verify: `./gradlew --console=plain -q :feature-vault:test`; unit test button-enabled state as a
function of typed text.

## Task 9 — Shredding transition screen
File: `android/feature-vault/src/main/kotlin/com/cryptum/vault/DeleteAccountScreen.kt` (same file,
add `ShreddingScreen` composable) or new `ShreddingScreen.kt` if the file is getting long — task
executor's call based on resulting file size.
- Transient screen: accent-filled square that dissolves (alpha 1→0, scale 1→0.8) over ~1200ms with
  an easing curve, "Destroying key…" pulsing caption. Shown for a fixed duration then the caller
  navigates away (caller-driven, this composable just renders the animation given `onFinished`
  fired via `LaunchedEffect(Unit) { delay(1200); onFinished() }`).
- This is docs/design-language.md's explicitly-sanctioned "one place a heavier motion treatment is
  justified" — the only screen allowed real celebratory-adjacent motion; keep it that way and
  don't add motion elsewhere in this slice.
Verify: `./gradlew --console=plain -q :feature-vault:compileDebugKotlin`; unit test that
`onFinished` fires once after the delay using `runTest`/`advanceTimeBy`.

## Task 10 — Add/Edit item screen restyle
File: `android/feature-vault/src/main/kotlin/com/cryptum/vault/VaultScreen.kt`
(`ComposeSecret` → generalize to also handle edit) or split into
`AddEditSecretScreen.kt` if cleaner — task executor's call.
- Add edit mode (currently only add/"seal something new" exists — no edit path at all today).
  Needs an `onSave` variant that calls `repository.update(id, payload)` if such a method exists on
  `VaultRepository`; if not, this task stops at UI + a TODO callback and files a follow-up ticket
  for the missing repository method — don't invent persistence.
- Title field gets caption: "Titles aren't encrypted — they're how your vault gets listed. Keep
  them recognisable, not revealing." (verbatim, matches design-language's accepted trade-off note).
- Edit mode adds caption: "This replaces the stored value. The old one isn't recoverable."
- Retint fields/button to `SealRadius.Button` pill shape, `Seal.CardBg` field backgrounds (replace
  current default `TextFieldDefaults` Material look — check against "Explicitly forbidden": no
  stock Material text field chrome, style via `TextFieldDefaults.colors` overrides as already done,
  extend with shape override too since Material's default field shape is not in the design system).
Verify: `./gradlew --console=plain -q :feature-vault:test`.

## Task 11 — FLAG_SECURE + lock-gate audit pass
No new files — audit existing `MainActivity.kt` / wherever screens are hosted.
- Confirm `FLAG_SECURE` is actually set (docs/design-language.md non-negotiable, task 2.13 in
  original ticket numbering) and that it covers every new plaintext-rendering screen from Tasks
  5–10 (Item Detail, Add/Edit). If `FLAG_SECURE` is set at the Activity level already, this task
  is verification-only (grep + read `MainActivity.kt`); if per-screen, ensure new composables sit
  behind the existing lock-gate/secure boundary, don't create a bypass route.
- Confirm the app lock gate re-triggers on resume for the new screens too (reuses existing
  `ReLockOnBackground`/`AppLock` — should be automatic since it's Activity/nav-level, but verify).
Verify: read-and-confirm, no build change expected unless a gap is found; if a gap is found, fix +
`./gradlew --console=plain -q :app:compileDebugKotlin`.

## Task 12 — Final full-repo verification
- `cd android && ./gradlew --console=plain -q test` (all JVM unit tests, all modules)
- `./gradlew --console=plain -q build` (full assemble across app + feature modules)
- Re-check every new/changed screen against docs/design-language.md "Explicitly forbidden" list
  one final time as a single pass (no stock `ListItem`, no card-with-shadow-on-white default, no
  emoji/colored-circle icons, no gradient hero, no FAB where a specific affordance fits, no
  lorem-ipsum empty states, no unjustified extra screens).
- Update `.scratch/cryptum/issues/` with any follow-up tickets filed during Tasks 5/8/10 (missing
  repository methods for read-failure, account deletion, edit — if applicable).
