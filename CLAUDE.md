# SPLITSTAK

Single-file vanilla HTML/CSS/JS workout tracker. Live at https://splitstak.com.

## Tech stack

- Single `index.html` for the app. No build step. No frameworks, no jQuery, no libraries.
- Vanilla JS only.
- External dependencies: Google Fonts only (Bebas Neue, JetBrains Mono, Inter).
- Data persists via `localStorage` on the user's device. No backend, no accounts, no cloud sync.
- localStorage key: `liftlog_data_v2` (kept as-is post-rebrand — internal plumbing only, never user-visible). Avoiding migration risk on a working storage key is a deliberate choice.
- v1 migration via `migrateV1()` must keep working.
- The PWA web layer remains single-file no-build per the rules above. A separate **native Android** build exists under `mobile/android/` (Gradle) and a separate **push backend** lives under `push-worker/` (Cloudflare Worker). Both are documented in their own sections at the bottom of this file; they do not change the rules for editing `index.html`.

## Hosting / deploy

- Repo: `splitstak/SPLITSTAK` on GitHub, owned by the `jacobsplitstak` account (primary email jacob@splitstak.com).
- Hosted on Cloudflare Workers Static Assets via the `wrangler.toml` at repo root (`[assets] directory = "./"`).
- Pushes to `main` auto-deploy to https://splitstak.com in ~30–60 seconds.
- Custom domain at Cloudflare with auto-provisioned HTTPS. `www.splitstak.com` redirects to apex via a Cloudflare Redirect Rule.
- Email jacob@splitstak.com via Fastmail (separate from hosting; MX/SPF/DKIM in Cloudflare DNS).
- Repo files: `index.html`, `manifest.json`, `sw.js`, `privacy.html`, `icon-192.png`, `icon-512.png`, `icon-512-maskable.png`, `favicon.ico`, `wrangler.toml`, `README.md`, `CLAUDE.md`.
- Repo subdirectories: `mobile/` (Capacitor native Android — see Native Android section), `push-worker/` (Cloudflare Worker for Web Push — see Push notifications section).
- PWA support files (manifest, sw, icons) are separate files at repo root by browser requirement, but the app code itself stays in `index.html`.
- `privacy.html` is the Play Store-required privacy policy at `https://splitstak.com/privacy.html`. Keep it in sync with what the app actually does — particularly the local-only data claim. Linked from a footer in the INFO tab.

## Workflow rules

1. **SURGICAL EDITS ONLY.** Modify only what is explicitly requested. Do not refactor, "improve," or change unrelated code. Do not regenerate the file from memory.
2. **Always work from the latest version of `index.html`.** Read it before any change. Never assume features exist or don't exist based on conversation memory — verify against the actual file.
3. **Before editing, list the features that could be affected** so the user can confirm nothing will be lost. If a change might affect other features, name them and confirm before proceeding.
4. **After every edit, verify both:**
   - Syntax check via Node (`new Function(scriptContent)` or equivalent).
   - Feature-presence audit: grep for the markers below. All must remain present.
5. **Bug reports → read the relevant code paths first, then propose a minimal fix targeting the exact symptom.** No speculation. No "while I'm here" improvements.
6. **Direct communication.** No filler. State the change, make it, verify it. If uncertain, say so plainly.

## Required feature markers (must remain present after every edit)

```
liftlog_data_v2, migrateV1, customConfirm, customAlert, customPrompt,
pendingDayAssignment, confirmDayAssignment, pickDefaultDay, finishDay,
data-btn.primary, settings-row.conflict, pick-needed, floatingKeys,
hasUnassignedDay, TUTORIAL_TOPICS, tutorialTopicSelect, tutorialBody,
CHANGELOG, changelogDateSelect, changelogBody, renderChangelogEntry,
DAY_FULL_NAMES, ⚠ DATA WARNING, DATA WIPE, SETTINGS, BACKUP & RESTORE,
DAY ASSIGNMENT, FOR BEGINNERS, stateToCSV, csvToState, csvParse,
setupScrollHidableTabs, tabs-hidden, top-tabs-main, top-tabs-day,
<link rel="manifest", navigator.serviceWorker.register,
<meta name="theme-color", getSuggestedRestSec, schedulePushBeeps,
stopTimerAudio, window.Capacitor, privacy.html
```

## Change Log protocol — every push gets a new entry

The CHANGE LOG card in the INFO tab is driven by a `CHANGELOG` array inside `renderInfo`. The array is ordered newest-first. The dropdown defaults to entry `[0]`.

**Before any changelog edit, check today's actual date.** Don't assume the existing top entry is today's. Run `date` in a shell, check the env, or ask. Especially watch out for sessions that have crossed midnight in the user's timezone.

Every push of `index.html` with user-visible changes:

1. Determine today's date (YYYY-MM-DD in the user's local timezone).
2. Look at `CHANGELOG[0].date`.
3. If they match → append your new bullet(s) to `CHANGELOG[0].changes`.
4. If they don't match → prepend a NEW entry to the front of the array.

Rules for changelog entries:

- One entry per date that includes a push.
- Newest entry is always at index 0. The dropdown auto-defaults to it.
- Plain language. "Sunday's full rest day no longer shows a cardio row" — not "Removed cardio_sun from DEFAULT_WORKOUTS.rest_sun.exercises."
- If a change re-styles or relocates UI, name the visible label or button text the user will see, not the CSS class or DOM id.
- Internal-only refactors (no user-visible behavior change) do NOT get a changelog entry.
- Bullets are complete sentences ending in a period.

## Aesthetic — set in stone, do not change without explicit request

Dark, hard, factual. Single accent color: orange `#ff5722`. No emojis except `✓ ○ → ⚠` in their existing contexts. No gradients beyond the warning section's existing accent-dim → surface fade. No light mode. No rounded corners on buttons or inputs (`border-radius: 0`).

ALL CARD HEADERS (`h3` inside `.info-section` and `.data-section`, plus the "DAY ASSIGNMENT" sub-header div) ARE FULLY UPPERCASE in source. Do not use mixed case for card headers. Sub-section content paragraphs and prose use sentence case.

### Color palette (CSS variables — these are the ONLY colors)

```
--bg: #0a0a0a            page bg, primary button text
--surface: #161616       card/section bg, exercise rows, modals
--surface-2: #1f1f1f     nested/expanded surfaces, disabled bg, toggle off track
--border: #2a2a2a        default 1px border, dividers, dashed dividers
--text: #f5f5f5          primary text
--text-dim: #888         secondary/muted text, history entries
--text-faint: #555       tertiary, hints, set numbers when not active
--accent: #ff5722        ORANGE — single accent for emphasis, focus, CTAs, conflicts, delete-exercise button
--accent-dim: #4a1a08    dark orange tint — input focus bg, autocomplete hover, conflict row bg
--success: #4ade80       only for the Auto-saved indicator and timer-done state
#ff4444                  one-off red for delete-exercise hover and danger-button hover
```

NEVER add new colors without explicit approval.

### Typography

- **Bebas Neue** — display headings: logo, day titles, page titles, top tabs, REST text, timer value, PR title, modal H3, day-dropdown selects, set numbers, finish-day button label, all card-header h3s.
- **JetBrains Mono** — all numeric data, dates, history, small UI labels, save indicator, toast, settings labels, button text, set inputs, autocomplete target hints.
- **Inter** — body prose: exercise names, modal copy, info paragraphs, ack copy, settings labels-as-prose.

Never load a font outside this set.

### Buttons (hierarchy)

- **Primary CTA (orange filled).** Classes: `.data-btn.primary` for the DATA + SETNG tab, `.modal-btn.primary` for modals, `.finish-day` for SAVE DAY. Spec: `background var(--accent)`, `border var(--accent)`, `color var(--bg)`, `font-weight 700`. Hover: `opacity 0.9`, no color flip.
- **Secondary outline (default for DATA buttons, history toggles).** `background var(--bg)` or transparent, `border var(--border)`, `color var(--text)` or `var(--text-dim)`. Hover: `border var(--accent)`, `color var(--accent)`.
- **Delete-exercise.** `.delete-exercise` outline shape, `border` AND `color` both `var(--accent)`. Hover flips to `#ff4444`.
- **Danger.** `.data-btn.danger` outline shape, `color var(--text-faint)`. Hover flips to `#ff4444`.

### Inputs and selects

All inputs/selects: `background var(--bg)`, `1px var(--border)`, `border-radius 0`, JetBrains Mono. Focus: `border var(--accent)`, `background var(--accent-dim)`. `-webkit-appearance: none` on selects with custom orange caret SVG.

`.day-dropdown` is the big Bebas Neue select used for day-view picking, the tutorial topic picker, and the changelog date picker.

`.day-assign option.pick-needed` colors only the floating workout(s) orange in Day Assignment dropdowns; this only applies when at least one day is unassigned in pending state. When every day has a workout, no floating-key highlights show.

### Modals and popups

All overlays use `.modal-overlay` with `z-index 1001`; rest-timer popup `1002`; PR popup `1003`. `customPrompt` reuses the `confirmModal` element with a dynamically-injected text input.

### Iconography

Allowed: `✓ ○ → ⚠`. Nothing else. No SVG icons beyond the orange caret used for native select dropdowns. No emoji.

### Top tabs — two-row layout with scroll-hide

- `.top-tabs-main` — INFO, DATA + SETNG, REMINDERS on a single row.
- `.top-tabs-day` — day tab on its own row, centered, 22px Bebas Neue, full day name (MONDAY, TUESDAY, etc. via `DAY_FULL_NAMES` lookup).
- `#topTabs` is `position: sticky` with `z-index 90` (header is `z-index 100`).
- `.tabs-hidden` adds `transform: translateY(-110%)` to slide it up behind the header.
- `setupScrollHidableTabs()` watches scroll and toggles this class — hidden when scrolling DOWN past 40px, visible when scrolling UP or near the top.

## Custom modal system

Never use native `confirm()` / `alert()` / `prompt()` — they fail in artifact previews and PWA installs. Always use `customConfirm` / `customAlert` / `customPrompt`.

All buttons attach via `addEventListener` inside render functions. Existing inline `onclick="closeModal..."` and `onclick="completeAck..."` handlers may stay where they are — do not migrate them.

## Data model (state object, persisted to localStorage as `liftlog_data_v2`)

```js
state.workouts: { [dayKey]: { name, subtitle, day (MON/TUE/etc), rest?, restMessage?, exercises: [{id, name, target, isCardio?}] } }
state.exerciseData: { [exerciseId]: { sets: [{w,r,d}], history: [{date, iso, summary, topWeight?, topReps?}], cardio?: {time, distance} } }
state.currentTab: 'info' | 'data' | 'reminders' | 'day'
state.currentDay: dayKey for the day view
state.acknowledged: bool (data warning acknowledgment)
state.timerEnabled: bool
state.timerDuration: 60 | 90 | 120 (seconds)
```

Module-level (NOT persisted):

- `WEEK_ORDER: ['MON','TUE','WED','THU','FRI','SAT','SUN']`
- `DAY_FULL_NAMES: { MON: 'MONDAY', TUE: 'TUESDAY', ... }` — display lookup.
- `pendingDayAssignment: null | { MON: workoutKey|null, TUE: ... }` — tentative day-assignment edits awaiting CONFIRM. Never persisted; resets to `null` on reload.

## App structure

Two-row tab strip: top row INFO / DATA + SETNG / REMINDERS, second row centered current-day tab.

### INFO — exactly FOUR cards, in this order

1. ⚠ DATA WARNING (red-orange tinted, references CSV export/import).
2. FOR BEGINNERS — single card explaining the default split is followable as-is for newcomers.
3. TUTORIAL — single card with a `.day-dropdown` topic selector and a body div that swaps content. Topics live in the `TUTORIAL_TOPICS` object inside `renderInfo`: DAILY USE, EDITING EXERCISES, SAVE DAY BEHAVIOR, REORDERING WORKOUT DAYS, RESET TO DEFAULTS. Defaults to DAILY USE.
4. CHANGE LOG — single card with a `.day-dropdown` date selector and a body div listing that date's bullets. Dates live in the `CHANGELOG` array inside `renderInfo`, newest-first. Defaults to `CHANGELOG[0]`.

### DATA + SETNG cards (in order)

SETTINGS (Rest Timer + DAY ASSIGNMENT sub-section), ⚠ READ THIS, BACKUP & RESTORE, DATA WIPE.

### REMINDERS

Static list of training/diet reminders.

### Day view

Dropdown to select day (sorted MON–SUN, full day names), workout exercises, "+ Add Exercise" button, "SAVE DAY" button at bottom.

## Key behaviors

- App always opens with the day tab pointing at TODAY's matching workout (`init()` always sets `state.currentDay = pickDefaultDay()`), regardless of what was last toggled.
- SAVE DAY commits all exercises with logged sets to history. Re-saving the same day overwrites that day's history entry (matched by ISO date). Resets done-tickers but keeps weight/reps for continuity.
- LAST TOP shows highest weight × reps from history (e.g., "170 × 8").
- NEW PR popup fires automatically when a save beats previous best (higher weight, OR same weight at higher reps).
- Rest timer pops up when checking a set ✓ (not when unchecking). Counts down from selected duration. Auto-hides 3 sec after reaching 00:00.
- **Day Assignment uses INDEPENDENT picks (no auto-swap).** Picking a workout already on another day clears that other day. The cleared day's row gets `.settings-row.conflict` (orange border + `accent-dim` bg) until the user picks a workout for it.
- Floating workouts (those not assigned to any day in the pending state) get `.pick-needed` orange highlight on their dropdown options ONLY when at least one day is unassigned. The "— SELECT —" placeholder option also gets `.pick-needed` for unassigned days.
- Each Day Assignment dropdown has a "Custom..." option at the bottom. Picking it on a day with a workout RENAMES that workout. On an unassigned day, it CREATES a new workout with the typed name and assigns it.
- Day Assignment dropdown options show ONLY the workout name (e.g., "PUSH"). They do not include "— on TUE" suffixes.
- CONFIRM button is `.data-btn.primary` (orange) and is disabled until `pendingDayAssignment` is dirty AND every day has a workout. Pressing CONFIRM commits to `state.workouts[k].day` and clears `pendingDayAssignment`. Navigating away from DATA + SETNG while `pendingDayAssignment` is non-null shows a `customAlert` ("CHANGES PENDING") and blocks navigation.
- `pendingDayAssignment` is cleared on Reset, Import, and CONFIRM. It is not persisted.
- Exercise names and targets are inline-editable via DOUBLE-click (single-click is reserved for opening/interacting with the row). Autocomplete suggests common lifts; selecting one auto-fills the rep range.
- Deleting an exercise also removes its `state.exerciseData[id]` entry — but only if no other workout still references that id. Defensive cleanup against orphan data accumulation.
- First launch shows acknowledgment modal that blocks navigation away from INFO until the user checks "I understand my data is local only." Modal copy references the CSV export/import flow.
- Existing migration in `loadState` strips deprecated `cardio_sun` from `rest_sun.exercises` for older saves. Same migration also rewrites any legacy "Muay Thai" subtitle on `rest_wed` to "Light Cardio". Both must keep working.
- The full rest day (`rest_sun`) has no cardio exercise; the active rest day (`rest_wed`) keeps its cardio.
- PWA: `index.html` links a `manifest.json` and registers a service worker (`sw.js`) for offline support and Add-to-Home-Screen install. Don't break the manifest link, the apple-touch-icon link, the theme-color meta tag, or the SW registration script.
- Top tabs slide up behind the SPLITSTAK header when scrolling down (via `.tabs-hidden` added by `setupScrollHidableTabs()`), and slide back when scrolling up or near the top of the page.
- **`stopTimerAudio()` ordering is load-bearing.** It MUST set `audioSession.type = 'auto'` BEFORE pausing any `<audio>` element and BEFORE tearing down the muted-video keep-alive / MediaStream. Pausing first re-enters music-ducking and pauses background Spotify/podcast playback. Bug history: caught in May 2026 — do not "tidy up" this function by reordering its cleanup.
- **SUGGESTED rest timer.** `getSuggestedRestSec(exercise)` classifies an exercise by name/target heuristics and returns 60 / 90 / 120 / 180 seconds. Wired into the rest timer when the user picks the SUGGESTED option in SETTINGS. Keep the classifier conservative — when in doubt, fall through to 90s. Do not surface the picked seconds as if it were an authored field on the exercise; it's recomputed each timer fire.
- **NOTES per exercise.** Each exercise row has a NOTES button that toggles a textarea bound to `state.exerciseData[id].notes`. Notes are included in CSV export/import and survive day re-saves. The textarea uses Inter (prose), not JetBrains Mono.
- **Native bridge bailout.** When `window.Capacitor` is truthy, the app is running inside the Capacitor WebView, not a regular browser. `schedulePushBeeps` MUST short-circuit to `window.Capacitor.Plugins.SplitstakWidget.scheduleRestComplete` instead of calling the Cloudflare Web Push backend — native uses AlarmManager for lock-screen alerts. The Android install-nudge banner (when added) must also hide when `window.Capacitor` is truthy.

## Backup / restore

CSV-based: `stateToCSV` / `csvToState` / `csvParse`. Multi-section CSV: `STATE`, `WORKOUTS`, `EXERCISES`, `SETS`, `HISTORY`, `CARDIO_LOG`. Export downloads a file via `Blob`; Import opens a file picker.

CSV is also the **manual data bridge** between the browser PWA at splitstak.com and the Capacitor native app, because Android's WebView storage is sandboxed per app — even though both load the same origin, the native app cannot read the browser's `localStorage`. Treat CSV as a load-bearing surface, not a debug feature: keep `stateToCSV` and `csvToState` round-trippable across every state field you add.

## Native Android (Capacitor)

The repo produces TWO Android app bundles (AABs) from `mobile/android/`:

- **Phone**: `mobile/android/app/build.gradle` → `mobile/android/app/build/outputs/bundle/release/app-release.aab`
- **Wear OS**: `mobile/android/wear/build.gradle` → `mobile/android/wear/build/outputs/bundle/release/wear-release.aab`

Both share `applicationId "com.splitstak.app"` and ship under the same Play Store listing (`play.google.com/store/apps/details?id=com.splitstak.app`). Play routes the right AAB to the right device class via the wear AAB's manifest `<uses-feature android:name="android.hardware.type.watch" />`. The wear module's Kotlin namespace is `com.splitstak.app.wear`; the `applicationId` is what Play cares about, not the namespace.

Build both at once:
```
cd mobile/android
./gradlew bundleRelease
```

Or per-module: `./gradlew :app:bundleRelease` / `./gradlew :wear:bundleRelease`.

### Capacitor strategy (do not change without explicit request)

`mobile/capacitor.config.ts` sets `server.url='https://splitstak.com'` so the native shell loads the live site instead of bundled HTML. Benefit: pushes to `main` auto-propagate to installed users with no APK rebuild — web iteration stays a `git push`. Cost: `localStorage` inside the Capacitor WebView is sandboxed at the OS level (separate `/data/data/com.splitstak.app/app_webview/Local Storage/` directory), so the native app does NOT share storage with the user's Chrome PWA at the same origin. CSV export/import is the migration path; see Backup / restore.

`window.Capacitor` is the gate for "am I running inside the native shell." Existing usages:

- `schedulePushBeeps` bails to the native widget plugin (see Key behaviors).
- The Android install banner (when present) hides itself.

If you add code that conditionally targets native, gate it on `window.Capacitor`, not on user-agent strings.

### Version code convention

Phone and wear AABs MUST have **distinct** `versionCode` integers per `applicationId`. Play rejects duplicates. Convention going forward:

- `versionCode`: monotonically increasing, **wear strictly greater than phone in the same release**. Bump both for every release even if only one bundle changed source, so the pair stays distinguishable in Play Console's bundle catalog.
- `versionName`: identical string on both for the same release (`"1.2.3"` etc).
- Edit BOTH `app/build.gradle` AND `wear/build.gradle` before rebuilding. The AAB on disk has the versionCode baked in at build time — re-uploading without rebuilding ships the old number and Play rejects it.

Sanity check before every upload: Play Console's upload dialog parses and displays the versionCode after you pick the file. Confirm it matches what you just set in source.

### Signing

`mobile/android/keystore.properties` is git-ignored and exists only on signing machines. It holds `storeFile`, `storePassword`, `keyAlias`, `keyPassword` for the upload key Play has registered for this app. Both `app/build.gradle` AND `wear/build.gradle` must:

1. Load `keystoreProperties` at the top of the file from `rootProject.file("keystore.properties")`.
2. Define `signingConfigs.release` reading from `keystoreProperties` inside an `if (keystorePropertiesFile.exists())` guard.
3. Wire it into `buildTypes.release` via `if (keystorePropertiesFile.exists()) { signingConfig signingConfigs.release }`.

All three pieces must be present per module. Missing step 3 produces an unsigned AAB that Play rejects as "All uploaded bundles must be signed." This was the bug that wasted v14/v15 on the wear side — keep the wear module's signing block in sync with the phone module's.

NEVER commit `keystore.properties`. NEVER paste its contents into chat — if leaked, rotate the upload key and re-register it with Play before signing a new release.

### What NOT to use

- **`wearApp project(':wear')`** in the phone module. Google deprecated bundled Wear-in-phone-AAB delivery in Sept 2023 (mandatory), and AGP 9.0 removed the DSL entirely in Jan 2026. The phone `build.gradle` has a comment to that effect — do not re-add `wearApp` even if a tutorial or older Capacitor sample suggests it. Wear is a separate AAB to a separate Play track.
- **`targetSdk` below 35.** Play requires `targetSdk >= 35` as of 2025 for new releases. We ship 36 on both modules. Bump in `variables.gradle` if Google raises the floor.
- **`--no-verify`, `--no-gpg-sign`, or disabling pre-commit hooks** during a release build. If a hook fails, fix the cause.

### Distribution

- Play Store-only. Sideload-from-URL is being phased out under Google's developer-verification mandate (pilot Sept 2026, global 2027).
- The phone and wear AABs live on **separate Play Console tracks**: "Internal testing" (phone) and "Internal testing (Wear OS)" (wear). Each has its own testers list AND its own opt-in URL. Being on the phone tester list does NOT grant watch app access.
- For a tester to install the wear app, their Google account must be (a) on the **Wear OS** track's testers list, AND (b) signed into the watch's Play Store. The watch discovers the app via its own Play Store catalog (Apps → "On your other devices" or a direct search) — there is no phone-to-watch push.
- Promotion path: Internal testing → Closed testing (14-day mandatory wait before Production access becomes available, per Play's 2024 policy) → Production review (1–7 days).

## Push notifications backend (PWA-only)

`push-worker/` is a Cloudflare Worker deployed at `splitstak-push.jacoba1998.workers.dev`. Stack:

- Web Push via VAPID (keys configured as Worker secrets).
- Durable Objects schedule the rest-timer alerts (`/schedule`, `/complete`, `/unschedule` endpoints).
- Two notification bodies: `⚠ 10 seconds left` (~50s into a 60s timer) and `✓ Rest complete` (~60s).

`sw.js` handles the inbound `push` event, looks up the timer phase from IndexedDB, and shows the right body. When `sw.js` changes in a behavior-affecting way (push handler, cache name, etc.), bump the cache name (currently `splitstak-v4`) so existing clients pick up the new SW on the next visit.

The native Android app **does not use this backend**. It uses `AlarmManager` via the `SplitstakWidget` Capacitor plugin for lock-screen alerts. See the "Native bridge bailout" entry under Key behaviors.

Worker repo files (separate deploy from the main app):
- `push-worker/src/index.js` — entry, route handlers, Durable Object class.
- `push-worker/wrangler.toml` — bindings, secrets references.
