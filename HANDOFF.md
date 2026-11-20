# HANDOFF — BLE Proximity Alert (state as of 2026-11-20)

Written for the next agent picking up this project. It records what was changed, what was verified, what was NOT done, and the acceptance bar the project owner set.

## 1. What this repo is

Android app (Kotlin, Jetpack Compose) that estimates distance to a paired Bluetooth audio device and alerts when you leave it behind. Background BLE scanning drives an RSSI → distance pipeline (Kalman filter + trend analysis + hysteresis/debounce alert engine), with a foreground service, diagnostics, and a calibration wizard.

- Package: `com.example.bleproximityalert`
- Min SDK 24 (Android 7.0), target SDK 36
- Source root: `app/src/main/java/com/example/bleproximityalert/`

## 2. Verified state at time of handoff

- `./gradlew assembleDebug` — BUILD SUCCESSFUL (Kotlin compiles clean, no errors)
- `./gradlew testDebugUnitTest` — 44 unit tests pass (AlertEngine 12, DistanceEstimator 14, KalmanFilter 8, TrendAnalyzer 10)
- `./gradlew assembleDebugAndroidTest` — instrumented tests compile
- CI exists: `.github/workflows/android.yml` runs unit tests + debug APK build on push to `main`/`master` and on PRs

Hardware testing was done on real devices (USB + on-device logcat):
- Sony WF-C500 (dual-mode: A2DP/HFP classic + BLE) — probe succeeded, live RSSI distance worked
- Airdopes Plus 311 (classic A2DP/HFP only, no BLE GATT) — no signal available; correct messaging is "no live signal for this device", not an error

## 3. What changed in the working tree (uncommitted as of this handoff, ~1250 lines)

| File | Change |
|---|---|
| `ble/RssiCapabilityProbe.kt` | Probe now tries **both LE and BR/EDR GATT paths** (previously only BR/EDR) — root cause of false "Proximity Signal Unavailable" on dual-mode devices like WF-C500 |
| `ui/StartMonitoringScreen.kt` | Added an explicit "starting" state and a re-entrancy guard so **one tap** on Start Monitoring is enough — previously it needed multiple taps because the ~7s capability probe ran with no UI feedback and the button stayed live |
| `ui/MonitoringScreen.kt` | New honest "signal unavailable" card driven by the actual probe result (device-specific text + Re-check button) instead of showing zeros/errors |
| `ble/BleScannerManager.kt` | Scanner starts **once per session** (guarded by `scanningStarted`), not on every `onStartCommand`; GATT RSSI polling is adaptive: 1000 ms at limit/link-risk/imminent, 3000 ms moving away, 5000 ms safe, stopped when disconnected/unavailable; classic discovery skipped when a BLE scan already has a result |
| `service/BleForegroundService.kt` | Scanner-once-per-session wiring; passes proximity state to the scanner |
| `util/ConnectionMonitor.kt` | Fallback connection poll backs off 10 s → 30 s when profile broadcast events are already arriving |
| `util/DistanceEstimator.kt`, `util/KalmanFilter.kt`, `model/BleDevice.kt` | Calibration/distancing refinements supporting the above |
| `ui/AlertScreen.kt`, `ui/DeviceSelectionScreen.kt`, `ui/PermissionRequestScreen.kt`, `ui/SettingsScreen.kt`, `ui/DiagnosticsScreen.kt`, `MainActivity.kt`, `MainViewModel.kt` | Wiring for the new states/messages (216-line ViewModel change consolidates device-selection paths) |
| `app/src/test/**` (new) | 4 unit-test classes listed in §2 — first automated tests in the repo |
| `app/src/androidTest/**` (new) | 3 instrumented flows: DisconnectRecording, ForegroundService, PermissionFlow |

Earlier commits `e76e203` and `8a67743` introduced the probe/monitor architecture and the RSSI scaling/close-range calibration; read those diffs before touching the same files.

## 4. Open work — the next agent's worklist

The owner reviewed this build and **rejected it**. The code changes above were accepted as bug fixes; the rejection is about product completeness and UI quality. Do these before calling it done:

1. **UX audit not implemented.** A 34 KB audit of this app exists (was produced outside the repo, in the bot's workspace, filename `UX_AUDIT_BLE_PROXIMITY_ALERT.md` — ask the owner for it if it was not handed over). Its P0 items, none of which are coded:
   - New `OnboardingScreen.kt`: first-run onboarding + honest capability explanation (what live distance needs, which devices can't do it)
   - `AlertScreen`: actionable instruction text + auto-dismiss behaviour
   - `DeviceSelectionScreen` (DeviceCard): clear **connected vs paired** distinction
   - `PermissionRequestScreen`: checklist rebuild
2. **App icon never changed** — the launcher icon is still the default template icon. Debossed/replaced icon is outstanding.
3. **No exit confirmation** — leaving the app (back gesture / recents swipe) gives no confirmation that monitoring continues or that the service was stopped. Decide and implement the correct behaviour (the owner expects a confirmation).
4. **UI quality.** The owner's verdict was blunt: "UI is still bad", "I see AI slop in everything". Treat the current screens as a rejected first pass, not a base to extend. Real design work needed: typography, spacing rhythm, empty/loading/error states, no template patterns.
5. **Classic-only device experience.** Verify the honest-unavailable flow end-to-end on an A2DP/HFP-only device (Airdopes 311) and make sure nothing in the UI implies distance is measurable when it isn't.

## 5. Getting started

```bash
./gradlew testDebugUnitTest   # 44 tests must pass
./gradlew assembleDebug       # APK → app/build/outputs/apk/debug/
```

Keep the adaptive-poll design: RSSI polling rate must track proximity state, and the scanner must not restart on every service command — that was a real battery bug.

The project owner has rejected the current UX. Confirm the acceptance bar with them before large UI work: they asked for "just like making the app work" — UI and function treated as one job, not a polish pass at the end.