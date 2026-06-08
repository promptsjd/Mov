# Version Reconstruction Specification — MOV (v1)

> **Restoration Checkpoint.** This document describes every behavior, rule, and data structure that exists in the current implementation. A developer or AI reading this document alone should be able to recreate an application that behaves identically to the current one. Source code is not reproduced; behavior and architecture are described precisely enough to rewrite from scratch.

---

## Product Overview

MOV is an Android application that forces a brief physical exercise before a user can open a "blocked" app. When the user taps a blocked app (e.g., Instagram), instead of the app opening normally, a fullscreen black overlay appears. The overlay instructs the user to rotate their phone through a random sequence of orientations. Only after completing every rotation in the correct order does the blocked app open. If the user cancels, they are sent to the home screen and the app does not open.

The problem it solves: reflexive, mindless phone use. The phone rotation challenge requires deliberate hands movement, inserting enough friction to break the habit loop without permanently blocking access.

---

## Core Concept

### How App Blocking Works

The app uses Android's Accessibility Service API to monitor every window change on the device. When any app comes to the foreground, the service checks its package name against a persistent list of blocked apps. If the package name is on that list, and the app is not already being challenged (no overlay is showing), and this is not the same app that was just unlocked, the blocking sequence begins.

The service does not prevent the app from launching at the OS level. Instead it immediately places a fullscreen opaque overlay on top of everything using `TYPE_ACCESSIBILITY_OVERLAY`, which covers the app completely.

### How the Overlay Appears

The overlay is a custom `View` drawn directly onto the `WindowManager` with a black opaque background. It uses `TYPE_ACCESSIBILITY_OVERLAY` window type, `FLAG_FULLSCREEN`, `FLAG_LAYOUT_IN_SCREEN`, and `FLAG_LAYOUT_NO_LIMITS` flags. It covers the entire screen including system bars. No existing window is hidden or destroyed — the overlay simply sits on top.

### How Phone Rotation Is Used

The app reads the device's accelerometer at `SENSOR_DELAY_UI` rate. It uses only the X and Y axes. The raw values are smoothed using an exponential moving average (15% new value, 85% previous). The smoothed values are converted to degrees using the inverse sine of G-force divided by 9.8. These degree values are used to classify the phone into one of four discrete orientation states.

### How Moves Are Counted

Each challenge session has a pre-generated list of `SessionStep` objects. Each step specifies: the state the phone must currently be in (`from`), the user-facing instruction string (`turn.label`), and the destination state (`to`). A move is counted as correct only when the phone physically enters the exact target state. The counter advances by one for each correct move. Wrong moves do not decrement the counter — they simply leave the current instruction visible at full opacity.

### How Unlocking Works

After the correct number of moves is completed in sequence, the session is marked complete. The overlay displays "Opening [AppName]..." for exactly 3000 milliseconds. After that delay, the service launches the blocked app by calling `startActivity()` with the package's launch intent, then removes the overlay. The app then opens normally.

### How Sessions Work

A session begins the moment the phone reaches a stable orientation after the overlay appears (the first `onStateChanged` event after `PRE_SESSION` phase). The starting orientation, whatever it happens to be, becomes the "home state." The session generates a random path of moves that begins from the home state and ends back at the home state. The session ends either by completing all moves (success) or by the user tapping Cancel or pressing Back (cancellation).

### What Causes Success

Every step in the `SessionEngine.steps` list is completed in order. `sessionEngine.isComplete` returns true when `index >= steps.size`. This triggers `completeSession()`.

### What Causes Failure / Cancellation

- User taps the "Cancel" text area at the bottom of the overlay → overlay dismissed, `GLOBAL_ACTION_HOME` fired (returns to home screen).
- User presses the Back hardware/gesture button → overlay dismissed, blocked app does not open (no home action fired on back press, only the overlay is removed).

### Timing-Related Behavior

- After `completeSession()` fires, there is a fixed 3000 ms delay (`COMPLETION_DELAY_MS = 3000L`) before the app launches.
- The delay is implemented with `Handler(mainLooper).postDelayed`. If the overlay is dismissed during this window (e.g., a second accessibility event removes it), the launch is cancelled — `launchApp()` is only called if `overlayView != null` at the time the delayed runnable fires.

### Restrictions and Safeguards

- The system will not show a second overlay if one is already visible (`overlayView == null` check).
- System UI package names (`android`, `com.android.systemui`, `com.android.phone`) are filtered out from all blocking checks and from `prevRealPkg` tracking.
- The `prevRealPkg` variable tracks the last real app to prevent re-triggering the overlay when returning from the completed unlock (the launched app's package name is written to `prevRealPkg` just before launch).
- Devices without an accelerometer sensor display an incompatibility screen and the service cannot be used.

---

## User Flow Documentation

### First Launch

1. User installs and opens MOV.
2. `MainActivity.onCreate()` runs.
3. The system checks for `Sensor.TYPE_ACCELEROMETER`.
4. If no accelerometer: a plain text screen is shown reading "This device doesn't have the sensors required to run MOV." The activity shows nothing else and cannot proceed.
5. If accelerometer present: `activity_main.xml` is inflated.
6. The screen shows: app title "MOV", label "Accessibility Service", and status "Not enabled" (gray).
7. Below the status, "Enable in Settings →" link is visible (blue).

### Permissions / Service Enablement

1. User taps "Enable in Settings →".
2. `startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))` fires, opening the system Accessibility Settings screen.
3. The user manually enables MOV's accessibility service.
4. User navigates back to the MOV app.
5. `MainActivity.onResume()` fires.
6. `isServiceEnabled()` polls `AccessibilityManager.getEnabledAccessibilityServiceList()` and finds the service.
7. Status text changes to "Active" (green, `#4CAF50`).
8. "Enable in Settings →" button becomes `GONE`.

### App Selection / Configuration

There is no UI screen for selecting blocked apps in the current implementation. The blocked apps list is stored in `SharedPreferences` and is modified only programmatically. The default list contains one entry: `"com.instagram.android"`. The complexity setting defaults to `MEDIUM` (8 moves). These settings cannot be changed by the user through any current UI.

### Attempting to Open a Blocked App

1. User taps a blocked app's launcher icon (or opens it any other way).
2. Android fires `TYPE_WINDOW_STATE_CHANGED` accessibility event.
3. `BlockerAccessibilityService.onAccessibilityEvent()` receives the event.
4. The service checks `event.packageName` is not null.
5. `isSystemUiPkg()` check — if it is a system package, the event is ignored entirely. `prevRealPkg` is not updated.
6. If not a system package, `prevRealPkg` is updated to this package.
7. The service checks whether the package is in `settings.blockedApps`.
8. The service checks `overlayView == null` (no overlay already showing).
9. The service checks `isNewOpen` (package differs from `prevRealPkg` before update, which was already updated above — note: the `isNewOpen` flag is computed using the value of `prevRealPkg` before this event's package is assigned to it).
10. If all conditions met: `showOverlay(pkg)` is called.

### Overlay Appears — Pre-Session Phase

1. `detector.reset()` clears sensor smoothing and state.
2. `phase = PRE_SESSION`, `sessionEngine = null`.
3. A new `OverlayView` is constructed.
4. The view is added to `WindowManager` with fullscreen opaque parameters.
5. The accelerometer listener is registered at `SENSOR_DELAY_UI`.
6. `MotionDetector` begins firing `onPosition()` on every sensor tick — overlay alpha starts responding to phone angle immediately.
7. The overlay shows a black screen with no instruction text yet (message is empty string). The "Cancel" text is visible at the bottom at 47% opacity.
8. Nothing happens until the phone reaches a stable orientation and `onStateChanged()` fires.

### Session Start

1. First `onStateChanged()` fires (phone is in any stable orientation — this becomes `homeState`).
2. `startSession(homeState)` is called.
3. `phase = ACTIVE`.
4. `SessionEngine(homeState, settings.complexity.moveCount)` is constructed — generates a random path of N steps ending back at homeState.
5. `loadInstruction()` is called — displays the first instruction text (e.g., "→ Turn right") in white at the appropriate rotation for the current phone orientation.
6. The overlay now shows a centered instruction text.

### Active Unlock Sequence

1. User reads the instruction and physically rotates the phone to the specified orientation.
2. `onStateChanged(newState, prevState)` fires when the phone crosses the zone boundary.
3. `handleStateChange(newState)` runs.
4. If `newState == sessionEngine.current.to` (correct move): `onCorrectMove()` called.
   - `sessionEngine.advance()` increments the index.
   - If `sessionEngine.isComplete` is false: `loadInstruction()` shows next instruction.
   - If `sessionEngine.isComplete` is true: `completeSession()` fires.
5. If `newState != sessionEngine.current.to` (wrong move): overlay text stays at full opacity (`lockAlpha = true`), same instruction remains.
6. The overlay continuously fades the instruction text based on current phone angle through `onPosition()` callbacks (except when `alphaLocked = true`).

### Session Completion

1. `phase = COMPLETE`.
2. Overlay displays "Opening [AppName]..." where AppName is fetched via `PackageManager.getApplicationLabel()`. Falls back to package name string if unavailable.
3. 3000 ms delay begins.
4. After delay, if overlay still exists: `prevRealPkg` is set to the launched package name (prevents re-trigger), `launchApp()` fires, `dismissOverlay()` fires.
5. The blocked app opens normally.

### Returning to Normal Usage

After `dismissOverlay()`:
- Overlay view is removed from `WindowManager`.
- Accelerometer listener is unregistered.
- `detector.reset()` is called.
- `currentPkg` is cleared to empty string.
- All service state variables return to idle values.
- The `prevRealPkg` retains the just-launched package name, so if the user immediately backs out and re-opens the same app, it will be blocked again (because the next open will differ from the current `prevRealPkg` only if the package changes, but the `isNewOpen` logic means back-to-same-app is actually allowed through — see Business Rules).

---

## Screen Documentation

### Screen 1: Main Screen (MainActivity)

**Purpose:** Single status screen. Shows whether the accessibility service is enabled. Provides a link to enable it if not.

**Elements shown:**
- Title: "MOV" — white, 32sp, bold, letter-spacing 0.12
- Label: "Accessibility Service" — gray (`#888888`), 13sp, letter-spacing 0.06
- Status text (`tv_service_status`) — 17sp, dynamic color:
  - Text "Active", color `#4CAF50` when service is enabled
  - Text "Not enabled", color `#888888` when service is not enabled
- Enable button (`btn_enable_service`) — "Enable in Settings →", blue `#4A90E2`, 15sp:
  - `GONE` when service is enabled
  - `VISIBLE` when service is not enabled

**User actions:**
- Tap "Enable in Settings →" → opens `Settings.ACTION_ACCESSIBILITY_SETTINGS`
- No other interactive elements

**Navigation behavior:**
- Screen has `android:screenOrientation="portrait"` — locked to portrait
- Returning to this screen (onResume) re-checks service status and updates the UI

**State changes:**
- No state is stored here; all state is read from `AccessibilityManager` on each `onResume`

---

### Screen 2: Overlay (OverlayView — shown over all apps)

**Purpose:** The challenge screen. Covers the entire display while the unlock sequence is active.

**Elements shown:**
- Black full-screen background
- Centered instruction text in white, 68sp, word-wrapped, `StaticLayout` centered alignment
- Text rotation: 0° (upright), 90° (landscape left), -90° (landscape right), 180° (inverted)
- Text alpha: 0.0 to 1.0 based on current zone and phone angle
- "Cancel" text at bottom center — white, 22sp, constant alpha 120/255 (~47%)

**User actions:**
- Tapping anywhere in the cancel zone (80px wide × 72px tall, centered at bottom, 48px from screen bottom) → `onCancel()` fires: dismiss overlay + go to home screen
- Pressing Back hardware/gesture button → `onBackPressed()` fires: dismiss overlay only (no home action)
- No other touch interactions are processed (all touch events consumed by `return true`)

**State changes:**
- `setZone(zone, msg, rotation, lockAlpha)` — updates displayed text, rotation, and alpha lock
- `updateAngles(tilt, inverted)` — drives alpha fade, triggers redraw via `invalidate()`

---

### Screen 3: Incompatibility Screen (shown in MainActivity when no accelerometer)

**Purpose:** Informs the user the device is unsupported.

**Elements shown:**
- Text: "This device doesn't have the sensors required to run MOV."
- White text, 17sp, 64dp padding on all sides, black background

**User actions:** None. The activity is effectively dead-ended.

---

## Settings Documentation

### Complexity

- **Name:** `complexity`
- **Purpose:** Controls how many rotation moves are required per challenge
- **Available values:** `LIGHT` (6 moves), `MEDIUM` (8 moves), `STRONG` (12 moves)
- **Default:** `MEDIUM`
- **Storage:** `SharedPreferences` key `"complexity"`, stored as enum name string
- **Effect:** Passed as `moveCount` to `SessionEngine` constructor. Directly determines the length of the generated path.
- **Fallback:** If stored value is not a valid `Complexity` name, defaults to `MEDIUM`

### Blocked Apps

- **Name:** `blocked_apps`
- **Purpose:** The set of package names that trigger the overlay
- **Available values:** Any valid Android package name strings
- **Default:** `{"com.instagram.android"}`
- **Storage:** `SharedPreferences` key `"blocked_apps"`, stored as `StringSet`
- **Effect:** Checked in `onAccessibilityEvent()`. A match triggers `showOverlay()`.
- **Modification:** Via `SettingsStore.addBlockedApp(pkg)` and `removeBlockedApp(pkg)` (no UI exists to call these in the current version)

---

## Rotation System

### Sensors Used

- **Sensor:** `Sensor.TYPE_ACCELEROMETER`
- **Rate:** `SensorManager.SENSOR_DELAY_UI`
- **Handler:** `Handler(mainLooper)` — callbacks deliver on main thread
- **Axes used:** X (left/right tilt) and Y (forward/back tilt, including inversion)
- **Axis Z:** Not used

### How Movement Is Detected

Raw accelerometer X and Y values are smoothed with an exponential moving average:
```
smoothX = 0.15 * rawX + 0.85 * smoothX
smoothY = 0.15 * rawY + 0.85 * smoothY
```

On the very first tick after `reset()`, smoothX and smoothY are set directly to raw values (no warmup lag for the initial classification).

The smoothed values are converted to degrees:
```
tiltDeg     = arcsin(|smoothX| / 9.8)  → measures left/right tilt
invertedDeg = arcsin(max(0, -smoothY) / 9.8)  → measures how far toward upside-down
```

Both values are clamped via `.coerceIn(-1.0, 1.0)` before the arcsin to prevent NaN.

### What Qualifies as a Valid Move

A move is validated the moment `onStateChanged()` fires with a `PhoneState` that matches `sessionEngine.current.to`. The transition must cross the zone boundary defined by `Thresholds.LANDSCAPE_ENTER_DEG` (50°) for landscape entry, and `Thresholds.INVERTED_ENTER_DEG` (50°) for inverted entry. A physical movement that briefly crosses the threshold and comes back without stabilizing will still register if `currentState` changed and changed back — but each state change fires `onStateChanged` separately, so an accidental bounce would count as both a correct move and a wrong move sequentially.

### Thresholds

All thresholds are defined in `Thresholds.kt`:

| Constant | Value | Meaning |
|---|---|---|
| `UPRIGHT_FULL_DEG` | 30° | Text fully opaque below this |
| `UPRIGHT_FADE_END_DEG` | 40° | Text fully transparent above this |
| `TRANSITION_GUARD_DEG` | 15° | Blocks LANDSCAPE→UPRIGHT exit if invertedDeg ≥ this |
| `LANDSCAPE_ENTER_DEG` | 50° | Entering landscape zone (tiltDeg must reach this) |
| `LANDSCAPE_EXIT_DEG` | 40° | Exiting landscape zone (tiltDeg must drop below this) |
| `LANDSCAPE_FULL_DEG` | 60° | Landscape text fully opaque above this |
| `INVERTED_ENTER_DEG` | 50° | Entering inverted zone (invertedDeg must reach this) |
| `INVERTED_EXIT_DEG` | 40° | Not directly used in classifier; same concept as LANDSCAPE_EXIT |
| `INVERTED_FADE_START_DEG` | 50° | Inverted text starts fading in at this angle |
| `INVERTED_FULL_DEG` | 60° | Inverted text fully opaque above this |

### State Classifier — Ring Topology

The phone is always in exactly one of four states. The states form a ring:

```
UPRIGHT(0) → LANDSCAPE_RIGHT(1) → INVERTED(2) → LANDSCAPE_LEFT(3) → UPRIGHT(0)
```

State transitions use 10° hysteresis (enter at 50°, exit at 40°).

**From UPRIGHT (or initial null state):**
- `invertedDeg ≥ 50°` → enter INVERTED
- `tiltDeg ≥ 50°` → enter LANDSCAPE_LEFT or LANDSCAPE_RIGHT (determined by sign of smoothX)
- Otherwise → stay UPRIGHT

**From LANDSCAPE_LEFT or LANDSCAPE_RIGHT:**
- `invertedDeg ≥ 50°` → enter INVERTED
- `tiltDeg < 40°` AND `invertedDeg < 15°` → enter UPRIGHT (transition guard prevents false exit mid-flip)
- Otherwise → stay in current landscape

**From INVERTED:**
- `tiltDeg ≥ 50°` → enter LANDSCAPE_LEFT or LANDSCAPE_RIGHT
- Cannot transition directly to UPRIGHT — ring topology enforced

**LANDSCAPE_LEFT vs LANDSCAPE_RIGHT determination:**
- `smoothX >= 0` → LANDSCAPE_LEFT
- `smoothX < 0` → LANDSCAPE_RIGHT

### Session Behavior

- The sensor is registered in `showOverlay()` and unregistered in `dismissOverlay()`.
- One detector instance exists per service lifecycle; it is reset (not recreated) on each new overlay.
- `currentState = null` after reset; the first `onStateChanged` after reset uses `prevState = null`.

### Reset Behavior

`detector.reset()` sets `currentState = null`, `smoothX = 0f`, `smoothY = 0f`. This happens at overlay creation and overlay dismissal.

### Anti-Accidental-Trigger Logic

**Transition guard (`TRANSITION_GUARD_DEG = 15°`):** When the phone is in a LANDSCAPE state and begins flipping toward INVERTED, physics dictates that when `tiltDeg` first drops through 40° (the LANDSCAPE_EXIT_DEG), `invertedDeg` is already at approximately 20.9°. The 15° guard ensures this condition always prevents a false LANDSCAPE→UPRIGHT exit during a LANDSCAPE→INVERTED flip.

**Directional alpha suppression in UPRIGHT zone:** When the current instruction is `Turn.RIGHT`, alpha feedback is suppressed for left-tilt movements (`lastXSmooth < 0` is checked; if phone tilts left when RIGHT is instructed, tiltDeg input to alpha calculation is replaced with 0, keeping overlay fully visible). Similarly, `Turn.LEFT` suppresses rightward tilt feedback. This prevents visual feedback that could mislead the user into thinking they are making progress in the wrong direction.

---

## Overlay System

### How Overlays Are Triggered

`showOverlay(pkg)` is called from `onAccessibilityEvent()` when all three conditions are met: (1) the package is in the blocked list, (2) `overlayView == null`, (3) `isNewOpen == true`.

### Display Priority

`TYPE_ACCESSIBILITY_OVERLAY` with `FLAG_FULLSCREEN | FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS` displays on top of all other apps and the status bar.

### Lifecycle

1. Created and added to `WindowManager` in `showOverlay()`.
2. Receives sensor angle updates via `updateAngles()` on every sensor tick.
3. Receives zone/message/rotation updates via `setZone()` on state changes.
4. Removed from `WindowManager` in `dismissOverlay()`.

### Visibility Rules

The overlay background is always fully opaque black. The instruction text alpha is computed on every draw call based on the current zone and sensor angles:

**UPRIGHT zone:**
```
alpha = min(
  ramp(tiltDeg,     30°, 40°, from=1.0, to=0.0),
  ramp(invertedDeg, 30°, 40°, from=1.0, to=0.0)
)
```
Text is fully visible at 0°–30°. Fades out between 30°–40°. Invisible above 40°.

**LANDSCAPE zone:**
```
alpha = ramp(tiltDeg, 50°, 60°, from=0.0, to=1.0)
```
Text is invisible at 50°. Fades in between 50°–60°. Fully visible above 60°.

**INVERTED zone:**
```
alpha = ramp(invertedDeg, 50°, 60°, from=0.0, to=1.0)
```
Text is invisible at 50°. Fades in between 50°–60°. Fully visible above 60°.

`ramp(v, low, high, lo, hi)`: linear interpolation; clamps below `low` to `lo`, above `high` to `hi`.

When `alphaLocked = true`, alpha is always 1.0 (full opacity).

### User Interaction Rules

- All touch events are consumed (`onTouchEvent` always returns `true`).
- Only the cancel zone at the bottom (a `RectF` positioned 48dp from bottom, 80px half-width, 72px tall) triggers a callback.
- The Back key fires `onBackPressed` callback.
- No other user input is processed.

### Exit Conditions

1. User taps Cancel → `onCancel()` → `dismissOverlay()` + `performGlobalAction(GLOBAL_ACTION_HOME)`
2. User presses Back → `onBackPressed()` → `dismissOverlay()` only
3. Challenge completed → `completeSession()` → 3s delay → `dismissOverlay()` + `launchApp()`
4. Service interrupted → `onInterrupt()` → `dismissOverlay()`
5. Service destroyed → `onDestroy()` → `dismissOverlay()`

---

## Data Storage

### SharedPreferences File

**Name:** `"mov_settings"`  
**Mode:** `Context.MODE_PRIVATE`  
**Location:** Standard Android SharedPreferences location (app-private, cleared on uninstall)

### Stored Values

#### `complexity` (String)
- **Type:** String (name of `Complexity` enum)
- **Purpose:** Determines move count for sessions
- **Default:** `"MEDIUM"`
- **Used in:** `SettingsStore.complexity` getter → `BlockerAccessibilityService.startSession()`
- **Changes when:** `SettingsStore.complexity` setter is called (no UI exists)

#### `blocked_apps` (StringSet)
- **Type:** `Set<String>` (Android SharedPreferences StringSet)
- **Purpose:** Package names that trigger the challenge overlay
- **Default:** `{"com.instagram.android"}`
- **Used in:** `BlockerAccessibilityService.onAccessibilityEvent()` — `pkg in settings.blockedApps`
- **Changes when:** `SettingsStore.addBlockedApp()` or `removeBlockedApp()` is called (no UI exists)

---

## State Management

### Global State (BlockerAccessibilityService — persists for the service lifetime)

| Variable | Type | Purpose | Initial Value |
|---|---|---|---|
| `phase` | `Phase` enum | Session stage: PRE_SESSION / ACTIVE / COMPLETE | `PRE_SESSION` |
| `currentPkg` | `String` | Package name of currently blocked app | `""` |
| `prevRealPkg` | `String` | Last real (non-system) package seen | `""` |
| `overlayView` | `OverlayView?` | Reference to active overlay; null if none showing | `null` |
| `sessionEngine` | `SessionEngine?` | Current session's path and progress | `null` |
| `currentPhoneState` | `PhoneState` | Last confirmed orientation state | `UPRIGHT` |
| `currentZone` | `OverlayView.Zone` | Current overlay zone (for directional angle logic) | `UPRIGHT` |
| `activeInstruction` | `String` | Current displayed instruction text | `""` |
| `activeInstructionRotation` | `Float` | Current canvas rotation degrees | `0f` |

### Session State (SessionEngine — exists only during active overlay)

| Variable | Type | Purpose |
|---|---|---|
| `homeState` | `PhoneState` | The orientation state when session began |
| `steps` | `List<SessionStep>` | Pre-generated ordered list of all moves |
| `index` | `Int` | Current position in steps (0-based) |

`SessionEngine` is created in `startSession()` and nulled in `showOverlay()` (before next session) and becomes unreachable after `dismissOverlay()`.

### Sensor State (MotionDetector)

| Variable | Type | Purpose |
|---|---|---|
| `smoothX` | `Float` | Exponentially smoothed X accelerometer value |
| `smoothY` | `Float` | Exponentially smoothed Y accelerometer value |
| `currentState` | `PhoneState?` | Last classified orientation; null = uninitialized |

### Persistent State

Stored in `SharedPreferences "mov_settings"`: `complexity`, `blocked_apps`. Survives app restarts and service restarts. Cleared on uninstall.

### State Transitions

```
Service connected → idle (no overlay, no sensor)
                         ↓ blocked app opened
                    PRE_SESSION (overlay visible, sensor active, no instruction text)
                         ↓ first stable orientation detected
                    ACTIVE (instruction visible, moves being validated)
                         ↓ all moves correct          ↓ user cancels
                    COMPLETE (3s delay, then          idle (overlay dismissed)
                    launch + dismiss)
```

---

## Business Rules

### Blocking Rules

1. Only apps whose package names are in `settings.blockedApps` are intercepted.
2. `"android"`, `"com.android.systemui"`, `"com.android.phone"` are never intercepted regardless of blocked list.
3. Only one overlay can be shown at a time. If an overlay is already showing, a second accessibility event for a blocked app is ignored.
4. The same app is not blocked twice in a row for the same "open" event (`isNewOpen` check using `prevRealPkg`). An app that was just unlocked and launched will not immediately retrigger the overlay.

### Session Rules

5. A session does not start (no instruction shown) until the phone reaches a stable orientation after the overlay appears. The first stable orientation becomes the home state.
6. The home state can be any of the four `PhoneState` values — whatever orientation the user holds the phone when the overlay first appears.
7. All sessions end at the same orientation they started (the generated path always returns to `homeState`).
8. Move count is determined by `settings.complexity.moveCount` at the moment `startSession()` is called. For `LIGHT`: 6, `MEDIUM`: 8, `STRONG`: 12.

### Move Validation Rules

9. A move is valid only if the phone enters the exact state specified by `sessionEngine.current.to`.
10. Any other state transition (entering a wrong state) results in `alphaLocked = true` on the overlay (instruction displayed at full opacity) — no counter change.
11. Moves must be completed in order. There is no skipping or branching.
12. Once a move is accepted, it cannot be undone. `sessionEngine.advance()` is one-directional.

### Path Generation Rules

13. The path is random but uses Z₄ ring arithmetic to guarantee it always returns to home.
14. After `moveCount - 2` random steps, if the distance from home is 0 (phone would be back already), the path is discarded and regenerated from scratch.
15. If the distance from home is 2 (guaranteed after an even number of steps in ring arithmetic), exactly 2 more steps in the same direction complete the path and return to home.
16. Move counts are always even (6, 8, 12) — this is required for the Z₄ closing guarantee.
17. INVERTED cannot transition directly to UPRIGHT in the session path (enforced by the ring topology: INVERTED can only reach LANDSCAPE_LEFT or LANDSCAPE_RIGHT, which can then reach UPRIGHT).

### Unlock Rules

18. After all `moveCount` correct moves, no further moves are required.
19. A 3-second fixed delay always occurs between challenge completion and app launch.
20. The delay cannot be shortened or skipped.
21. The overlay displays "Opening [AppName]..." during the delay. If the app name cannot be retrieved, the package name string is shown.

### Permission Requirements

22. The Accessibility Service must be enabled by the user in Android Settings. The app cannot enable it programmatically.
23. No runtime permissions are requested. `SYSTEM_ALERT_WINDOW` is not used. `TYPE_ACCESSIBILITY_OVERLAY` does not require it.
24. `android.hardware.sensor.accelerometer` is declared in the manifest with `required="false"` (app installs on devices without it, but shows an incompatibility message).

---

## Edge Cases

### Device Has No Accelerometer
`MainActivity.onCreate()` checks for `Sensor.TYPE_ACCELEROMETER`. If null, `showIncompatible()` is called. The activity never loads `activity_main.xml`. The accessibility service, if somehow enabled, would call `sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)` — if null, the sensor registration in `showOverlay()` is skipped entirely (the `?.let` block does not execute), so no sensor callbacks fire, and the session never starts (overlay shows but remains in PRE_SESSION phase forever, Cancel being the only exit).

### Phone Already in Non-Upright Orientation When Overlay Appears
The session waits for the first `onStateChanged()` after overlay creation, regardless of what state that is. If the phone is already sideways, the first state detected (LANDSCAPE_LEFT, for example) becomes homeState. The session is generated from and returns to that state.

### Overlay Dismissed During Completion Delay
If `dismissOverlay()` is called (e.g., by service interrupt) while the 3-second `postDelayed` runnable is queued, the runnable still fires after 3 seconds. The `if (overlayView != null)` guard inside the runnable prevents `launchApp()` from executing. The app does not launch. This is the only protection — there is no `cancel()` call on the handler.

### User Presses Back vs. Cancel
- **Back:** `dismissOverlay()` only. The blocked app remains in the background (no home action). User ends up wherever they were before the blocked app was opened.
- **Cancel:** `dismissOverlay()` + `performGlobalAction(GLOBAL_ACTION_HOME)`. User is sent to the Android home screen.

### Rapid App Switching / System UI Interleaving
`isSystemUiPkg()` filters out `"android"`, `"com.android.systemui"`, and `"com.android.phone"` from both `prevRealPkg` updates and blocking checks. This prevents system dialogs, notifications, or quick settings overlays from polluting the "last seen app" tracking or triggering false overlays.

### App Name Not Retrievable
`getAppName()` is wrapped in `try/catch(Exception)`. If `PackageManager.getApplicationInfo()` throws (package was just uninstalled, for example), the raw package name string is displayed in the "Opening..." message.

### Wrong Move During Active Session
`alphaLocked = true` is passed to `setZone()`. This forces `computeAlpha()` to return 1.0, keeping the instruction fully visible. The `Turn` instruction does not change. The session counter does not reset. The user must complete the correct move from wherever the phone currently is. Note: if the user moved to a wrong state and that state happens to equal a future step's `to` value, it is not counted — validation is against `sessionEngine.current.to` only (the immediate next required step).

### Phone Goes Flat (Horizontal)
No FLAT state exists. The classifier only knows UPRIGHT, LANDSCAPE_LEFT, LANDSCAPE_RIGHT, INVERTED. A flat phone produces near-zero tiltDeg and near-zero invertedDeg. The classifier stays in whatever state it was last in (no exit conditions are met). In UPRIGHT phase, the overlay text fades toward full opacity (tiltDeg and invertedDeg both ≤ 30°, which is full alpha for the UPRIGHT zone).

### Sensor Oscillation at Zone Boundaries
10° hysteresis on all state boundaries. The 15° `TRANSITION_GUARD_DEG` provides additional protection against LANDSCAPE→UPRIGHT false exits during mid-flip physics. Exponential smoothing (α=0.15) further dampens noise.

### Accelerometer Reading Exceeds 9.8 m/s²
`(g / 9.8).coerceIn(-1.0, 1.0)` clamps the arcsin input. Values exceeding ±1g (due to dynamic acceleration) are clamped and do not cause NaN or crashes.

---

## Dependency Documentation

### `org.jetbrains.kotlin:kotlin-stdlib:1.9.22`
**Why:** Required for Kotlin language runtime — collections, extensions, standard library functions used throughout. All source files are Kotlin.

### `androidx.appcompat:appcompat:1.6.1`
**Why:** `MainActivity` extends `AppCompatActivity`. Provides backward-compatible Activity base class, theme support, and `AppCompatTextView` behavior. Required for `Theme.AppCompat.NoActionBar` parent style.

### `androidx.core:core-ktx:1.10.1`
**Why:** Provides Kotlin extension functions for Android framework classes. Imported but specific KTX extensions not visibly invoked in the current source — retained as standard Android Kotlin project dependency.

### Android Framework (system — not declared as dependency)
**Why:** `AccessibilityService`, `WindowManager`, `SensorManager`, `SharedPreferences`, `PackageManager`, `Handler` — all standard Android framework APIs.

---

## Repository Structure

```
mov/
├── build.gradle                    Root Gradle build — declares AGP and Kotlin plugin versions only
├── settings.gradle                 Project-level settings — declares :app module, repository sources
├── gradle.properties               JVM heap (2048m), AndroidX enabled, Jetifier enabled, Kotlin code style
├── local.properties                Machine-local SDK path (not committed to VCS)
├── gradlew / gradlew.bat           Gradle wrapper scripts
├── gradle/wrapper/                 Gradle wrapper JAR and config
│
├── docs/
│   ├── PROJECT_KNOWLEDGE_BASE.md  779-line product/architecture documentation
│   └── Version_Reconstruction_Specification_1.md  ← this document
│
└── app/
    ├── build.gradle               App module: namespace, SDK versions, dependencies
    └── src/main/
        ├── AndroidManifest.xml    Declares MainActivity, BlockerAccessibilityService, accelerometer feature
        │
        ├── java/com/example/blocker/
        │   ├── MainActivity.kt                      Main UI: service status, enable link, incompatibility check
        │   ├── BlockerAccessibilityService.kt       Core service: event monitoring, overlay lifecycle, session coordination
        │   ├── OverlayView.kt                       Custom View: renders instruction text with alpha fade, handles Cancel/Back
        │   │
        │   ├── sensor/
        │   │   ├── MotionDetector.kt                Accelerometer reader: smoothing, toDeg, state classifier, hysteresis
        │   │   └── Thresholds.kt                    All angle constants in one place
        │   │
        │   ├── session/
        │   │   ├── PhoneState.kt                    Enum: 4 orientation states with ring index and step() method
        │   │   ├── Turn.kt                          Enum: LEFT/RIGHT/UP/DOWN with display label strings
        │   │   ├── Complexity.kt                    Enum: LIGHT/MEDIUM/STRONG with moveCount and display strings
        │   │   └── SessionEngine.kt                 Path generator + move tracker; uses Z₄ ring math for closure
        │   │
        │   └── settings/
        │       └── SettingsStore.kt                 SharedPreferences wrapper: complexity, blocked apps
        │
        └── res/
            ├── layout/
            │   └── activity_main.xml                Main screen layout (title, status label, status value, enable button)
            ├── values/
            │   ├── strings.xml                      app_name="MOV", accessibility_service_description
            │   └── themes.xml                       AppTheme: NoActionBar, all-black chrome, blue accent
            └── xml/
                └── accessibility_service_config.xml typeWindowStateChanged, flagReportViewIds, 100ms timeout
```

---

## Application Lifecycle

### App Launch (`MainActivity.onCreate`)
1. `SensorManager` queried for `TYPE_ACCELEROMETER`.
2. If absent → `showIncompatible()` renders plain text error. Activity stops here.
3. If present → `setContentView(R.layout.activity_main)`.
4. `onResume()` fires → `updateServiceStatus()` checks `AccessibilityManager` → updates UI.

### Backgrounding the MOV App
- `MainActivity` goes to background. No foreground service. No work happens.
- `BlockerAccessibilityService` continues running in the background — it is an accessibility service, managed by the Android framework, not tied to the app's process state.

### Foregrounding the MOV App
- `MainActivity.onResume()` fires, re-checks service status, updates UI.

### Accessibility Service Start
`onServiceConnected()` runs once when the user enables the service:
1. `MotionDetector` instance created.
2. `WindowManager`, `SensorManager` obtained.
3. `SettingsStore` instantiated.
The service then idles, listening for accessibility events via `onAccessibilityEvent()`.

### Opening a Blocked App (Overlay Lifecycle)
1. `onAccessibilityEvent()` receives `TYPE_WINDOW_STATE_CHANGED` for a blocked package.
2. `showOverlay()`:
   - `detector.reset()`
   - `phase = PRE_SESSION`
   - `sessionEngine = null`
   - `OverlayView` constructed and added to `WindowManager`
   - Accelerometer listener registered
3. Sensor ticks begin; `onPosition()` fires each tick — alpha updates.
4. First stable orientation → `onStateChanged()` with `prevState = null` → `startSession(homeState)`.
5. `phase = ACTIVE`. `SessionEngine` created. First instruction shown.
6. Each correct phone rotation → `sessionEngine.advance()` → next instruction shown.
7. Final correct move → `completeSession()` → `phase = COMPLETE` → "Opening..." displayed.
8. After 3000 ms → `launchApp(currentPkg)` → `dismissOverlay()`.
9. `dismissOverlay()`:
   - `currentPkg = ""`
   - `wm.removeView(overlayView)`
   - `overlayView = null`
   - `sm.unregisterListener(detector)`
   - `detector.reset()`

### Service Interruption / Destruction
- `onInterrupt()` → `dismissOverlay()` (overlay cleaned up immediately)
- `onDestroy()` → `super.onDestroy()` then `dismissOverlay()`

---

## Reconstruction Guide

### Step 1: Project Setup

Create a new Android project:
- Language: Kotlin
- Min SDK: 26
- Target SDK: 34
- Package name: `com.example.blocker`
- Application name: `MOV`
- No Compose; use View-based XML layouts

Add dependencies:
- `androidx.appcompat:appcompat:1.6.1`
- `androidx.core:core-ktx:1.10.1`
- Kotlin stdlib is added automatically

### Step 2: AndroidManifest.xml

Declare:
- `uses-feature android:name="android.hardware.sensor.accelerometer" android:required="false"`
- `MainActivity` with `android:screenOrientation="portrait"` and MAIN/LAUNCHER intent filter
- `BlockerAccessibilityService` with `BIND_ACCESSIBILITY_SERVICE` permission, `AccessibilityService` intent filter, and `accessibility_service_config.xml` meta-data

### Step 3: Accessibility Service Config

Create `res/xml/accessibility_service_config.xml`:
- `accessibilityEventTypes="typeWindowStateChanged"`
- `accessibilityFlags="flagReportViewIds"`
- `accessibilityFeedbackType="feedbackGeneric"`
- `notificationTimeout="100"`
- `canRetrieveWindowContent="true"`
- `description` pointing to a string resource

### Step 4: Theme

Set `Theme.AppCompat.NoActionBar` as parent. Set all window chrome to black. Set primary/accent to `#4A90E2`.

### Step 5: Domain Model (no dependencies, pure Kotlin)

Implement in order:

**`PhoneState` enum** — four values: UPRIGHT(0), LANDSCAPE_RIGHT(1), INVERTED(2), LANDSCAPE_LEFT(3). Each has a `ringIndex: Int`. Add a `step(clockwise: Boolean): PhoneState` method: clockwise advances `ringIndex + 1` mod 4, counter-clockwise advances `ringIndex + 3` mod 4.

**`Turn` enum** — four values: LEFT, RIGHT, UP, DOWN. Each has a `label: String` getter that returns an arrow + word string.

**`Complexity` enum** — three values: LIGHT(6), MEDIUM(8), STRONG(12). Each has `moveCount: Int`, `label: String`, `description: String`.

**`SessionStep` data class** — fields: `from: PhoneState`, `turn: Turn`, `to: PhoneState`.

**`SessionEngine` class** — constructor takes `homeState: PhoneState` and `moveCount: Int`. On construction, generate the step list using `buildPath()`. Expose `current: SessionStep?` (nullable, returns null when complete), `isComplete: Boolean`, `advance()` method.

`buildPath()` algorithm:
1. Generate `moveCount - 2` random steps. Start from `homeState`. Each step: pick random boolean → call `state.step(random)` → compute `Turn` using `deriveTurn(from, to)` → add `SessionStep`.
2. Compute `dist = (home.ringIndex - state.ringIndex + 4) % 4`.
3. If `dist == 0`: discard and retry entire path.
4. If `dist == 2`: randomly pick CW or CCW → add 2 more steps in that direction.
5. Verify `state == homeState` at end (should always be true if `dist == 2`).

`deriveTurn(from, to)` mapping (CW = `(to.ringIndex - from.ringIndex + 4) % 4 == 1`):
- UPRIGHT + CW → RIGHT; UPRIGHT + CCW → LEFT
- INVERTED + CW → LEFT; INVERTED + CCW → RIGHT  ← user's perspective flips here
- LANDSCAPE_RIGHT + CW → DOWN; LANDSCAPE_RIGHT + CCW → UP
- LANDSCAPE_LEFT + CW → UP; LANDSCAPE_LEFT + CCW → DOWN

### Step 6: Thresholds

Create `Thresholds` object with constants: UPRIGHT_FULL_DEG=30, UPRIGHT_FADE_END_DEG=40, TRANSITION_GUARD_DEG=15, LANDSCAPE_ENTER_DEG=50, LANDSCAPE_EXIT_DEG=40, LANDSCAPE_FULL_DEG=60, INVERTED_ENTER_DEG=50, INVERTED_FADE_START_DEG=50, INVERTED_FULL_DEG=60.

### Step 7: MotionDetector

Implement `SensorEventListener`. Constructor takes a `Listener` interface (`onPosition(tiltDeg, invertedDeg)` + `onStateChanged(newState, prevState?)`).

Internal state: `smoothX: Float`, `smoothY: Float`, `currentState: PhoneState?`.

`onSensorChanged()`:
1. If `currentState == null`: assign `smoothX = rawX`, `smoothY = rawY` (bypass lag).
2. Else: `smoothX = 0.15 * rawX + 0.85 * smoothX`, same for Y.
3. `tiltDeg = arcsin(coerceIn(|smoothX| / 9.8, -1, 1)).toDegrees()`
4. `invertedDeg = arcsin(coerceIn(max(0, -smoothY) / 9.8, -1, 1)).toDegrees()`
5. Call `listener.onPosition(tiltDeg, invertedDeg)`.
6. Call `classify(tiltDeg, invertedDeg)`.
7. If result != `currentState`: update `currentState`, call `listener.onStateChanged(newState, prev)`.

`classify()` state machine: see Rotation System → State Classifier section above for exact transition table.

`landscapeSide()`: `smoothX >= 0` → LANDSCAPE_LEFT, else LANDSCAPE_RIGHT.

`reset()`: `currentState = null`, `smoothX = 0`, `smoothY = 0`.

Expose `lastXSmooth: Float` as a public getter for `smoothX`.

### Step 8: SettingsStore

Wrap `SharedPreferences("mov_settings", MODE_PRIVATE)`. Expose:
- `complexity: Complexity` — reads/writes by enum name, defaults to MEDIUM
- `blockedApps: Set<String>` — reads/writes StringSet, default `{"com.instagram.android"}`
- `addBlockedApp(pkg)` / `removeBlockedApp(pkg)` — immutable set +/- operations

### Step 9: OverlayView

Extend `View`. Constructor parameters: `context: Context`, `onBackPressed: () -> Unit`, `onCancel: () -> Unit`.

Internal state: `message: String`, `canvasRotation: Float`, `zone: Zone` (enum: UPRIGHT/LANDSCAPE/INVERTED), `tiltDeg: Float`, `invertedDeg: Float`, `alphaLocked: Boolean`, `cancelRect: RectF`.

Initialize: black background, `isFocusable = true`, `isFocusableInTouchMode = true`.

`onSizeChanged()`: Set `cancelRect` to a 160×72 px region horizontally centered, 48px from bottom.

`onDraw(canvas)`:
1. Compute alpha via `computeAlpha()`.
2. If message not empty and alpha > 0 and width > 0:
   - Determine layout width: if `canvasRotation != 0`, use `height - 160` else `width - 160`.
   - Set `textPaint.alpha = (alpha * 255).toInt()`.
   - Build `StaticLayout` with `ALIGN_CENTER`, 1.2x line spacing.
   - `canvas.save()`, rotate by `canvasRotation` around center, translate to center the layout, `layout.draw(canvas)`, `canvas.restore()`.
3. Draw "Cancel" text at center-x, `cancelRect.bottom - 20`, alpha 120.

`computeAlpha()`:
- If `alphaLocked` → return 1f.
- UPRIGHT: `min(ramp(tiltDeg, 30, 40, 1→0), ramp(invertedDeg, 30, 40, 1→0))`
- LANDSCAPE: `ramp(tiltDeg, 50, 60, 0→1)`
- INVERTED: `ramp(invertedDeg, 50, 60, 0→1)`

`ramp(v, low, high, lo, hi)`: if v ≤ low → lo; if v ≥ high → hi; else linear interpolation.

Text paint: white, anti-alias, 68sp.
Cancel paint: white, anti-alias, 22sp, center-aligned.

### Step 10: BlockerAccessibilityService

Extend `AccessibilityService`, implement `MotionDetector.Listener`.

Internal phase enum: PRE_SESSION, ACTIVE, COMPLETE. Constant `COMPLETION_DELAY_MS = 3000L`.

`onServiceConnected()`: instantiate `MotionDetector`, get `WindowManager`, `SensorManager`, instantiate `SettingsStore`.

`onAccessibilityEvent()`:
1. Filter: only `TYPE_WINDOW_STATE_CHANGED`.
2. Get `pkg` from `event.packageName`.
3. Compute `isNewOpen = (pkg != prevRealPkg)`.
4. If not a system UI pkg: `prevRealPkg = pkg`.
5. If `pkg in settings.blockedApps && overlayView == null && isNewOpen`: `showOverlay(pkg)`.

`showOverlay(pkg)`:
1. `detector.reset()`, `phase = PRE_SESSION`, `sessionEngine = null`, `currentPkg = pkg`.
2. Create `WindowManager.LayoutParams`: MATCH_PARENT×MATCH_PARENT, TYPE_ACCESSIBILITY_OVERLAY, OPAQUE, fullscreen flags.
3. Construct `OverlayView` with `onBackPressed = ::dismissOverlay`, `onCancel = { dismissOverlay(); performGlobalAction(GLOBAL_ACTION_HOME) }`.
4. `wm.addView(view, params)`, set `overlayView = view`.
5. Register accelerometer sensor with `sm.registerListener(detector, sensor, SENSOR_DELAY_UI, Handler(mainLooper))`.

`dismissOverlay()`:
1. `currentPkg = ""`, remove view from WM, `overlayView = null`.
2. `sm.unregisterListener(detector)`, `detector.reset()`.

`onPosition(tiltDeg, invertedDeg)`:
- Apply `directionalAngles()` filtering.
- Call `overlayView?.updateAngles(effTilt, effInverted)`.

`onStateChanged(newState, prevState)`:
- PRE_SESSION → `startSession(newState)`
- ACTIVE → `handleStateChange(newState)`
- COMPLETE → ignore

`startSession(homeState)`:
- `phase = ACTIVE`, `currentPhoneState = homeState`
- `sessionEngine = SessionEngine(homeState, settings.complexity.moveCount)`
- `loadInstruction(zone, rotation)` using `zoneAndRotation(homeState)`

`handleStateChange(newState)`:
- `currentPhoneState = newState`
- Get `step = sessionEngine?.current ?: return`
- `val (zone, rotation) = zoneAndRotation(newState)`
- If `step.to == newState`: `onCorrectMove(newState, zone, rotation)`
- Else: `applyZone(zone, activeInstruction, activeInstructionRotation, lockAlpha = true)`

`onCorrectMove(newState, zone, rotation)`:
- `currentPhoneState = newState`, `sessionEngine?.advance()`
- If `sessionEngine?.isComplete == true`: `completeSession(zone, rotation)`
- Else: `loadInstruction(zone, rotation)`

`loadInstruction(zone, rotation)`:
- `activeInstruction = sessionEngine?.current?.turn?.label ?: return`
- `activeInstructionRotation = rotation`
- `applyZone(zone, activeInstruction, rotation)`

`completeSession(zone, rotation)`:
- `phase = COMPLETE`
- `applyZone(zone, "Opening ${getAppName(currentPkg)}...", rotation)`
- `Handler(mainLooper).postDelayed(3000) { if (overlayView != null) { prevRealPkg = pkgToLaunch; launchApp(pkgToLaunch); dismissOverlay() } }`

`directionalAngles(tiltDeg, invertedDeg)`:
- If not `ACTIVE` or not `Zone.UPRIGHT` zone: return unchanged.
- If instruction is `Turn.RIGHT` and `lastXSmooth < 0`: suppress tilt (return 0 for tilt).
- If instruction is `Turn.LEFT` and `lastXSmooth > 0`: suppress tilt (return 0 for tilt).
- Otherwise: return unchanged.

`zoneAndRotation(state)`:
- UPRIGHT → Zone.UPRIGHT, 0°
- LANDSCAPE_LEFT → Zone.LANDSCAPE, 90°
- LANDSCAPE_RIGHT → Zone.LANDSCAPE, -90°
- INVERTED → Zone.INVERTED, 180°

`applyZone(zone, msg, rotation, lockAlpha)`:
- `currentZone = zone`
- `overlayView?.setZone(zone, msg, rotation, lockAlpha)`

`launchApp(pkg)`:
- `packageManager.getLaunchIntentForPackage(pkg)?.addFlags(FLAG_ACTIVITY_NEW_TASK)` then `startActivity()`.

`getAppName(pkg)`:
- `packageManager.getApplicationInfo(pkg, 0)` → `getApplicationLabel()` → toString(). Catch all exceptions → return pkg.

`onInterrupt()` → `dismissOverlay()`
`onDestroy()` → `super.onDestroy()` then `dismissOverlay()`

### Step 11: MainActivity

Extend `AppCompatActivity`.

`onCreate()`:
1. Get `SensorManager`, check for `TYPE_ACCELEROMETER`.
2. If null: call `showIncompatible()` and return.
3. Else: `setContentView(R.layout.activity_main)`.

`onResume()`: `updateServiceStatus()`.

`updateServiceStatus()`:
- Find `tv_service_status` and `btn_enable_service` by ID.
- Call `isServiceEnabled()`.
- If enabled: status text = "Active", color `#4CAF50`, button `GONE`.
- If disabled: status text = "Not enabled", color `#888888`, button `VISIBLE` with click listener → `startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))`.

`isServiceEnabled()`:
- Query `AccessibilityManager.getEnabledAccessibilityServiceList(FEEDBACK_ALL_MASK)`.
- Return true if any entry has `resolveInfo.serviceInfo.packageName == packageName`.

`showIncompatible()`:
- `setContentView(android.R.layout.simple_list_item_1)`
- Find `android.R.id.text1`, set text, white color, 17sp, 64px padding, black background.

### Step 12: Layouts

`activity_main.xml`: Vertical `LinearLayout`, black background, 28dp horizontal / 64dp top / 40dp bottom padding. Contains: title `TextView` "MOV" (32sp bold white, letterSpacing 0.12, marginBottom 48dp), label `TextView` "Accessibility Service" (13sp gray #888888, letterSpacing 0.06, marginBottom 8dp), status `TextView` (17sp, marginBottom 16dp), enable button `TextView` "Enable in Settings →" (15sp blue #4A90E2, visibility GONE, paddingTop 4dp, paddingBottom 16dp).

---

## Verification Checklist

### Startup
- [ ] App shows title "MOV" in white bold on black background
- [ ] On first launch with service disabled: status reads "Not enabled" in gray
- [ ] "Enable in Settings →" link is visible and taps open Accessibility Settings
- [ ] After enabling service and returning: status reads "Active" in green
- [ ] Enable button disappears when service is active
- [ ] On device without accelerometer: incompatibility message shown, no other UI

### Blocking Behavior
- [ ] Opening `com.instagram.android` shows the overlay
- [ ] Other apps not in blocked list open normally
- [ ] System UI events (`com.android.systemui`) do not trigger overlay
- [ ] Opening a blocked app while overlay is already showing does not create a second overlay

### Overlay Appearance
- [ ] Overlay is fullscreen black, no transparency
- [ ] "Cancel" text visible at bottom center at ~47% opacity
- [ ] No instruction text visible immediately after overlay appears (PRE_SESSION phase)

### Session Start
- [ ] Instruction text appears only after phone reaches a stable orientation
- [ ] Instruction text is centered on screen
- [ ] Instruction text includes an arrow character and direction word

### Instruction Text Rotation
- [ ] When phone is upright: text reads horizontally
- [ ] When phone is tilted to its left side (LANDSCAPE_LEFT): text is rotated 90° clockwise
- [ ] When phone is tilted to its right side (LANDSCAPE_RIGHT): text is rotated 90° counter-clockwise
- [ ] When phone is upside down (INVERTED): text is rotated 180°

### Alpha Fade Behavior
- [ ] In UPRIGHT zone: text begins fading when tilt exceeds 30°, fully invisible at 40°+
- [ ] In UPRIGHT zone: text begins fading when invertedDeg exceeds 30°, fully invisible at 40°+
- [ ] In LANDSCAPE zone: text fades in as tilt increases from 50° to 60°, fully visible above 60°
- [ ] In INVERTED zone: text fades in as invertedDeg increases from 50° to 60°, fully visible above 60°

### Directional Alpha Suppression
- [ ] When instruction is "Turn right" and phone tilts left: overlay stays at full opacity
- [ ] When instruction is "Turn left" and phone tilts right: overlay stays at full opacity

### Move Validation
- [ ] Completing the correct rotation advances to the next instruction
- [ ] Rotating to a wrong orientation keeps current instruction at full opacity (locked alpha)
- [ ] Wrong rotation does not advance or reset the counter

### Path Properties
- [ ] Session always ends with phone in same orientation it started
- [ ] LIGHT: 6 move challenge
- [ ] MEDIUM: 8 move challenge
- [ ] STRONG: 12 move challenge

### Completion
- [ ] After final correct move: text changes to "Opening [AppName]..."
- [ ] No further moves accepted after completion (COMPLETE phase)
- [ ] Blocked app opens after exactly 3 seconds
- [ ] Overlay disappears when app opens

### Cancellation — Back Button
- [ ] Pressing Back dismisses the overlay
- [ ] Blocked app does not open
- [ ] User is NOT sent to home screen (remains in previous context)

### Cancellation — Cancel Button
- [ ] Tapping "Cancel" area dismisses the overlay
- [ ] Blocked app does not open
- [ ] User IS sent to the Android home screen

### Re-trigger Prevention
- [ ] After completing unlock and app opens: the app does not immediately re-show the overlay
- [ ] Re-opening the same blocked app after navigating away triggers the overlay again

### Ring Topology
- [ ] INVERTED state cannot transition directly to UPRIGHT — must pass through LANDSCAPE
- [ ] Hysteresis prevents rapid oscillation at zone boundaries

### Sensor Edge Cases
- [ ] Transition guard prevents false UPRIGHT detection mid-flip from LANDSCAPE toward INVERTED
- [ ] App handles device acceleration above 1g without crashing
