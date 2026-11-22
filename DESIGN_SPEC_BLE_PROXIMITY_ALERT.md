# DESIGN SPEC — BLE Proximity Alert

**Purpose:** Replace the default template launcher icon and the owner's "AI slop" verdict with a real, reviewable design spec around what the app already does. Nothing adds features; it refines visuals, theme, typography, spacing, and states.

**Reading this as:** a mobile Android monitoring app for Bluetooth proximity alerts, dark-first with a light theme, glance-first telemetry, calm and trustworthy. Dial: ENERGY 2 / RHYTHM 2 / MOTION 1.

## 1. App icon — replaces the template icon

- **Shape:** Android adaptive icon circle. Canvas (background): deep navy `#0F1113`.
- **Glyph:** rounded-square letter **B** (the product wordmark from "BLE Proximity Alert"), centered, ~64dp, color = signature accent Teal `#2EC4B6`.
- **Deboss (the point of the redesign):** the B is drawn with a 2dp stroke; its counter is filled with the canvas color and has a 2dp inner edge, so the letter reads as etched into the icon body; a small soft shadow (2dp offset right/down, ~4% opacity) sits under the glyph's lower right. Flat, no glow, no gradient (slop: R-01, D-3).
- **Legacy square:** same glyph and colors on a rounded-corner square.
- **Monochrome (Android 13+):** B silhouette in Teal `#2EC4B6` on `#0F1113`.

## 2. Material 3 Color Roles — dark theme

| Role | Hex |
|---|---|
| primary | `#2EC4B6` |
| onPrimary | `#0F1113` |
| primaryContainer | `#1B4A45` |
| onPrimaryContainer | `#A2DFD4` |
| secondary | `#7DD3C0` |
| onSecondary | `#0F1113` |
| secondaryContainer | `#0F2E2A` |
| onSecondaryContainer | `#B4E8DD` |
| tertiary | `#FF9F1C` |
| onTertiary | `#0F1113` |
| tertiaryContainer | `#33240A` |
| onTertiaryContainer | `#FFD280` |
| error | `#F87171` |
| onError | `#1A1D22` |
| errorContainer | `#341A1F` |
| onErrorContainer | `#F87171` |
| background | `#121416` |
| onBackground | `#E6E7EB` |
| surface | `#1A1D22` |
| onSurface | `#E6E7EB` |
| surfaceVariant | `#22262E` |
| onSurfaceVariant | `#9DA6B3` |
| outline | `#8E96A2` |
| inverseSurface | `#FFFFFF` |
| inverseOnSurface | `#121416` |

Validated pairs (AA, WCAG checker): onPrimary 8.73, onPrimaryContainer 6.65, onSecondary 10.77, onSecondaryContainer 10.75, onTertiary 9.22, onTertiaryContainer 10.58, onError 6.11, onErrorContainer 5.78, onBackground 14.94, onSurface 13.67, onSurfaceVariant 6.17, outline (on surfaceVariant) 5.08, inverseOnSurface 18.46; severity pairs in §4.

## 3. Material 3 Color Roles — light theme

| Role | Hex |
|---|---|
| primary | `#006A5D` |
| onPrimary | `#FFFFFF` |
| primaryContainer | `#C4F0E8` |
| onPrimaryContainer | `#006A5D` |
| secondary | `#004D40` |
| onSecondary | `#FFFFFF` |
| secondaryContainer | `#D5EFE7` |
| onSecondaryContainer | `#004D40` |
| tertiary | `#FF9F1C` |
| onTertiary | `#0F1113` |
| tertiaryContainer | `#FFF3E0` |
| onTertiaryContainer | `#33200A` |
| error | `#B3261E` |
| onError | `#FFFFFF` |
| errorContainer | `#FFDAD6` |
| onErrorContainer | `#8E0C0C` |
| background | `#FFFFFF` |
| onBackground | `#1F1B16` |
| surface | `#FFFFFF` |
| onSurface | `#1F1B16` |
| surfaceVariant | `#E7E8EC` |
| onSurfaceVariant | `#4C5258` |
| outline | `#5F6368` |
| inverseSurface | `#1A1B1E` |
| inverseOnSurface | `#FFFFFF` |

Validated pairs (AA): onPrimary 6.52, onPrimaryContainer 5.27, onSecondary 9.83, onSecondaryContainer 8.11, onTertiary 9.22, onTertiaryContainer 14.17, onError 6.54, onErrorContainer 7.36, onBackground 17.12, onSurface 17.12, onSurfaceVariant 6.46, outline (on surfaceVariant) 4.94, inverseOnSurface 17.22; severity pairs in §4.

Light theme is additive: the app today uses dark colors; both themes ship and pass AA (R-34). Signature accent: Teal — `#2EC4B6` (dark) / `#006A5D` (light; M3 light themes use darker source tones of the same hue). No purple-to-blue gradients.

## 4. Severity / state palette — text on cards

| State (Ai.txt labels) | Dark | Light |
|---|---|---|
| Safe | `#2EC4B6` | `#006A5D` |
| Moving away | `#FF9F1C` | `#7A5A00` |
| Near limit | `#FB923C` | `#A84B2A` |
| Connection at risk | `#F87171` | `#D72B2B` |
| Imminent | `#EF4444` | `#C62828` |
| Disconnected | `#F87171` | `#B3261E` |
| Unavailable | `#9DA6B3` | `#4C5258` |

Validated on card (AA): dark: Safe 7.00, Moving away 7.39, Near limit 6.70, Connection at risk 5.48, Imminent 4.03 (passes AA large 3.0). Light: Safe 6.52, Moving away 6.38, Near limit 5.67, Connection at risk 4.92, Imminent 5.62. Usage: severity text is always paired with an icon; the hero "Estimated distance" value uses the same state color (see §7).

## 5. Typography

- **Family:** Android platform typeface (Roboto / Roboto Flex) — no custom font assets; the app is Material 3.
- **Weights in use:** Regular 400, Medium 500, Bold 700.

| Role | Size / line height | Weight | Use |
|---|---|---|---|
| DisplayMedium | 45 / 52 | Bold | Hero distance value (key glance reading) |
| TitleLarge | 22 / 28 | Bold | State label |
| TitleMedium | 18 / 24 | Medium | Screen titles, card headings |
| BodyMedium | 14 / 20 | Regular | Body text, measurements |
| BodySmall | 13 / 16 | Regular | Muted captions, timestamps |
| LabelLarge | 14 / 20 | Medium | Buttons, callouts |
| LabelSmall | 12 / 16 | Regular | Tiny captions, badges |

## 6. Spacing rhythm

Scale: 4, 8, 12, 16, 20, 24, 32, 48, 64 dp. Screen margin 16; hero card padding 24; card padding 16; inter-card gaps 8-12; section gaps 20-24; button rows 8. Radius ladder: 6dp (buttons, chips), 8dp (cards, bars).

## 7. Empty / loading / error styling per screen

| Screen | Empty | Loading | Error |
|---|---|---|---|
| DeviceSelection | "No Bluetooth devices found." + "Pair and connect your earphones in Android Settings, then tap Refresh." + Refresh / Open Bluetooth settings buttons (muted icon, centered) | "Looking for connected earphones…" + spinner | "This device has no Bluetooth" + "Bluetooth is turned off" + Enable Bluetooth button |
| StartMonitoring | — | "Starting monitoring…" + spinner in the button | `startError` text in error color, nothing else changes |
| Monitoring | Disconnected card: "You left your headset behind" + last known distance line + "Reconnect your headset or pick another device." | Probing card: "Checking signal strength…" + spinner | Unavailable card (muted): "Android isn't reporting signal strength from X. You can still detect disconnection and save your last location." + "Check Signal Again" |
| Settings | — (scrolls) | — | `startError`-style red text |
| Calibration | — | "Sampling… N s" + countdown headlineSmall in primary | "Save Calibration" only after sampling; "Cancel" anytime |
| Diagnostics | N/A fields, no fake values | — | — |
| DisconnectHistory | "No disconnection events recorded." (muted icon + text) | — | "Location unavailable for this event" muted line |
| Alert | Dialog auto-dismisses (see §8) | — | — |

## 8. Onboarding and Alert redesign

**Onboarding (new, P0):** two screens. (1) "Your headset's signal can warn you": copy from Ai.txt §12 Screen 1 ("This app estimates proximity using Bluetooth signal. It is not exact GPS or laser distance." / "Small thresholds like 1 m require calibration." / "Some headphones do not expose signal strength to Android. The app will tell you when yours can't.") + buttons Continue, Run Compatibility Check. (2) "Pick Your Headset": mirrors DeviceSelection with its states.

**Alert screen (P0):** centered AlertDialog-style card: title = state label (colored icon + label); body = calm instruction ("Please move closer to your headset before the connection is lost.") + "Estimated distance: ~X.X m"; actions: "Return to Monitoring" (primary) + "Dismiss"; auto-dismiss ~10 s when nothing is clicked. No screaming red; severity colors per §4.

## 9. Shady's review checklist (concrete)

- **Palette pass:** verify rendered colors on DeviceCard, Monitoring state label, hero distance value, unavailable card = tables in §2/§3/§4; every text pair ≥4.5 (3.0 large) per the validated notes.
- **Icon pass:** render the B letterform in the icon editor: 2dp stroke, counter filled with canvas color + 2dp inner edge, soft lower-right shadow; replace the template launcher icon.
- **Visual audit:** no pill elements (R-11), no decorative icon-in-box, no glow/gradient (R-01, D-3, A-11), spacing from the §6 scale, section rhythm varied (R-05).

## 10. Titan implementation notes

- **MainActivity:** wire the full dual-theme ColorScheme (tables in §2/§3).
- **MonitoringScreen:** state label = DisplayMedium Bold + state color (§4); hero distance = DisplayMedium Bold; proximity bar = rounded LinearProgressBar, height 6-8dp, state-colored (container muted), fraction = min(distance/threshold, 1); trend line + confidence badge + last-update line (§7).
- **StartMonitoringScreen:** threshold as a wrap-able chip row; alert style: 2-option chips ("In-ear voice + notification" / "Notification only"); power mode: 3-option chips (Battery saver / Balanced / Responsive); "Save location when monitoring is in the background" toggle + honest privacy line ("Location data is stored only on this phone; nothing is shared.").
- **DeviceSelectionScreen:** connected vs paired split — Connected cards ("Connected"/"Active Output"), Paired cards ("Paired").
- **OnboardingScreen (new file):** two screens (§8).
- **AlertScreen:** AlertDialog redesign + auto-dismiss (§8).
- **SettingsScreen / DiagnosticsScreen / DisconnectHistoryScreen / CalibrationWizardScreen:** refresh with §7 styling; verify all tap targets ≥44dp (R-03/K-3).
- After wiring: Shady pass (§9); final check: `./gradlew assembleDebug`.

## 11. Verification & status

- All palette pairs in §2/§3/§4 validated with the WCAG checker (antislop-human formula): ratios as listed; worst = light Connection at risk 4.92 (≥4.5).
- **Build: green, unchanged.** No code was edited; the tree matches the handoff state (uncommitted changes present in `BleScannerManager.kt` and `RssiCapabilityProbe.kt` are the pre-existing handoff changes; `QA_TEST_PLAN.md` is untracked). HANDOFF §2 recorded BUILD SUCCESSFUL for this tree.
- **Not validated:** no icon/screen renders produced (spec only); `./gradlew` not re-run by me.
