# Privacy Policy — Learned Ayahs

_Effective 23 October 2026_

This policy covers the **Learned Ayahs** Android app (package `com.quran.learnedayahs`), published
on Google Play from the open-source project at
[github.com/aq106ai/learned-ayahs](https://github.com/aq106ai/learned-ayahs). Because the source
is public, everything below can be checked against the code.

## In short

Learned Ayahs has no account, no ads, no analytics and no tracking. The developer does not
receive any information about you or how you use the app.

## What stays on your phone

- **Your learned ayahs, bookmarks and settings** are stored on your phone only.
- **Downloaded recitation audio** is stored in the app's private storage.
- **Crash reports.** If the app crashes, it saves a description of the error on your phone and
  shows it to you the next time it opens. It is only sent anywhere if you choose to copy or share
  it yourself.
- **Backups.** If Android backup is turned on, your learned ayahs, bookmarks and settings are
  included in your phone's own backup to your Google account, which Google handles under its
  policies. The developer has no access to it. Downloaded audio is left out of cloud backups.
- **Export and import.** When you export your list, it is saved to a file you choose. When you
  import, the app reads only the file you pick.

## What the app connects to

The app connects to the internet only to fetch Qur'an recitation and text:

| Service | What for |
| --- | --- |
| everyayah.com | Ayah recitation audio |
| audio.qurancdn.com (Quran.com) | Word-by-word audio |
| api.qurancdn.com (Quran.com) | Ayah text, if it is missing from the app |
| raw.githubusercontent.com (this project on GitHub) | Nature photos for video backgrounds, only if you choose them |

These requests contain only what is needed to download the file (which ayah or word). Like any
internet request, they reveal your IP address and the app's name to that service, which handles
it under its own privacy policy. The app sends no identifiers, account details or usage data.

## Microphone (Recite & review)

Recite & review is an optional beta feature, off until you turn it on in Settings, and it needs
Android 13 or later. While you use it, the app listens through the microphone and passes the
audio to your phone's speech recognition service so it can follow your recitation:

- If an offline Arabic speech pack is installed, recognition happens on your phone.
- Otherwise your phone's speech service (usually Google's) may process the audio online, under
  that provider's privacy policy.

The app does not save, upload or share your recordings itself. The microphone is used only while
Recite & review is listening, and you can revoke the permission at any time in Android settings.

## Videos you make

"Make a video" builds the video on your phone. It downloads only what it needs: the recitation,
from the services listed above, and, if you choose nature photos as the background, those photos
from this project's own GitHub repository (raw.githubusercontent.com). The finished video stays on
your phone until you save it or share it with an app you choose.

## Notifications

The app shows a notification while it plays audio, so you can control playback from the
notification and lock screen. It sends no other notifications.

## Children

The app does not knowingly collect personal information from anyone, including children.

## Changes

If this policy changes, the new version will be published at this address with a new effective
date. The full history is visible in the project's repository.

## Contact

Questions about this policy can be raised at
[github.com/aq106ai/learned-ayahs/issues](https://github.com/aq106ai/learned-ayahs/issues).
