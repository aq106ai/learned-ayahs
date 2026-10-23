# Google Play listing

Everything the Play Console asks for, where it lives in this repo, and the answers to its
questionnaires. The listing files use the layout fastlane `supply` and F-Droid read, so they can
also be uploaded with `fastlane supply --metadata_path fastlane/metadata/android`.

## The files

| Play Console field | Limit | File (under `fastlane/metadata/android/en-US/`) |
| --- | --- | --- |
| App name | 30 chars | `title.txt` |
| Short description | 80 chars | `short_description.txt` |
| Full description | 4000 chars | `full_description.txt` |
| Release notes (versionCode 40) | 500 chars | `changelogs/40.txt` |
| App icon | 512×512 PNG, 32-bit | `images/icon.png` |
| Feature graphic | 1024×500 PNG, no alpha | `images/featureGraphic.png` |
| Phone screenshots | 2–8, 9:16, ≥1080 px | `images/phoneScreenshots/` |
| 7-inch tablet screenshots | up to 8 | `images/sevenInchScreenshots/` |
| 10-inch tablet screenshots | up to 8 | `images/tenInchScreenshots/` |
| Privacy policy URL | — | `PRIVACY.md` at the repo root (link below) |

**Privacy policy URL:** <https://github.com/aq106ai/learned-ayahs/blob/main/PRIVACY.md>

## Regenerating the graphics

The icon is the launcher icon (`res/drawable/ic_launcher_foreground.xml` on
`ic_launcher_background`), so it changes when the launcher icon does. The screenshots are of the
real app:

1. **Capture** — run the **Store screenshots** workflow from the Actions tab. It drives the app on a
   phone and a tablet emulator (`PlayStoreScreenshots` in `app/src/androidTest`, with the status
   bar in demo mode) and commits the raw PNGs to `store/raw/<device>/`. Locally, with an emulator
   or phone connected: `bash store/capture-screenshots.sh store/raw/phone`.
2. **Frame** — `node store/render/render.cjs` (needs Node 18+ and Playwright). It puts each raw
   shot under its caption from `store/render/captions.json` and writes everything into
   `fastlane/metadata/android/en-US/images/`.

Captions describe only what the app does today. Check them against the app before a release.
A caption with `"devices": ["phone"]` is left out of the tablet set: the app is laid out for
phones, and at tablet width the word-by-word screen is mostly empty space.

## Store settings

- **App category:** Books & Reference (Education also fits).
- **Contact email:** required by Play; enter it in the Console, not in this repo.
- **Website:** <https://github.com/aq106ai/learned-ayahs>
- **Price:** free. **Ads:** no. **In-app purchases:** none.

## App content answers

### Privacy policy
The URL above.

### Ads
"No, my app does not contain ads."

### App access
"All functionality is available without special access." There is no login.

### Content rating (IARC questionnaire)
Category **Reference, News, or Educational**. Answer **No** to violence, sexuality, language,
controlled substances, gambling and horror; **No** to users interacting or exchanging content,
sharing location and digital purchases. Expect **Everyone / PEGI 3**.

### Target audience
**13 and over** is the simple choice. Including children under 13 puts the app under the
Families policy. The app collects nothing, but Recite & review sends a child's voice to the
phone's speech service, which that policy treats with extra care.

### Data safety
Based on what the code does (see `PRIVACY.md`): no SDKs, no analytics, no accounts, and the only
network traffic is downloading Qur'an audio and text over HTTPS.

- **Does your app collect or share any of the required user data types?** Yes, for one type only:
  - **Audio → Voice or sound recordings**: *collected*, **not shared**; **processed
    ephemerally**; **optional** (users choose whether to use it); purpose **App
    functionality**. This is Recite & review handing microphone audio to the phone's speech
    recognition service, which may process it online. If you judge that service to be outside
    your app, you could answer "No" instead; declaring it is the cautious choice, and Google
    reviews the app against what it does.
- **Is all user data encrypted in transit?** Yes.
- **Do you provide a way for users to request that their data be deleted?** Nothing is kept
  off the phone. Uninstalling, or clearing the app's data, deletes everything.
- Everything else (location, contacts, identifiers, app activity, crash logs, and so on) is
  **not collected**. The learned list, bookmarks, settings and crash reports stay on the phone,
  and Android backup data is not accessible to the developer.

### Foreground service permissions (Android 14+)
The app declares `FOREGROUND_SERVICE_MEDIA_PLAYBACK`. Choose **Media playback** and describe it:

> Plays Qur'an recitation the user starts, so it keeps playing with the screen off and can be
> paused, skipped and resumed from the notification, the lock screen and Bluetooth headphones.
> If the service were stopped or deferred, recitation would cut out as soon as the screen
> turned off.

The Console asks for a link to a short video showing the feature. It is made from the real app:

1. **Record** — the **Store video** workflow (Actions tab) runs `PlayStoreFgsVideo` on an emulator:
   Play on Al-Mulk, Home, pause and resume from the notification, screen off, the lock screen and
   back to the app. It commits the screen recording, a playback log and the clips that played to
   `store/raw/video/`.
2. **Compose** — `node store/render/render-video.cjs` (needs ffmpeg too) writes
   `store/video/learned-ayahs-media-playback.mp4`: 1920×1080 with a caption for each step and the
   recitation rebuilt from the playback log, since Android's screen recorder has no sound.
3. **Upload** it to YouTube from your account as **Unlisted** (not Private, which reviewers
   cannot open), and paste its `youtube.com/watch?v=…` link into the declaration.

### Promo video (marketing)
The foreground-service video above is for Play reviewers and must show only that feature. For the
listing's **Promo video** field, YouTube and social media there is a separate short tour of the app:
`node store/render/render-promo.cjs` writes `store/video/learned-ayahs-promo.mp4` (1920×1080,
about 37 s) from the phone screenshots in `store/raw/phone/` and their captions, over a recitation
of Bismillah and Al-Mulk 1–3 from `store/raw/video/audio/`. Upload it to YouTube as **Public** or
**Unlisted**, with ads turned off, and paste the link into **Main store listing → Video**.

### Other declarations
News app: no. Health: no. Financial features: none. Government app: no. COVID-19: no.

## Releasing

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`, and add
   `changelogs/<versionCode>.txt`.
2. Build the bundle with the upload key in `keystore.properties` (kept out of git):
   `./gradlew bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`.
3. In Play Console, use **Play App Signing** and upload the `.aab`.
4. **New personal developer accounts** must run a **closed test with at least 12 testers for 14
   days** before Play allows a production release. Start that track early.
