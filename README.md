# Unicast

An Android app where one broadcaster (the owner) records and uploads audio and goes live, and listeners tune in, play recordings and download them. Listeners need no account.

## What it does

**Listeners**
- Listen to live shows, with the show's title and description shown before they join
- Ask to speak during a live show, and talk once the host allows it
- Play and download episodes, change playback speed, and resume where they left off
- Episodes play with extra loudness so quiet voices are easy to hear
- Get a notification when a live show starts or a new episode is uploaded, each with its own switch
- Update the app from inside the app

**Broadcaster (long-press the "Unicast" title to open the admin screen)**
- Sign in, record (pause and resume), preview and upload episodes with a title and description
- Edit or delete episodes, see play and download counts
- Go live with a title and description, see how many people are listening, and see estimated LiveKit minutes used this month
- Mute or unmute your own microphone while live
- Allow, end or decline listeners who ask to speak
- Live shows are recorded as a mix of the host and any guest speaker, and saved as a draft episode when the show ends

## How it is built

| Part | Used for |
|---|---|
| Kotlin and Jetpack Compose | The Android app |
| LiveKit Cloud (free plan) | Live audio |
| Supabase (free plan) | Login (one admin), database, file storage, notification function |
| Firebase Cloud Messaging | Push notifications |
| GitHub Actions | Builds and signs the app, publishes releases |

- Recordings are Opus, mono, 6 kbps, which keeps files and storage very small.
- Live tokens are made inside the database, so no extra server is needed.
- Guest speakers are allowed or ended by the host through LiveKit's server API, called from the database.

## Installing and updating

Download from the latest release: <https://github.com/0racode/unicast/releases/latest>

- 64-bit phones (most phones): `Unicast-arm64.apk`
- Older 32-bit phones: `Unicast-arm32.apk`
- Direct link to the newest 64-bit file: <https://github.com/0racode/unicast/releases/latest/download/Unicast-arm64.apk>

Once installed, the app shows an update card when a new release exists. Updates install over the old app, because every build is signed with the same permanent key.

Each release's notes are what the update card shows. Write them for listeners only, and only about what is new in that build.

## One-time setup (already done for this project)

1. **Supabase.** Run the files in `supabase/` in this order in the SQL Editor: `schema.sql`, `live_token.sql` (replace the `PASTE_` placeholders with your LiveKit values first), `tracking.sql`, `usage.sql`, `update_1.sql`, `notifications.sql`, `live_details.sql`. Create the admin user, and make the `episodes` storage bucket public.
2. **Edge function.** Deploy `supabase/functions/notify-live` with "Verify JWT" turned off (the function checks the admin itself). Add the secret `FIREBASE_SERVICE_ACCOUNT`.
3. **Firebase.** Register the Android app `com.unicast.app`. The resulting `app/google-services.json` is committed to the repo, which is safe because it is also inside every APK. The service account key must never be committed.
4. **GitHub.** Add the repository secrets `KEYSTORE_BASE64` and `KEYSTORE_PASSWORD`. Without them the build is unsigned and no release is published.
5. **Config.** `app/src/main/java/com/unicast/app/Config.kt` holds the public Supabase address and key.

## Keep safe

- **The signing keystore and its password.** If both are lost, existing users cannot update and must reinstall. Keep a backup somewhere other than one phone.
- **The LiveKit secret and the Firebase service account key.** Never put them in the repo or share them in chat. If either leaks, create a new one and replace the old value.

## Free-plan limits to know

- LiveKit: the app shows estimated minutes used this month.
- Supabase: 1 GB of storage, and a project with no activity for about a week is paused (open the dashboard and restore it).
- Notifications: the server will not send a second "live" notification within 2 minutes.

## Known limits

- A live show is lost from the recording if the app is closed or killed while live.
- Download quality is fixed at 6 kbps.
- Live recording needs Android 10 or newer.

## Keeping the app small

Every release is built as one APK per phone type. Avoid adding large libraries, and check the release file sizes after each build (about 21.6 MB for 64-bit, 19.6 MB for 32-bit). Most of the size is program code, so turning on code shrinking is the next way to reduce it, but it needs testing on a real phone because it can break live audio.

The release also still includes a large `Unicast.apk` (all phone types) so that older installed versions can update. Remove it from the workflow once nobody is on build 25 or older.

See [docs/app-plan.md](docs/app-plan.md) for the original plan and design decisions.
