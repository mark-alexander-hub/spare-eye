# Spare Eye

Turn the old Android phone in your drawer into a home camera that messages you on Telegram
when something moves. No account, no subscription, no cloud. The only thing that leaves the
phone is the alert photo, sent to your own Telegram chat.

- **Motion alerts with a photo**, straight to Telegram, within a second or two.
- **Live view on your Wi-Fi**: open a link on any phone or laptop at home and see the picture.
- **Always visible**: a notification stays in the shade with a Disarm button the whole time,
  and the phone's own camera indicator lights up. It is a camera for your home, not a hidden one.
- Works on Android 8 and newer, so most phones from 2017 onwards.

## Set it up (five minutes)

1. Install the APK from the Releases page on the spare phone.
2. In Telegram, open **@BotFather**, send `/newbot`, answer its two questions, and copy the
   token it gives you. Paste the token into Spare Eye.
3. Open your new bot in Telegram and send it any message. In Spare Eye tap **Find my chat**.
   It replies "connected" in Telegram so you know the loop works.
4. Choose back or front camera, name the room, leave sensitivity at 6.
5. Stand the phone where it can see the room, plug it in, tap **Arm in 20 seconds**, walk away.

The screen goes almost black (so the phone doesn't light the room) and shows the live-view
link. Tap **Disarm** on the screen or in the notification to stop.

## How the motion detection works

Every frame from the camera is shrunk to a few thousand sample points and compared with the
previous one. When enough of the picture changes on two frames in a row, that is movement:
the app takes a proper photo and sends it. After an alert it waits (45 seconds by default)
before it will send another, so a cat pacing the room doesn't send you 200 photos.

Sensitivity 1 only reacts to something big crossing the room; 10 catches a curtain twitching.
A ceiling fan, trees outside a window, or a TV left on will trigger it, so point the camera
away from those or drop the sensitivity.

## Live view

With "Live view on my Wi-Fi" on, the armed screen shows a link like
`http://192.168.1.23:8080/k7m2xq4p/`. It works only for devices on the same Wi-Fi, and the
random part of the link means nobody can simply guess it. Nothing is forwarded to the internet.

## Privacy

- The bot token and chat id stay in the app's private storage on the phone.
- Photos are sent to Telegram only on movement (or when you tap "Send a test photo"), and only
  to the chat you connected. Telegram's own privacy terms apply to what you send there.
- No analytics, no crash reporting, no accounts.

## Build it yourself

Android Studio or just the command line with a JDK 17+ and the Android SDK:

```
gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`.

## Licence

MIT. Built by Mark Isidore Nicholas Alexander.
