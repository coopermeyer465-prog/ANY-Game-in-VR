# Quest Headpose

`Quest Headpose` is a Quest 3S head-tracking window app plus a macOS receiver.

- The Quest app shows connection state, OpenXR state, yaw, pitch, roll, receiver acknowledgement, local network info, and recenter controls.
- The Quest app can enter a real OpenXR `NativeActivity` that renders a black immersive scene while headpose streaming continues.
- The Mac receiver listens for UDP headpose packets and injects mouse movement only while the macOS cursor is hidden.
- `dev.sh` is the stable command surface for building, installing, connecting, disconnecting, and changing sensitivity.

## Project Layout

- `quest_headpose_app/`: Android Quest app titled `Quest Headpose`
- `mac_receiver/`: Swift receiver binary
- `dev.sh`: main terminal entrypoint
- `scripts/quest_shortcut.sh`: Shortcuts-friendly wrapper
- `config/quest_headpose.env`: shared runtime config
- `third_party/openxr-sdk-source/`: vendored Khronos OpenXR SDK source used by the Quest native activity

## Requirements

- macOS with Accessibility enabled for your terminal app
- Homebrew `android-platform-tools`
- Homebrew `openjdk@17`
- Android command-line SDK at `/opt/homebrew/share/android-commandlinetools` or `~/Library/Android/sdk`
- Quest developer mode enabled
- USB debugging accepted in-headset at least once

## Accessibility Permission

The receiver can only inject mouse movement if macOS Accessibility is enabled for the process that launches it.

1. Open `System Settings`.
2. Go to `Privacy & Security`.
3. Open `Accessibility`.
4. Enable `Terminal` and `Codex`.

## Commands

Run everything from:

```bash
cd "/Users/marissameyer/Desktop/Games In VR"
```

Main commands:

```bash
./dev.sh detect_quest_ip
./dev.sh build_quest_app
./dev.sh install_quest_app
./dev.sh connect
./dev.sh disconnect
./dev.sh set_sensitivity 18.0
./dev.sh receiver_status
```

What each command does:

- `detect_quest_ip`: reads the Quest Wi-Fi IP through ADB and writes it into `config/quest_headpose.env`
- `build_quest_app`: builds the Quest APK with the vendored OpenXR native activity
- `install_quest_app`: builds and installs the APK to the connected Quest
- `connect`: connects wireless ADB, writes the current Mac IP into config, starts the Swift receiver in Terminal, and launches the Quest app with auto-connect arguments
- `disconnect`: asks the app to disconnect, disconnects wireless ADB, and stops the Mac receiver
- `set_sensitivity <float>`: updates the mouse sensitivity used by the receiver
- `receiver_status`: reports whether the receiver is running

## Typical Flow

First-time or after a Quest reboot:

```bash
adb devices
adb tcpip 5555
./dev.sh detect_quest_ip
./dev.sh install_quest_app
./dev.sh connect
```

Daily use after that:

```bash
./dev.sh connect
```

Then in the headset:

1. Open `Quest Headpose` if it is not already in front of you.
2. Press `Connect`.
3. Use `Enter OpenXR` to switch into the black immersive OpenXR activity.
4. Press the Quest menu button to reopen the window UI while the immersive activity stays active.
5. Use `Quit OpenXR` in the window UI when you want to leave immersive mode.

When you close the Quest app task, the background service disconnects automatically.

## Config

`config/quest_headpose.env` contains:

- `QUEST_IP`
- `ADB_PORT`
- `LISTEN_PORT`
- `QUEST_PORT`
- `SENSITIVITY`
- `MAC_IP`

`./dev.sh connect` updates `MAC_IP` automatically. `./dev.sh detect_quest_ip` updates `QUEST_IP`.

## Shortcuts App

Create one Shortcut named `Quest Headpose Control` with one `Run Shell Script` action:

```bash
/Users/marissameyer/Desktop/Games\ In\ VR/dev.sh shortcut
```

That menu currently exposes:

- `Connect to Quest`
- `Disconnect Quest`
- `Detect Quest IP`
- `Install Quest App`
- `Change Head Movement Sensitivity`

If the internal terminal commands change later, keep using the same Shortcut entrypoint.

## Notes

- The Quest build is intentionally `arm64-v8a` only because Quest hardware does not need the extra emulator ABIs.
- The OpenXR immersive scene is intentionally black for now.
- Headpose streaming still comes from the app service, so the Mac receiver keeps working in both the window UI and the OpenXR activity.
