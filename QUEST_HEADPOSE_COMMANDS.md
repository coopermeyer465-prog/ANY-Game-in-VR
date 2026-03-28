# Quest Headpose Commands

Each command finds the project folder first, then runs from there.

## Start Receiver

```bash
cd "$(dirname "$(dirname "$(find "$HOME" -path '*/scripts/quest_shortcut.sh' -print -quit)")")" && ./scripts/run_receiver.sh
```

## Build Quest Headpose

```bash
cd "$(dirname "$(dirname "$(find "$HOME" -path '*/scripts/quest_shortcut.sh' -print -quit)")")" && ./dev.sh build_quest_app
```

## Install Or Update Over USB

```bash
cd "$(dirname "$(dirname "$(find "$HOME" -path '*/scripts/quest_shortcut.sh' -print -quit)")")" && adb devices && adb -s "$(adb devices | awk 'NR>1 && $2==\"device\" {print $1; exit}')" install -r "$PWD/quest_headpose_app/app/build/outputs/apk/debug/app-debug.apk"
```

## Install Or Update Over Wireless ADB

```bash
cd "$(dirname "$(dirname "$(find "$HOME" -path '*/scripts/quest_shortcut.sh' -print -quit)")")" && ./dev.sh detect_quest_ip && adb connect "$(sed -n 's/^QUEST_IP=//p' config/quest_headpose.env):5555" && ./dev.sh install_quest_app
```

## Connect Quest Headpose

```bash
cd "$(dirname "$(dirname "$(find "$HOME" -path '*/scripts/quest_shortcut.sh' -print -quit)")")" && ./dev.sh connect
```

Then open `Quest Headpose` in the headset and press `Connect`.

## Disconnect

```bash
cd "$(dirname "$(dirname "$(find "$HOME" -path '*/scripts/quest_shortcut.sh' -print -quit)")")" && ./dev.sh disconnect
```

## Set Receiver Sensitivity From The Mac

```bash
cd "$(dirname "$(dirname "$(find "$HOME" -path '*/scripts/quest_shortcut.sh' -print -quit)")")" && ./dev.sh set_sensitivity 240.0
```

## Detect Quest IP

```bash
cd "$(dirname "$(dirname "$(find "$HOME" -path '*/scripts/quest_shortcut.sh' -print -quit)")")" && ./dev.sh detect_quest_ip
```

## Receiver Status

```bash
cd "$(dirname "$(dirname "$(find "$HOME" -path '*/scripts/quest_shortcut.sh' -print -quit)")")" && ./dev.sh receiver_status
```

## Re-enable Wireless ADB After A Full Quest Reboot

```bash
cd "$(dirname "$(dirname "$(find "$HOME" -path '*/scripts/quest_shortcut.sh' -print -quit)")")" && adb devices && adb tcpip 5555 && ./dev.sh detect_quest_ip && adb connect "$(sed -n 's/^QUEST_IP=//p' config/quest_headpose.env):5555"
```

## Quest App Controls

- Use the Quest app slider for the 10 built-in head-movement sensitivity presets.
- Wait for `Receiver ready` before expecting any mouse movement.
- Use `Recenter` before testing.
- Hide the macOS cursor in the target app before expecting mouse injection.
