# Unicast: App Plan

## 1. What the app does

- **One broadcaster (you):** only you can upload audio and go live.
- **Listeners:** no accounts needed. They can play live shows, play recorded audio, and download recordings.

## 2. Main features

### Listener side
1. Home screen with a "LIVE NOW" banner when you are broadcasting.
2. Tap the banner to listen live (play and stop, volume).
3. List of recorded episodes (title, date, length).
4. Play an episode, and download it for offline listening.

### Admin side (only you)
1. Login screen (single account, hidden from listeners).
2. Record or pick an audio file, add a title, and upload it.
3. Edit or delete past episodes.
4. "Go Live" button that starts your mic broadcast, and "End Live" to stop.

## 3. Tech choices

| Part | Choice | Why |
|------|--------|-----|
| App language | Kotlin with Jetpack Compose | Standard for Android, works with LiveKit's Android SDK |
| Live audio | LiveKit Cloud (free Build plan) | Free, no card needed, official Android SDK |
| Token server | Small serverless function | LiveKit needs a secure server to hand out access tokens |
| Login | Supabase Auth (one admin account) | Free, no card needed |
| Database | Supabase Database | Stores episode list and the "live now" flag |
| File storage | Supabase Storage (free plan) | Holds the recorded audio files. Move to Cloudflare R2 later if you outgrow it and get a dollar card. Firebase Storage is not used because it needs a paid plan with a card. |
| Recording format | Opus (.ogg) at 6 kbps, mono, voice-only | Tiny files: about 2 MB for a 45-minute show. Test with your own voice before locking in. |
| Audio playback | Media3 / ExoPlayer | Plays recorded files, supports background play |
| Downloads | Android DownloadManager | Handles saving files to the phone |

## 4. How the live show works

1. You press **Go Live** in the app.
2. The app asks the token server for a broadcaster token (only works if you are logged in as admin).
3. The app joins a LiveKit room and publishes your microphone.
4. The app sets a "live now" flag in the database.
5. Listeners' apps see the flag, show the LIVE banner, request a **listen-only** token, and join the same room.
6. You press **End Live**, the flag is cleared, and the room closes.

Listener tokens are created with publishing turned off, so nobody else can speak.

## 5. How recorded audio works

1. You record or choose a file in the admin screen.
2. The app uploads it to file storage.
3. The app saves a database entry with the title, date, and file link.
4. Listeners see the new episode in their list and can stream or download it.

Recorded files do not use any LiveKit minutes.

### Audio size and free storage math

- Recording format is Opus at 6 kbps (under 1 KB per second), close to the lowest Opus allows. Good for a single voice, weak for music, noise or several speakers. 16 or 24 kbps is the fallback if it sounds too thin.
- A 45-minute show is about 2 MB.
- Supabase free plan allows roughly 5 GB of downloads a month, so about 2,400 downloads of one 45-minute show.
- Storage of about 1 GB holds roughly 490 shows of that length.
- Android can record Opus directly on Android 10 and newer. Older phones may need extra setup.
- Some phone music apps cannot open .ogg or .opus files, but the app's own player will play them.
- Supabase free projects pause after about a week of inactivity, so use the app regularly. Confirm current limits before launch.

## 6. Build phases

1. **Setup:** create the Android project, LiveKit account, and Supabase project.
2. **Recorded audio first:** admin upload, episode list, player, download.
3. **Token server:** write the function that creates LiveKit tokens.
4. **Live broadcast:** Go Live and listener screen, with the LIVE banner.
5. **Polish:** background playback, notifications, app icon, error messages.
6. **Testing:** test on a real phone, with a few listeners at once.
7. **Release:** Google Play listing, or share the APK directly.

## 7. Things to watch

- **Free minutes:** about 5,000 LiveKit minutes a month, counted per listener. Fifty people for one hour is 3,000 minutes.
- **Concurrent limit:** about 100 people connected at once on the free plan.
- **Confirm limits:** check LiveKit's pricing page again before launch, since numbers change.
- **Permissions:** the app needs microphone permission for you, and a foreground service so audio keeps playing when the screen is off.
- **Security:** never put the LiveKit secret key inside the app. It lives only on the token server.
- **Later:** if you outgrow the free plan, you can move to a self-hosted LiveKit server without rewriting the app, since it is the same SDK.

## 8. Questions to settle next

1. ~~Will you record inside the app, or upload files you made elsewhere?~~ Decided: the app records itself, saving Opus at 6 kbps mono (Android MediaRecorder with OGG and Opus on Android 10+; older phones need an Opus library).
2. ~~Should listeners get a notification when you go live?~~ Decided: yes. Use Firebase Cloud Messaging (push only, no Firebase Storage). Listeners subscribe to a "live" topic with no account. Pressing Go Live makes the token server send one push to that topic. Android 13+ asks listeners for notification permission. Confirm FCM's free limits before launch.
3. ~~Do you want a name and logo for the app now, or later?~~ Name decided: **Unicast** (check it is free on Google Play before launch). Logo still to do.
4. ~~Have you built Android apps before?~~ Decided: Claude writes the code, and since the owner has no computer, GitHub Actions builds the APK.
