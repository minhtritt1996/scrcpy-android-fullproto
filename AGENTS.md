# AGENTS.md - Maintainer Guide for Future Upgrades

This file defines how coding agents should work on this repository to avoid regressions in remote control, streaming, and UI behavior.

## 1. Mission

Maintain and upgrade this Android-to-Android scrcpy client while preserving:

- stable ADB-over-Ethernet connection flow
- reliable video decode pipeline
- responsive remote input (tap, hold, swipe, multi-touch)
- predictable UI state transitions

## 2. Current baseline (must not regress)

- App launch:
  - Session Controls visible
  - `...` floating button hidden
  - nav pill hidden
- After successful connect:
  - Session Controls hidden by default
  - `...` floating button visible and draggable
  - nav pill visible and draggable
- Stream controls available via overlay panel.
- Input works for tap, long-press, drag/swipe, nav keys.

## 3. Repository map

- `app/`
  - `MainActivity.java`:
    - end-to-end connect/start/disconnect flow
    - UI state toggling
    - touch mapping and gesture forwarding
  - `ScrcpyVideoClient.java`:
    - socket connect/retry
    - packet read
    - `MediaCodec` decode/render
  - `ScrcpyControlClient.java`:
    - control socket
    - input event serialization and queueing
  - `view/AspectRatioSurfaceView.java`:
    - letterbox vs stretch measuring
  - `res/layout/activity_main.xml`:
    - overlay ordering and visibility defaults
  - assets:
    - `assets/scrcpy-server.jar`
    - `jniLibs/arm64-v8a/libadb.so`
- `adb-core/`
  - `NativeAdbBridge.java`:
    - `adb pair/connect/push/forward/shell`
- `scrcpy-proto/`
  - scrcpy stream metadata and packet readers
- `scripts/auto_auth_c.sh`
  - optional root-only ADB auth automation

## 4. Non-negotiable technical rules

1. Do not remove or break `NativeAdbBridge` connection flow.
2. Do not replace pointer-based touch injection with single-point hacks.
3. Keep strict input validation for ports (`1..65535`) to prevent crashes.
4. Preserve control socket queue backpressure behavior.
5. Keep UI controls off the main content by default when connected.
6. Keep immersive mode behavior functional during active session.

## 5. UI/UX guardrails

When changing UI:

- Keep remote video as the primary visual layer.
- Keep floating controls movable and minimally intrusive.
- Avoid always-visible large panels during active control.
- Keep text and icon contrast readable in both bright and dark content.
- Avoid adding controls that duplicate existing function without clear value.

## 6. Touch and control rules

When touching `MainActivity.handleTouch()` or `ScrcpyControlClient`:

- Keep `pointerId` mapping stable.
- Keep separate handling for `DOWN`, `MOVE`, `UP`, `CANCEL`.
- Preserve pressure normalization on UP (`0f`).
- Do not block UI thread with network or socket operations.
- Maintain key events fallback through `adb shell input keyevent` when control socket is not ready.

## 7. Streaming rules

When touching `ScrcpyVideoClient`:

- Keep connect retry behavior for startup race conditions.
- Keep decoder reconfigure path on config packets.
- Keep read timeout protection (`STREAM_READ_TIMEOUT_MS`).
- Any decode change must be tested on real device, not emulator-only.

## 8. ADB and security rules

- `libadb.so` execution path is app-internal and required.
- Do not log sensitive tokens, pairing code, or auth material.
- Root automation script is optional and unsafe by design.
- Never enable insecure auto-auth behavior in main app flow by default.

## 9. Build and verification protocol

Required commands before merge:

```bash
cd /data/data/com.termux/files/home/scrcpy-android-fullproto
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

Minimum manual validation:

1. Install debug build on controller device.
2. Connect to target via Wi-Fi debug IP/port.
3. Confirm `Decoder configured` appears.
4. Verify:
   - tap
   - long-press
   - swipe
   - Back/Home/Recents
5. Toggle stream tuning panel with `...`.
6. Disconnect and reconnect once.

## 10. Release protocol

For each release:

1. Update version in `app/build.gradle`:
   - `versionCode`
   - `versionName`
2. Build release APK.
3. Sign release APK.
4. Verify signed APK with `apksigner verify`.
5. Tag repository (`vX.Y.Z`).
6. Publish GitHub Release with APK asset.

## 11. Commit discipline

- Keep commits scoped:
  - `feat(ui): ...`
  - `fix(adb): ...`
  - `fix(input): ...`
  - `docs: ...`
- Do not mix refactor and behavior changes without reason.
- Include at least one verification note in commit message body for risky changes.

## 12. Priority backlog for next upgrades

1. Improve gesture smoothness and event synchronization under load.
2. Add adaptive stream presets (latency vs quality).
3. Add robust reconnect state machine with explicit states.
4. Add diagnostics panel (adb state, forward port, stream stage).
5. Add crash-safe session recovery on app resume.

## 13. Done criteria for agent tasks

A task is complete only when:

1. Code compiles.
2. Critical flow still works (`connect -> stream -> control -> disconnect`).
3. No obvious UI obstruction regressions.
4. README and user-facing behavior notes are updated if flow changed.
5. Release artifact path is reported if task requested build output.

