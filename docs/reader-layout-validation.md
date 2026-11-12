# Reader layout and playback validation

Updated 2026-11-12. Changes are local and unstaged; no release was published.

## Fixed in this update

- Web: tall ayahs no longer overflow above the scroll origin when vertically centered. The reader content does not shrink as a flex item.
- Web: word following scrolls only the reading viewport, never its page ancestors. New ayahs reset the viewport to the top.
- Web: fit calculations rerun after window/control resizing, translations arriving, and font loading. The minimum size respects enlarged browser text, with a scroll fallback for content that remains too tall.
- Web: header buttons wrap; expanded controls scroll within their allocated space.
- Android: minimum-size fit content can scroll instead of being clipped above/below the reading slot. Measurement-only copies are excluded from accessibility semantics.
- Android: word pages respect the word-meaning visibility setting, size their text and spacing against the available reading area, and allow vertical scrolling. The header and playback panel have bounded, scrollable space in short windows.

## Completed checks

- Web: 135 unit tests passed.
- Web: 32 browser regressions passed (playback, offline behavior, content settings, onboarding, navigation, RTL, and static hosting).
- Web: 3 new browser regressions passed. Both overflow modes tested at 320x640, 360x740, 411x891, 600x700, 840x600, 1024x768, and 740x360, including 200% browser text and a player background. First/last-word reachability and page/header stability during auto-follow are asserted.
- Android: 235 unit tests and debug/test APK builds passed.
- One intermediate full run timed out in the existing recitation-coach progression test while two emulators were active. The isolated retry and subsequent complete 235-test run passed without changing that test or recitation code; the timeout remains a test-stability observation.
- Android 36 emulator: all 4 new playback regressions passed (cached paused transitions, playing transitions/recreation, invalid word audio with recovery, and reachable settings).
- Android 36 emulator: all 20 existing word-reader regressions passed.
- Android 36 emulator: both new long-ayah/translation-visibility tests passed after fixing the measurement-semantics defect.
- Android 36 layout assertions passed at 320x640/1.0/en, 360x740/1.3/ur, 411x891/2.0/en, 600x800/2.0/ar, 840x600/1.0/en, 1024x768/1.0/en, and 740x360/1.0/en (width/height in dp, followed by font scale and language).
- Android 26 emulator: all 6 new reader and playback regressions passed.
- Android 36 foldable emulator: both reader tests passed in OPENED, HALF_OPENED, and CLOSED states (6 executions). The half-open screenshot confirms content stays on one safe side of the vertical hinge.
- The final Android matrix passed all seven configurations (14 test executions) after the responsive word-size change and corrected locale setup. Urdu and Arabic were applied before Activity creation; their screenshots confirm the localized interface. Earlier locale-labelled runs alone did not establish RTL coverage. Fresh screenshots were reviewed at small-phone, enlarged-text Arabic tablet, and short-landscape sizes, without the earlier emulator System UI overlays.

Web screenshots: `web-app/test-results/reader-AUTO_SCROLL.png` and `reader-FIT_TO_SCREEN.png`.
Android matrix logs and screenshots: `android-app-quran-player/build/reader-validation/`.

## Broader implementation carried forward

The Android checkout also contains the two-queue playback refactor, legacy-setting migration, independent translation switches, fold-aware frame, and four additional provider-verified word-meaning languages. The provider samples include English fallback; see `word-language-audit.json`. This is sample validation, not a linguistic audit of every word.

## Limits

The web fixes do not change web playback-mode choices. Android and web are not being claimed bug-free. Full physical-device coverage, all folding postures, interrupted-download/reconnection combinations, and every screen in the earlier broad plan are not established by these targeted reader tests.


## Player menu review update — 2026-11-12

- Both apps now offer separate Ayah / Word by word choices above the reader, and an Actions menu containing Mode (Learned ayahs / Full surah), Tafsir, Share, Background and Settings. The footer retains transport/repeat controls.
- Juz queues and multi-ayah page views are deferred; no inactive choices are shown.
- Existing web word audio remains an optional control for learned ayahs. Full-surah word audio is not implemented on web; the UI explicitly states that limitation. Android retains its separate word clip/repeat controls.
- Short web landscape windows place track details beside transport buttons to preserve reading space. This fixed the small reading viewport detected by the first layout run.
- Added shared Urdu/Arabic translations for the new menu labels and previously untranslated playback/meaning statuses.
- Web: 135 unit tests passed; 19 playback/content browser tests, 4 reader/menu tests, 4 video/share tests, and 4 offline/RTL tests passed. Screenshot: `web-app/test-results/player-menu-review.png`.
- Android: the initial 27-test run passed 26; one assertion still expected the retired “Whole ayah” toggle label. It was updated to assert the new Ayah choice, and its retry passed. The final 3 reader/menu tests, 4 playback tests, corrected view-choice test, and all 3 background/share tests passed. Two share tests initially waited for a button before opening its new menu; their setup was corrected and both passed. The final full 235-test Android unit suite passed. All seven configurations passed (21 reader/menu test executions) before the final placement revision below.


### Final placement revision

Mode is now grouped inside Actions on both apps; it is no longer a standalone header button. The reading-view choices remain above the ayah, and the footer stays focused on playback. Menus scroll on short windows.

The prior separate-Mode-button version passed the seven Android screen configurations. The approval service then reached its usage limit. The final placement revision has not been rebuilt or run in the emulator/browser tests; the installed Android preview still shows the preceding layout. This is a validation limitation, not a production-ready claim.
