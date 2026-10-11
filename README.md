# NFC Bridge

An Android app plus a Python script. The phone holds the card; your laptop types raw
hex commands (like "Advanced NFC commands" in NFC Tools) and sees the replies.

Status: the Python client was tested against a simulated card. The Android app has NOT
been compiled or run on a real phone yet, so expect to fix a typo or two on first build.

## 1. Get the APK

### Option A: GitHub builds it for you (no Android Studio)
1. Make a free GitHub account and create a new repository.
2. Upload everything in this folder (keep the `.github` folder and `gradlew`).
3. Open the repository's Actions tab, wait for "Build APK" to finish (a few minutes).
4. Open the finished run and download the `nfc-bridge-apk` artifact. Unzip it to get `app-debug.apk`.
   If the build fails, open the run and send me the error text.

### Option B: Android Studio
Open this folder, wait for Gradle sync, then Build > Build APK(s).
The file is at `app/build/outputs/apk/debug/app-debug.apk`.

## 2. Install on the phone
Copy the APK to the phone, open it, and allow "install unknown apps" when asked.

## 3. Use it
1. Open NFC Bridge on the phone. Keep the screen on and the app in front (Android only
   lets an app read cards while it is on screen). It shows a PIN, port, and IP.
2. Hold the card flat on the phone's NFC spot until the app says "Tag ready".
3. On the laptop (Python 3, nothing to install):
   - USB: enable USB debugging, install adb, then
     `python laptop/nfc_client.py --adb --pin 1234`
   - Wi-Fi: `python laptop/nfc_client.py --host 192.168.x.x --pin 1234`
4. Type commands:

```
nfc> info
nfc> dump 0 15
nfc> read 8
nfc> write 8 68656C6C
nfc> text 8 hello
nfc> 30 08
```

The phone adds the CRC, so never type one. A `0A` reply to a write means it was accepted.
A short reply or ERROR on a write usually means a locked page.

## Limits
- Locked pages stay locked. This tool sends commands through the same chip, so it cannot
  write pages 4-7 on your arcade card.
- Pages 0-3 are guarded by a "yes" prompt. Writes there can brick a card.
- Only NfcA cards (ISO 14443-3A) are supported, which includes Ultralight and NTAG.
- The PIN stops other devices on your Wi-Fi from driving the card, but traffic is not
  encrypted. Use it on a network you trust, or use USB.
