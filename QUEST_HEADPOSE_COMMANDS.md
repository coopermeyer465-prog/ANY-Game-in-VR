# Quest Headpose Commands

Project root:

```bash
cd "/Users/marissameyer/Desktop/Games In VR"
```

## Start Receiver

```bash
cd "/Users/marissameyer/Desktop/Games In VR"
./scripts/run_receiver.sh
```

## Build Quest Headpose

```bash
cd "/Users/marissameyer/Desktop/Games In VR"
./dev.sh build_quest_app
```

## Install Or Update Over USB

```bash
cd "/Users/marissameyer/Desktop/Games In VR"
adb devices
./dev.sh install_quest_app
```

## Install Or Update Over Wireless ADB

```bash
cd "/Users/marissameyer/Desktop/Games In VR"
adb connect 192.168.0.63:5555
./dev.sh install_quest_app
```

## Connect Quest Headpose

```bash
cd "/Users/marissameyer/Desktop/Games In VR"
./dev.sh connect
```

Then open `Quest Headpose` in the headset and press `Connect`.

## Disconnect

```bash
cd "/Users/marissameyer/Desktop/Games In VR"
./dev.sh disconnect
```

## Set Receiver Sensitivity From The Mac

```bash
cd "/Users/marissameyer/Desktop/Games In VR"
./dev.sh set_sensitivity 240.0
```

## Detect Quest IP

```bash
cd "/Users/marissameyer/Desktop/Games In VR"
./dev.sh detect_quest_ip
```

## Receiver Status

```bash
cd "/Users/marissameyer/Desktop/Games In VR"
./dev.sh receiver_status
```

## Open The Shortcut Menu

```bash
cd "/Users/marissameyer/Desktop/Games In VR"
./scripts/quest_shortcut.sh
```

## Re-enable Wireless ADB After A Full Quest Reboot

```bash
cd "/Users/marissameyer/Desktop/Games In VR"
adb devices
adb tcpip 5555
adb connect 192.168.0.63:5555
```

## Quest App Controls

- Use the Quest app slider for the 10 built-in head-movement sensitivity presets.
- Wait for `Receiver ready` before expecting any mouse movement.
- Press `Enter OpenXR`. The app is OpenXR-only now and will not stream fallback window-mode pose.
- Use `Recenter` before testing.
- Hide the macOS cursor in the target app before expecting mouse injection.
