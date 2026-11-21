# MOV

MOV is an Android app that introduces physical friction before opening selected apps. It starts a fullscreen rotation challenge; only after every move is completed in the required sequence does the blocked app open.

The project uses an Android AccessibilityService to detect app-window changes and the device accelerometer to classify the phone into four orientation states:

```text
UPRIGHT -> LANDSCAPE_RIGHT -> INVERTED -> LANDSCAPE_LEFT -> UPRIGHT
```

## Features

- Fullscreen black overlay for blocked apps
- Six-, eight-, or twelve-move generated rotation sessions
- Accelerometer-based detection with smoothing and hysteresis
- Wrong-move validation with progress tracking
- Flat-phone guard that pauses the challenge
- Cancel and Back handling
- Automatic return to the blocked app after a successful session
- Compatibility screen for devices without an accelerometer

## How It Works

1. Enable MOV's accessibility service.
2. Open a package listed in `SettingsStore.blockedApps`.
3. MOV immediately shows a fullscreen overlay and sends the device to the home screen.
4. Hold the phone upright until the pre-session state is established.
5. Follow the on-screen left, right, up, or down instructions.
6. Complete the generated orientation path to open the app.

The challenge can be cancelled with the **Cancel** control or the device Back action. Cancellation dismisses the overlay and does not launch the blocked app.

## Current Configuration

There is no settings UI in the current version. Configuration is stored in Android `SharedPreferences` under `mov_settings`:

| Preference | Key | Default |
|---|---|---|
| Complexity | `complexity` | `MEDIUM` |
| Blocked apps | `blocked_apps` | `com.instagram.android` |

`Complexity` is one of:

- `LIGHT`: 6 moves
- `MEDIUM`: 8 moves
- `STRONG`: 12 moves

Developers can change or query these values through `SettingsStore`:

```kotlin
val settings = SettingsStore(context)
settings.complexity = Complexity.STRONG
settings.addBlockedApp("com.example.app")
settings.removeBlockedApp("com.instagram.android")
```

## Requirements

- Android device or emulator with an accelerometer
- Android 8.0 (API 26) or newer
- Android Studio or a JDK capable of running Gradle
- Android SDK platform 34
- Accessibility access enabled for MOV

The app declares `targetSdk 34` and `compileSdk 34`.

## Build and Run

Clone the repository and open it in Android Studio, or build from the command line:

```bash
git clone https://github.com/promptsjd/Mov.git
cd Mov
```

Build a debug APK:

```bash
bash ./gradlew assembleDebug
```

Install on a connected device or emulator:

```bash
bash ./gradlew installDebug
```

The generated APK is located at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

For local Gradle Wrapper compatibility, use a JDK supported by Android Gradle Plugin 8.2. Java 17 is the recommended JDK for this project.

## Enable the Accessibility Service

1. Launch MOV.
2. Tap **Enable in Settings ->**.
3. Open MOV in Android's Accessibility settings.
4. Enable **MOV** and confirm the system warning.
5. Return to MOV and verify that the status is `Active`.

The app listens only for `TYPE_WINDOW_STATE_CHANGED` accessibility events and requires no network or account setup.

## Project Structure

```text
app/src/main/
├── AndroidManifest.xml
├── java/com/example/blocker/
│   ├── MainActivity.kt                         Status and setup screen
│   ├── BlockerAccessibilityService.kt          Blocking and session orchestration
│   ├── OverlayView.kt                          Fullscreen challenge UI
│   ├── sensor/
│   │   ├── FlatGuard.kt                       Flat-phone safety guard
│   │   ├── MotionDetector.kt                   Sensor smoothing and state classification
│   │   ├── PhoneGuard.kt                       Guard interface
│   │   └── Thresholds.kt                       Orientation thresholds
│   ├── session/
│   │   ├── Complexity.kt                       Challenge length options
│   │   ├── PhoneState.kt                       Four-state orientation ring
│   │   ├── SessionEngine.kt                    Random path generation
│   │   └── Turn.kt                             User-facing turn instructions
│   └── settings/
│       └── SettingsStore.kt                    SharedPreferences wrapper
└── res/
    ├── layout/activity_main.xml
    ├── values/strings.xml
    ├── values/themes.xml
    └── xml/accessibility_service_config.xml
```

## Orientation and Session Logic

- Raw accelerometer X/Y values are smoothed with a 15% new-value, 85% previous-value moving average.
- Landscape entry occurs around 50 degrees of sideways tilt; landscape exit uses 40 degrees, providing 10 degrees of hysteresis.
- Inverted entry uses approximately 50 degrees and requires returning through landscape rather than directly from upright.
- A flat phone triggers a guard at 70 degrees of flatness and clears after it drops below 55 degrees.
- `SessionEngine` generates a random path that starts and ends at the phone's initial state.
- A correct move advances the sequence; a wrong move leaves the current instruction unchanged.
- After completion, MOV waits three seconds before launching the original app.

The orientation thresholds are centralized in `Thresholds.kt`.

## Permissions and Privacy

MOV uses:

- `BIND_ACCESSIBILITY_SERVICE` to monitor foreground app-window changes
- The accelerometer sensor to detect physical phone rotations
- `WindowManager` to draw a fullscreen overlay
- Local `SharedPreferences` to store complexity and blocked package names

MOV does not contain network API calls or an external analytics or authentication service in this implementation. Blocking package names and session state are processed locally on the device.

## Testing

No source-set tests or instrumentation tests are currently included. The most direct validation is to install the APK on a physical device, enable Accessibility access, and test the default Instagram package blocking and each orientation transition.

For a Gradle build:

```bash
bash ./gradlew assembleDebug
```

## Known Implementation Constraints

- App selection and complexity controls are not exposed in the UI.
- The default blocked package is Instagram.
- The app cannot function if the device has no accelerometer.
- Behavior depends on Android AccessibilityService event delivery and the platform's package launch intents.
- Rapid app switching or system UI transitions can affect window-change event handling.
- `MainActivity` only reports whether the accessibility service is enabled; it does not add or remove blocked apps.

## Documentation

Additional implementation detail is available in [docs/Version_Reconstruction_Specification_1.md](docs/Version_Reconstruction_Specification_1.md).
