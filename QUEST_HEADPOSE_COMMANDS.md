# Quest Headpose Commands

Run these from:

```bash
cd "/Users/marissameyer/Desktop/Games In VR"
```

## Start Receiver

```bash
./scripts/run_receiver.sh
```

## Build Quest Headpose

```bash
./dev.sh build_quest_app
```

## Install Or Update Over USB

```bash
adb devices
adb -s 340YC10G9B0S11 install -r "/Users/marissameyer/Desktop/Games In VR/quest_headpose_app/app/build/outputs/apk/debug/app-debug.apk"
```

## Install Or Update Over Wireless ADB

```bash
adb connect 192.168.0.63:5555
./dev.sh install_quest_app
```

## Connect Quest Headpose

```bash
./dev.sh connect
```

Then open `Quest Headpose` in the headset and press `Connect`.

## Disconnect

```bash
./dev.sh disconnect
```

## Set Receiver Sensitivity From The Mac

```bash
./dev.sh set_sensitivity 240.0
```

## Detect Quest IP

```bash
./dev.sh detect_quest_ip
```

## Receiver Status

```bash
./dev.sh receiver_status
```

## Re-enable Wireless ADB After A Full Quest Reboot

```bash
adb devices
adb tcpip 5555
adb connect 192.168.0.63:5555
```

## Quest App Controls

- Use the Quest app slider for the 10 built-in head-movement sensitivity presets.
- Wait for `Receiver ready`, then press `Arm Mouse` before expecting injection.
- Use `Recenter` before testing.
- Hide the macOS cursor in the target app before expecting mouse injection.
