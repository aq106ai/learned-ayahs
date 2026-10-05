# Security Policy

## Supported versions

Only the latest release of the Android app and the current `main` branch of the web player
receive fixes.

## Reporting a vulnerability

**Please do not open a public issue for security problems.**

Report them privately through GitHub's
[private vulnerability reporting](https://github.com/aq106ai/learned-ayahs/security/advisories/new)
(the **Security → Report a vulnerability** tab of this repository). Please include:

- a description of the issue and its impact;
- steps to reproduce, or a proof of concept;
- the affected app version, or the commit for the web player.

You should get an acknowledgement within a few days. Once a fix is available, we will credit you
in the release notes unless you prefer otherwise.

## Scope notes

- The Android app requests `INTERNET`, `RECORD_AUDIO` (used only on the Recite & review screen,
  which is opt-in) and notification/foreground-service permissions. It requests **no storage
  permissions**. Anything that reads or writes user files outside the Storage Access Framework
  is a bug.
- The web player's server binds to `127.0.0.1` only. Exposing it on a network is outside its
  intended use.
- Recitation audio, word audio, text and timings are fetched from third-party services
  (everyayah.com, Quran.com, QUL). Problems with those services should be reported to them.
