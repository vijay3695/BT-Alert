# BLE Proximity Alert - Architecture, Goal, and Implementation Mechanisms

## 1. Project Goal & Vision
The goal of **BLE Proximity Alert** is to build a proactive Android mobile application that monitors a user's currently **connected Bluetooth headphone/earphone** (e.g., Sony WF-C500, AirPods, neckbands, A2DP/HFP headsets) and delivers a **Voice Alert (Text-to-Speech)** directly through the connected earphones **BEFORE** the user moves too far away and the connection drops.

### Core Objectives:
1. **Detect Connected Devices**: Identify Bluetooth audio devices that are ALREADY paired and connected to the phone (without initiating new pairings).
2. **Live Distance Estimation**: Continuously estimate distance between the phone and the connected headphone using Bluetooth signal metrics (RSSI).
3. **Threshold-Based Alerts**: Allow the user to configure a warning distance threshold (9m, 8m, 7m, 6m, 5m, 4m, 3m, 2m, 1m, 0.5m, 0.1m).
4. **Smart Moving-Away Trend Detection**: Predict imminent disconnection by analyzing RSSI degradation over time (RSSI drop over a short window).
5. **In-Ear Voice Alert Delivery**: Deliver TTS warnings directly through the connected Bluetooth headphones via `STREAM_MUSIC` / `USAGE_MEDIA` before link loss occurs.
6. **Foreground Service Monitoring**: Continue scanning and processing signal trends in the background when the phone screen is off or the app is minimized.

---

## 2. Technical Mechanisms & Architecture

```
+-----------------------------------------------------------------------------------+
|                                 BleApplication                                    |
|                   (Notification Channels: SCAN, SERVICE, ALERT)                   |
+-----------------------------------------+-----------------------------------------+
                                          |
                                          v
+-----------------------------------------------------------------------------------+
|                                BleScannerManager                                  |
|  - Classic Bluetooth Profile Monitor (A2DP & Headset connected device discovery)  |
|  - Multi-Transport GATT Reader (connectGatt over BR/EDR & LE)                     |
|  - Classic Discovery Loop (bluetoothAdapter.startDiscovery & ACTION_FOUND)        |
|  - BLE Scanner (BluetoothLeScanner + Name/MAC matching)                           |
+-----------------------------------------+-----------------------------------------+
                                          |
                                          v
+-----------------------------------------------------------------------------------+
|                              Signal Processing Layer                              |
|  - KalmanFilter: 1D Gaussian Noise Reduction on RSSI measurements                |
|  - DistanceEstimator: Path-Loss Model [d = 10 ^ ((txPower - RSSI) / (10 * n))]    |
+-----------------------------------------+-----------------------------------------+
                                          |
                                          v
+-----------------------------------------------------------------------------------+
|                                 AlertEngine                                       |
|  - ThresholdConfig: User-selected warning distance (0.1m - 9.0m)                  |
|  - Degradation Analyzer: Sliding window RSSI slope (>= 4 dBm drop in 3s)          |
|  - Alert Evaluation: Normal vs. Warning vs. Critical vs. Imminent Disconnect      |
+-----------------------------------------+-----------------------------------------+
                                          |
                                          v
+-----------------------------------------------------------------------------------+
|                               TextToSpeechHelper                                  |
|  - AudioAttributes: USAGE_MEDIA + CONTENT_TYPE_SPEECH                             |
|  - AudioFocus: AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK on STREAM_MUSIC                 |
|  - Target: Route TTS directly into connected Bluetooth earphones                  |
+-----------------------------------------------------------------------------------+
```

---

## 3. Key Implementation Mechanisms

### A. Connected Device Discovery & Identification
* **Challenge**: Standard BLE scanners (`BluetoothLeScanner`) only detect devices sending BLE Advertising packets over the air. However, Classic Bluetooth earphones (A2DP/HFP) stop emitting BLE advertisements once they are paired and connected.
* **Mechanism**:
  1. `BluetoothAdapter.bondedDevices` fetches paired devices.
  2. `BluetoothManager.getConnectedDevices(BluetoothProfile.GATT)` and `BluetoothAdapter.getProfileProxy` for `BluetoothProfile.A2DP` and `BluetoothProfile.HEADSET` discover active audio connections.

### B. Live RSSI Acquisition Methods Attempted
To continuously sample RSSI while connected, three parallel acquisition methods were implemented:
1. **Multi-Transport GATT Polling**: Calling `device.connectGatt(context, false, gattCallback, TRANSPORT_BREDR)` and issuing `gatt.readRemoteRssi()` every 1000ms.
2. **Classic Discovery Pings**: Periodically calling `bluetoothAdapter.startDiscovery()` every 3000ms and capturing `BluetoothDevice.ACTION_FOUND` broadcast intents with `EXTRA_RSSI`.
3. **Unfiltered BLE Scanning**: Running `BluetoothLeScanner.startScan()` without strict MAC filters and matching by device name (`"WF-C500"`) to capture resolvable private BLE beacon signals.

### C. RSSI Smoothing & Distance Modeling
* **1D Kalman Filtering**: Smooths noisy raw RSSI readings using process noise Q = 0.08 and measurement noise R = 4.0:
  - Prior Prediction: `priorCov = errorCovarianceP + Q`
  - Kalman Gain: `K = priorCov / (priorCov + R)`
  - Measurement Update: `estimatedRssi = priorEst + K * (rawRssi - priorEst)`
  - Covariance Update: `errorCovarianceP = (1 - K) * priorCov`
* **Log-Distance Path Loss Model**: Converts smoothed RSSI (dBm) into estimated distance (d in meters):
  - Formula: `d = 10 ^ ((txPower - RSSI) / (10 * n))`
  - Where `txPower = -59.0 dBm` and path-loss exponent `n = 2.2`.

### D. Smart Trend Detection & Alert Escalation
* **Degradation Detection**: Maintains a sliding window of recent RSSI samples (over 3000ms). If RSSI drops by >= 4 dBm over the window, `trendDegrading` becomes true.
* **Threshold Evaluation**:
  - `distanceMeters >= warningDistanceMeters`: Triggers `AlertLevel.WARNING`.
  - `trendDegrading && distance >= warningDistanceMeters * 0.8`: Escalates to `AlertLevel.WARNING` / `CRITICAL`.
  - `distanceMeters >= 7.0m || RSSI <= -80 dBm`: Triggers `AlertLevel.CRITICAL`.
  - `distanceMeters >= 9.0m || RSSI <= -84 dBm`: Triggers `AlertLevel.IMMINENT_DISCONNECT`.

### E. Earphone Audio Routing for Voice Warnings
* **AudioFocus & Media Routing**: `TextToSpeechHelper` configures `AudioAttributes`:
  - `USAGE_MEDIA`
  - `CONTENT_TYPE_SPEECH`
  - `KEY_PARAM_STREAM = STREAM_MUSIC`
  - Requests `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` prior to speaking so the voice warning plays inside the user's connected earphones.
* **Post-Disconnection Suppression**: When a device disconnects (`ACTION_ACL_DISCONNECTED`), `distanceMeters` is reset to `null` to avoid false alerts on the phone speaker after link loss.

---

## 4. Current Implementation Summary
* **Gradle & Build**: Upgraded to Kotlin `2.0.21` with Compose Compiler plugin `org.jetbrains.kotlin.plugin.compose`. Configured Gradle to build with JDK 17 (`C:/Users/harsh/.jdks/jbr-17.0.14`).
* **Android Manifest**: Declared permissions (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `ACCESS_FINE_LOCATION`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS`) and foreground service `BleForegroundService`.
* **State Machine & UI**: Refactored `MainActivity` with `AppScreen` state machine to manage navigation across `DeviceSelectionScreen`, `StartMonitoringScreen`, `MonitoringScreen`, `SettingsScreen`, `AlertScreen`, and `PermissionRequestScreen`.
