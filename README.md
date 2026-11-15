# BLE Proximity Alert

An Android app that reminds users when they are moving away from their phone while wearing Bluetooth earphones. The alert plays through the earphones themselves based on Bluetooth signal strength.

## Concept

- User wears Bluetooth earphones connected to their phone
- App monitors the Bluetooth signal to the earphones
- When the user walks away from the phone, the signal weakens
- App triggers a voice alert **through the earphones** so the user hears it immediately
- No second phone, no beacon mode, no random-device noise

## Tech Stack

- **Language**: Kotlin
- **UI**: Jetpack Compose + Material 3
- **Architecture**: MVVM with ViewModel + StateFlow
- **BLE**: Native Android Bluetooth APIs
- **Audio**: TextToSpeech routed through connected Bluetooth audio device
- **Min SDK**: 24 (Android 7.0)
- **Target SDK**: 36 (Android 16)

## How It Works

1. User opens the app
2. App shows paired Bluetooth audio devices (earphones/headsets)
3. User selects their earphones
4. App starts monitoring:
   - Bluetooth connection state via `BroadcastReceiver`
   - BLE/RSSI signal strength if available
5. When signal drops below threshold or device disconnects:
   - Alert voice plays through the earphones
   - Persistent notification appears
6. User dismisses alert when they return to the phone

## Key Features

- **Earphone-only tracking**: No random Bluetooth device warnings
- **In-ear alert**: Voice plays through connected Bluetooth audio device
- **Connection monitoring**: Detects disconnection via Android Bluetooth profiles
- **RSSI-based distance**: Optional signal strength monitoring when available
- **Foreground service**: Keeps monitoring active in background
- **Clean UI**: Simple device selection and alert flow
