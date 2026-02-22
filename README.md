# SCRCPY Android-to-Android FullProto

Android app prototype to view and control one Android device from another Android device, using `scrcpy-server` over `ADB over Ethernet` (Wi-Fi debugging).

Current app version in code: `0.1.0-m1` (`app/build.gradle`).

## 1. What this project does

- Controller device (example: machine B) runs this app.
- Target device (example: machine C) exposes Wireless debugging (`IP:PORT`).
- App-side embedded ADB binary (`libadb.so`) handles:
  - `adb connect`
  - optional `adb pair`
  - push `scrcpy-server.jar`
  - port forwarding
  - remote shell to start scrcpy server
- Video stream is decoded via `MediaCodec` and rendered to `SurfaceView`.
- Input control is sent over scrcpy control socket (touch, swipe, key events).

## 2. Current feature set

- Wi-Fi debug connect by `IP + port`.
- Optional pairing flow by `pair port + pair code`.
- H.264 video decode and live display.
- Touch injection:
  - tap
  - long-press
  - drag/swipe
  - pointer-based multi-touch events
- Android navigation quick keys: Back, Home, Recents.
- Stream tuning:
  - max resolution (`Auto/240p/360p/480p/720p/1080p`)
  - max FPS (`Auto/30/45/60`)
  - stretch vs letterbox
- Session UI behavior:
  - app start: show Session Controls
  - connected: hide Session Controls, show floating `...` and nav pill
  - floating controls are draggable to reduce screen obstruction

## 3. Architecture overview

Project modules:

- `app`
  - UI + orchestration (`MainActivity`)
  - video decode client
  - control client
  - custom `AspectRatioSurfaceView`
  - assets:
    - `app/src/main/assets/scrcpy-server.jar`
    - `app/src/main/jniLibs/arm64-v8a/libadb.so`
- `adb-core`
  - `NativeAdbBridge` shells app-bundled `libadb.so`
- `scrcpy-proto`
  - wire parsing for codec metadata and frame packets

Key files:

- `app/src/main/java/com/example/scrcpyandroidfullproto/MainActivity.java`
- `app/src/main/java/com/example/scrcpyandroidfullproto/ScrcpyVideoClient.java`
- `app/src/main/java/com/example/scrcpyandroidfullproto/ScrcpyControlClient.java`
- `adb-core/src/main/java/com/example/scrcpy/adb/NativeAdbBridge.java`
- `scrcpy-proto/src/main/java/com/example/scrcpy/proto/ScrcpyVideoStreamReader.java`
- `app/src/main/res/layout/activity_main.xml`

## 4. Environment requirements

Minimum for local build in Termux:

- Android device with Termux
- JDK 17
- Android SDK:
  - `platforms;android-34`
  - `build-tools;34.0.0` (or compatible)
  - platform-tools
- Gradle wrapper (included)

This project currently assumes:

- `sdk.dir=/data/data/com.termux/files/home/android-sdk` in `local.properties`
- `minSdk 21`, `targetSdk 34`
- Controller app device is `arm64-v8a` (because bundled `libadb.so` is arm64)

## 5. Build instructions

From project root:

```bash
cd /data/data/com.termux/files/home/scrcpy-android-fullproto
chmod +x gradlew
./gradlew clean :app:assembleDebug
./gradlew :app:assembleRelease
```

Artifacts:

- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Release APK (unsigned by default): `app/build/outputs/apk/release/app-release-unsigned.apk`

### Optional: sign release APK (recommended)

```bash
cd /data/data/com.termux/files/home/scrcpy-android-fullproto
keytool -genkeypair -v -keystore release.keystore -alias scrcpy_fullproto \
  -keyalg RSA -keysize 2048 -validity 10000

/data/data/com.termux/files/home/android-sdk/build-tools/34.0.0/apksigner sign \
  --ks release.keystore \
  --out app/build/outputs/apk/release/app-release-signed.apk \
  app/build/outputs/apk/release/app-release-unsigned.apk

/data/data/com.termux/files/home/android-sdk/build-tools/34.0.0/apksigner verify \
  app/build/outputs/apk/release/app-release-signed.apk
```

## 6. Install and run on controller device (machine B)

Example using ADB over Wi-Fi:

```bash
adb connect 192.168.1.250:7777
adb -s 192.168.1.250:7777 install -r app/build/outputs/apk/debug/app-debug.apk
```

Open app and configure:

1. `Target device IP`: target phone IP (example `192.168.1.253`).
2. `Debug port`: wireless debug connect port (example `7777`).
3. If pairing is required:
   1. set `Pairing Port`
   2. set `Pairing Code`
4. `Local Forward Port`: local TCP port on controller (example `7008`).
5. Tap `Connect`.

## 7. Typical workflow (B controls C)

1. On target C:
   - enable Developer options
   - enable Wireless debugging
   - keep screen on during first authorization
2. On controller B:
   - open app
   - enter C IP/port
   - connect
3. After connected:
   - use remote touch on full video surface
   - use nav pill (Back/Home/Recents)
   - tap `...` to open/close stream tuning panel

## 8. Auto-authorize ADB (root-only utility)

Script: `scripts/auto_auth_c.sh`

Purpose:

- collect controller ADB pubkey(s)
- push keys to target `/data/misc/adb/adb_keys` via SSH root
- restart `adbd` to accept keys without manual dialog

Example:

```bash
cd /data/data/com.termux/files/home/scrcpy-android-fullproto
HOST=192.168.1.253 \
SSH_PORT=8022 \
SSH_USER=root \
SSH_PASSWORD='<YOUR_SSH_PASSWORD>' \
ADB_SERIAL_B=192.168.1.250:7777 \
./scripts/auto_auth_c.sh
```

Important:

- This is high risk and weakens ADB trust model.
- Use only on owned test devices, never on production/user devices.

## 9. Troubleshooting

### `NumberFormatException` when pressing Connect

- Root cause: direct `Integer.parseInt()` on empty/invalid input.
- Current fix: centralized `parsePort()` validation in `MainActivity`.
- Action: ensure fields are numeric, in `1..65535`, or left empty for defaults where allowed.

### `ADB start error: ADB core will be implemented in M1`

- Cause: old build used `StubAdbBridge`.
- Action: use current build where `NativeAdbBridge` is wired.

### `adb failed ... device offline`

- On target C, toggle Wireless debugging off/on.
- Reconnect and re-authorize key.
- If still offline, run pairing again.

### `adb failed ... device not found`

- Wrong connect port or stale IP.
- Re-check Wireless debugging `Connect port`.
- Run pairing flow first if target rotated connect token.

### `Stream error: EOFException` or timeout

- Usually scrcpy server not fully started or forward/socket race.
- Retry Connect.
- Lower stream settings (`240p`, `30 fps`).
- Check that target C is awake and not heavily throttled.

### Black screen with no interaction

- Confirm `statusText` eventually shows `Decoder configured`.
- If not, reconnect and verify `scrcpy-server.jar` push succeeds.
- Test with lower resolution/fps first.

## 10. Test checklist before release

Run every release candidate:

1. Build success:
   - `./gradlew :app:assembleDebug`
   - `./gradlew :app:assembleRelease`
2. Install success on controller device.
3. Connect from B to C by IP/port.
4. Verify display:
   - no black screen
   - aspect mode toggles work
5. Verify controls:
   - tap, long-press, swipe
   - nav Back/Home/Recents
   - open/close control panel by `...`
6. Verify disconnect/reconnect stability.

## 11. GitHub publish flow (public repo + release APK)

If using plain git (no `gh` CLI):

1. Create a new empty public repo on GitHub web.
2. In local project:

```bash
cd /data/data/com.termux/files/home/scrcpy-android-fullproto
git init
git add .
git commit -m "Initial public release: v0.1.0-m1"
git branch -M main
git remote add origin <YOUR_GITHUB_REPO_URL>
git push -u origin main
```

3. Create GitHub release on web:
   - tag: `v0.1.0-m1`
   - upload APK (`app-release-signed.apk` preferred)

## 12. Known limitations

- No audio forwarding.
- Only H.264 path is wired.
- No dynamic bitrate adaptation.
- No reconnection state machine for unstable networks.
- No runtime language switch (English strings only at this stage).
- Root-only automation script is external and optional.

## 13. Upgrade roadmap suggestion

- M1.1:
  - robust reconnect state machine
  - better gesture smoothing
  - persistent profile presets
- M1.2:
  - low-latency mode and adaptive tuning
  - metrics overlay (fps, decode latency)
- M2:
  - hardened security model
  - optional relay/web gateway mode
