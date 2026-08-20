# Localink

Direct file transfer between your phone and your laptop over your phone's hotspot
or a USB cable. No cloud, no account, nothing running in the background.

---

## Running it

**Laptop** — double-click `dist\Localink.vbs`, or run `dist\Install shortcut.bat`
once to put it in the Start menu.

**Phone** — `adb install -r dist\Localink.apk`, or copy the APK across and tap it.

**Pair, once** — turn on the phone's hotspot (set it to **5 GHz**), connect the
laptop to it, then on the phone tap **Pair a laptop** and type the 8-character
code into the laptop. Codes are single use and last 90 seconds.

---

## Using it

**Laptop → phone.** Drop files on the window or press Ctrl+V. Pick a destination.
Select rows and press **Send**. The phone shows what is coming and you accept it.

**Phone → laptop.** Share any file → **Localink**.

Keyboard on the laptop: `Ctrl+V` paste, `Ctrl+A` select all, `Delete` remove.
Send and Pause act on the current selection.

**Pause and resume** work from either device and take effect on both. A paused
file keeps everything transferred so far; resuming carries on from there rather
than starting again. Partial files last 7 days.

The phone disconnects after **1 minute** of inactivity. Reconnect any time.

---

## Speed

| Setup | Realistic |
|---|---|
| 2.4 GHz hotspot | 6–12 MB/s |
| 5 GHz hotspot | 25–45 MB/s |
| USB tethering | 30–40 MB/s, very steady |

The radio is the limit, not the encryption — both devices do AES in hardware at
well over 1 GB/s. Distance from the phone and thermal throttling on long
transfers matter far more.

---

## Layout

```
shared/src/fileshare/core/   protocol, crypto, transfer engine  (compiled into BOTH apps)
pc/src/fileshare/pc/         laptop app: Swing UI, gateway discovery, disk I/O
android/app/                 phone app: listener, MediaStore I/O, share target
test/src/                    end-to-end tests over loopback
```

The shared folder is the point: both apps compile the *same source files* for
everything that touches the wire, so the two sides cannot drift apart.

## Building

```bash
build-pc.bat
```

```bash
build-pc.bat test
```

Phone app: open `android` in Android Studio, or

```bash
android\gradlew.bat -p android assembleDebug
```

## Permissions the phone app asks for

`INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_CONNECTED_DEVICE`, `CHANGE_NETWORK_STATE`,
`POST_NOTIFICATIONS`.

That is the whole list. No storage permission (files move through the share sheet
and MediaStore), no camera (pairing is a typed code), no location.

See `PROTOCOL.md` for the wire format and the security design.
