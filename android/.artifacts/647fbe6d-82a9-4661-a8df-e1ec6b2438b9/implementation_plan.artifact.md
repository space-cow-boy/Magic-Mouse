# Add Bluetooth Connectivity to Magic Mouse

This plan adds Bluetooth Classic (RFCOMM / Serial Port Profile) connectivity alongside the existing Wi-Fi UDP connection, allowing users to connect their Android phone to their PC via Bluetooth.

## User Review Required

> [!IMPORTANT]
> **Permissions**: Android 12+ (API 31+) requires `BLUETOOTH_CONNECT` and `BLUETOOTH_SCAN` runtime permissions, which will be requested when connecting via Bluetooth.
> **Receiver Requirement**: The Windows receiver companion app will need to support listening on a Bluetooth RFCOMM Server Socket (using the standard SPP UUID `00001101-0000-1000-8000-00805F9B34FB`) in addition to UDP.

## Open Questions

- Should Bluetooth support both paired device selection and discovery, or focus primarily on paired devices (PCs already paired in Windows/Android settings)? *Focusing on paired devices is standard and most reliable for mouse/hid controllers.*

## Proposed Changes

### Android Manifest

#### [MODIFY] [AndroidManifest.xml](file:///C:/Users/Sandeep%20Singh/Downloads/MagicMouse/android/app/src/main/AndroidManifest.xml)
- Add Bluetooth permissions: `BLUETOOTH`, `BLUETOOTH_ADMIN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`.

### Bluetooth Networking

#### [NEW] [BluetoothClient.kt](file:///C:/Users/Sandeep%20Singh/Downloads/MagicMouse/android/app/src/main/java/com/magicmouse/android/network/BluetoothClient.kt)
- Create a `BluetoothClient` class mirroring `UdpClient`:
  - Manages `BluetoothAdapter` and `BluetoothSocket`.
  - Connects via RFCOMM using standard SPP UUID (`00001101-0000-1000-8000-00805F9B34FB`).
  - Sends raw packet byte arrays (`Protocol` packets) over `BluetoothSocket.outputStream`.
  - Provides helper methods to get paired devices list (`getPairedDevices()`).

### Controller & ViewModel

#### [MODIFY] [MouseController.kt](file:///C:/Users/Sandeep%20Singh/Downloads/MagicMouse/android/app/src/main/java/com/magicmouse/android/controller/MouseController.kt)
- Integrate `BluetoothClient` alongside `UdpClient`.
- Add `connectBluetooth(deviceAddress: String)` and update gesture/motion dispatching to send via Bluetooth if Bluetooth is connected.
- Update connection state handling.

#### [MODIFY] [MainViewModel.kt](file:///C:/Users/Sandeep%20Singh/Downloads/MagicMouse/android/app/src/main/java/com/magicmouse/android/main/MainViewModel.kt)
- Expose Bluetooth connection methods and paired devices list.

### UI Updates

#### [MODIFY] [MainScreen.kt](file:///C:/Users/Sandeep%20Singh/Downloads/MagicMouse/android/app/src/main/java/com/magicmouse/android/ui/MainScreen.kt)
- Add connection method selector (Wi-Fi UDP vs Bluetooth).
- For Bluetooth: Show paired devices dropdown/list, runtime permission request handling for Android 12+, and Connect/Disconnect buttons.

## Verification Plan

### Automated Tests
- Build project using gradle (`app:assembleDebug`) to ensure compilation succeeds without errors.

### Manual Verification
- Deploy app to an Android device/emulator, grant Bluetooth permissions, verify paired Bluetooth devices are listed, and test connection flow.
