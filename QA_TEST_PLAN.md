# QA Acceptance Test Plan — BLE Proximity Alert

Scope: verify against `Ai.txt` §15 acceptance criteria + open work in `HANDOFF.md`. No code edits. Build must stay green (44 unit tests).

## Preconditions
- Device A: Android 13/14 phone; Device B headset dual-mode (Sony WF-C500) for RSSI path; Device C classic-only (Airdopes 311) for honest-unavailable path.
- Fresh install; revoke all permissions first for denied flows.
- Unit tests green: `./gradlew testDebugUnitTest`.

## Checklist (Pass/Fail)

| # | Test | Steps | Expected | Pass/Fail |
|---|---|---|---|---|
| 1 | Headset connects A2DP | Pair/connect WF-C500; open app | Device appears in selection | |
| 2 | Disconnect | Disconnect headset | State DISCONNECTED within a few seconds | |
| 3 | No RSSI source | Use Airdopes 311 | "Proximity unavailable", no fake distance | |
| 4 | GATT RSSI works | Connect WF-C500, start monitoring | Samples arrive; Diagnostics shows source GATT | |
| 5 | Uncalibrated | Threshold 1 m, no calibration | Warning: calibration required; no false precision | |
| 6 | Calibrated | Calibrate at 1 m and 3 m | Distance estimate + confidence displayed | |
| 7 | Moving away | Walk away | Trend indicator changes to moving away | |
| 8 | Warning threshold | Cross threshold | One voice alert after debounce, then cooldown | |
| 9 | Return | Walk back | State exits warning with hysteresis | |
| 10 | Disconnect TTS | Disconnect headset | No TTS on phone speaker; notification saved | |
| 11 | Location granted | Grant location; disconnect | Event stores lat/lon; map opens | |
| 12 | Location denied | Deny location; disconnect | Event saved without location; no crash | |
| 13 | Background | Background app | Foreground service continues monitoring | |
| 14 | Screen off | Turn screen off | Monitoring continues; no excessive battery drain | |
| 15 | Bluetooth perms denied | Deny BT perms | App explains disabled; no crash | |
| 16 | Android 13/14 notif | Deny notification perm | Notification permission handled | |
| 17 | Android 14 FGS | Start service on A14 | Service starts with correct declared type | |

## New RSSI probe + icon cases (added)

| # | Test | Steps | Expected | Pass/Fail |
|---|---|---|---|---|
| 18 | Classic-only probe (Airdopes 311) | Start Monitoring on classic-only headset | Probe finishes within ~12s; no hang; honest "unavailable" | |
| 19 | Classic inquiry RSSI | Airdopes 311 within range | If RSSI arrives, `promoteRssiAvailability()` flips UI to available; no stuck unavailable | |
| 20 | Dual-mode any source (WF-C500) | Start Monitoring | Live distance shows if GATT/BLE/classic inquiry succeeds | |
| 21 | Probe cancellation | Start then back out mid-probe | No crash; probe stops; no stale callbacks | |
| 22 | Bluetooth off mid-probe | Turn BT off during probe | Honest unavailable; no crash; re-check works | |
| 23 | Permission revoked mid-probe | Revoke BT/location mid-probe | Honest unavailable; explains disabled; no crash | |
| 24 | Launcher icon | Install APK | New earbud+signal-arcs icon on launcher | |
| 25 | Themed icon (Android 13+) | Enable themed icons | Icon adapts correctly | |

## Edge cases to verify on real device
- Dual-mode device: probe tries LE + BR/EDR + classic inquiry; no false "unavailable" (regression from e76e203/8a67743).
- Classic-only device: no UI implies measurable distance; re-check button works.
- Re-entrancy: single tap on Start Monitoring enough (no multi-tap).
- Scanner starts once per session; no restart on every onStartCommand.
- Adaptive polling: 1000 ms at limit/link-risk/imminent; 3000 ms moving away; 5000 ms safe; stopped when disconnected/unavailable.
- Classic discovery skipped when BLE scan has result.
- ConnectionMonitor fallback backs off 10 s→30 s when broadcasts arrive.
- Do not reset last-known distance/location; disconnect evidence preserved.
- No repeated connectGatt without closing prior GATT.
- No continuous GPS; no main-thread BT polling.
- Exit confirmation behavior on back/recents swipe (open work item 3).
- Onboarding + capability explanation (open work item 1) not yet coded.
- App icon still default (open work item 2).

## Open work not covered by code yet (from HANDOFF)
1. UX audit P0 items (onboarding, alert actionable text/auto-dismiss, connected vs paired, permission checklist).
2. App icon replacement.
3. Exit confirmation.
4. UI quality/design pass.
5. Classic-only end-to-end verification.

## Repro steps (selected)
- RSSI unavailable flow: pair Airdopes 311 → Start Monitoring → expect "Proximity unavailable" card + Re-check; no distance numbers; no crash.
- Hysteresis/cooldown: calibrate WF-C500 at 1m/3m; walk to threshold; confirm single voice alert then cooldown; walk back; confirm state exits warning only after hysteresis margin.
- Disconnect detection: monitor WF-C500; turn off headset; confirm DISCONNECTED within few sec; notification saved; no phone-speaker TTS.

## Notes
- Do not edit Gradle. Keep adaptive-poll design.
- Instrumented tests present: DisconnectRecording, ForegroundService, PermissionFlow. Run `./gradlew connectedAndroidTest` on device.