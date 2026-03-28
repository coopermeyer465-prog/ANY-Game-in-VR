# Quest Headpose

`Quest Headpose` is a Quest window app plus a macOS terminal receiver.

- The Quest app streams headpose in window mode and in the black OpenXR immersive mode.
- The macOS receiver listens on UDP and injects mouse motion only when the Quest app is armed and the macOS cursor is hidden.
- The Quest app now has a 10-preset sensitivity slider for live tuning.

## Project Layout

- `quest_headpose_app/`: Android Quest app
- `mac_receiver_py/`: active Python receiver
- `dev.sh`: main terminal entrypoint
- `scripts/run_receiver.sh`: starts the receiver
- `scripts/quest_shortcut.sh`: Shortcuts-friendly wrapper
- `config/quest_headpose.env`: shared receiver config
- `QUEST_HEADPOSE_COMMANDS.md`: live command list

## Requirements

- macOS Accessibility enabled for `Terminal` and `Codex`
- `adb` installed through Homebrew
- `openjdk@17`
- Android SDK or command-line tools
- Quest developer mode enabled
- USB debugging accepted once in-headset

## Accessibility

The receiver cannot inject mouse movement without Accessibility permission.

1. Open `System Settings`
2. Go to `Privacy & Security`
3. Open `Accessibility`
4. Enable `Terminal`
5. Enable `Codex`

## Main Commands

Run everything from:

```bash
cd "/Users/marissameyer/Desktop/Games In VR"
```

Start the receiver:

```bash
./scripts/run_receiver.sh
```

Build the Quest app:

```bash
./dev.sh build_quest_app
```

Install or update over USB:

```bash
adb devices
adb -s 340YC10G9B0S11 install -r "/Users/marissameyer/Desktop/Games In VR/quest_headpose_app/app/build/outputs/apk/debug/app-debug.apk"
```

Install or update over wireless ADB:

```bash
adb connect 192.168.0.63:5555
./dev.sh install_quest_app
```

Connect the pipeline:

```bash
./dev.sh connect
```

Disconnect:

```bash
./dev.sh disconnect
```

Set receiver sensitivity from the Mac:

```bash
./dev.sh set_sensitivity 240.0
```

Detect the Quest IP:

```bash
./dev.sh detect_quest_ip
```

Check receiver status:

```bash
./dev.sh receiver_status
```

## Typical Flow

After a full Quest reboot:

```bash
adb devices
adb tcpip 5555
./dev.sh detect_quest_ip
./dev.sh install_quest_app
./dev.sh connect
```

Normal use after wireless ADB is already active:

```bash
./scripts/run_receiver.sh
```

Then in the headset:

1. Open `Quest Headpose`
2. Press `Connect`
3. Wait for `Receiver ready`
4. Press `Arm Mouse`
5. Hide the macOS cursor in the target app or game
6. Tune the 10-step sensitivity slider in the Quest app if needed

## Quest App Notes

- `Connect`, `Disconnect`, `Recenter`, and `Enter Immersive` are all in the window UI.
- `Arm Mouse` must be enabled before the receiver can inject motion.
- The immersive mode is a black OpenXR scene for now.
- Closing the Quest app task disconnects automatically.
- The sensitivity slider stores its selected preset on the headset and reapplies it on reconnect.

## Config

`config/quest_headpose.env` controls the receiver defaults, including:

- `QUEST_IP`
- `ADB_PORT`
- `LISTEN_PORT`
- `QUEST_PORT`
- `SENSITIVITY`
- `MAC_IP`
- `MAX_STEP_PIXELS`
- `MIN_STEP_PIXELS`
- `SMOOTHING_ALPHA`
- `YAW_DEADZONE_DEG`
- `PITCH_DEADZONE_DEG`
- `RESPONSE_EXPONENT`
- `YAW_SCALE`
- `PITCH_SCALE`

`./dev.sh connect` updates `MAC_IP`.

## Shortcuts

Create one Shortcut named `Quest Headpose Control` with one `Run Shell Script` action:

```bash
/Users/marissameyer/Desktop/Games\ In\ VR/dev.sh shortcut
```

That menu exposes:

- `Connect to Quest`
- `Disconnect Quest`
- `Detect Quest IP`
- `Install Quest App`
- `Change Head Movement Sensitivity`

## Command Reference

The live command list is here:

- [QUEST_HEADPOSE_COMMANDS.md](/Users/marissameyer/Desktop/Games%20In%20VR/QUEST_HEADPOSE_COMMANDS.md)
