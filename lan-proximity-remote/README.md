# LAN Proximity Remote

A simple Android app that uses Bluetooth signal strength (RSSI) to automatically select the nearest device on your local network, then sends UDP commands to control it (Play/Pause, Volume, etc.).

## Features
- Auto proximity switching using BLE
- Manual device selection mode
- Clean dark glass-style UI
- Sends simple UDP commands (port 9876 by default)

## How to get the APK

1. Push this project to the `main` branch of your GitHub repository.
2. Go to the **Actions** tab.
3. Wait for the workflow "Build LAN Remote APK" to finish (green check).
4. Click on the latest successful run.
5. Download the artifact named **LAN-Remote-APK**.
6. Inside the zip you will find `app-debug.apk`.
7. Transfer the APK to your phone and install it (allow "Install from unknown sources").

## Important Notes for Beginners
- The default device IPs are examples (`192.168.31.101` and `192.168.31.102`). Change them in `MainActivity.kt` to match your real devices.
- For the proximity feature to work, your target devices need to advertise a BLE name in this format:  
  `LAN|DEVICE_ID|IP_ADDRESS|PORT`  
  Example: `LAN|LIVING_TV|192.168.31.101|9876`
- If you don't have BLE advertisers, the app still works in Manual mode.

## Changing Device IPs
Open the file:  
`app/src/main/java/com/mahor/lanremote/MainActivity.kt`

Find the `seedDefaultDevices()` function and edit the IP addresses.
