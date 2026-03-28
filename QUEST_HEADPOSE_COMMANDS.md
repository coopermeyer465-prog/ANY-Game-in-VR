# Quest Headpose Commands

Run these from:

```bash
cd "/Users/marissameyer/Desktop/Games In VR"
```

## Start Receiver

Start the receiver:

```bash
./scripts/run_receiver.sh
```

## Build Quest Headpose

```bash
./dev.sh build_quest_app
```

## Install Or Update Quest Headpose Over USB

```bash
adb devices
adb -s 340YC10G9B0S11 install -r "/Users/marissameyer/Desktop/Games In VR/quest_headpose_app/app/build/outputs/apk/debug/app-debug.apk"
```

## Install Or Update Quest Headpose Wirelessly

Wireless install or update:

```bash
adb connect 192.168.0.63:5555
./dev.sh install_quest_app
```

Direct install command:

```bash
adb -s 192.168.0.63:5555 install -r "/Users/marissameyer/Desktop/Games In VR/quest_headpose_app/app/build/outputs/apk/debug/app-debug.apk"
```

## Connect The Quest App To The Mac Receiver

Make sure the receiver is already running, then:

```bash
adb connect 192.168.0.63:5555
./dev.sh connect
```

On the headset, open `Quest Headpose` and press `Connect`.

## Disconnect

```bash
./dev.sh disconnect
```

## Change Sensitivity

```bash
./dev.sh set_sensitivity 18.0
```

## Detect Quest IP

If the Quest IP changes:

```bash
./dev.sh detect_quest_ip
```

## Receiver Status

```bash
./dev.sh receiver_status
```

## If Wireless ADB Stops Working

Usually this happens after a full reboot or power-off. Use USB once:

```bash
adb devices
adb tcpip 5555
adb connect 192.168.0.63:5555
```

After that, you can unplug USB again and keep installing wirelessly while the Quest stays on or asleep.

## Notes

- The Quest window app works in normal window mode and is supposed to keep sending while immersive is active.
- You do not need USB for normal updates once wireless ADB is active.
- If the Quest fully reboots, you usually need USB once again for `adb tcpip 5555`.
- Start the receiver before pressing `Connect` in the headset app.
- The active Mac receiver is `mac_receiver_py/quest_headpose_receiver.py`.
