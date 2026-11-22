# Security policy

## Reporting a vulnerability

Please report security problems **privately**, through GitHub's private vulnerability reporting: [Report a vulnerability](https://github.com/james-tanuwidjaja/madoyomi/security/advisories/new) (the repo's **Security** tab → **Report a vulnerability**). Don't open a public issue or discussion for them.

The maintainer answers in the advisory as soon as possible, works out the fix and its release with you there, and credits you in the published advisory unless you'd rather not be named.

## Supported versions

Only the **latest release** gets security fixes. A fix ships as a new release, which installed copies pick up through the app updater (setup.exe installs with "Check for app updates" on) or by installing it over the old version.

| Version | Supported |
|---|---|
| The latest release ([Releases](https://github.com/james-tanuwidjaja/madoyomi/releases/latest)) | Yes |
| Anything older | No: update first |

## What to include

- The Madoyomi version (Settings → Backend) and which installer you used (setup.exe or MSI), and your Windows version.
- What the problem is and what an attacker could do with it.
- Steps to reproduce, or a proof of concept, as small as you can make it.
- Whether it needs a malicious extension, repo, website or local access, or works without one.
- Logs (Settings → Backend → Export logs) if they help. Check them first: they list your repos and extensions.

Please don't include links to extension repos or copyrighted content; describe them instead.

## Scope

In scope: the Madoyomi app and its installers, the backend (`server/`), how it loads and isolates extensions (the signing-certificate trust, the extension class loader, the local API and its token), the app updater and its signatures, and the release workflow.

Out of scope: vulnerabilities in third-party extensions or the sites they reach (report those to the extension's authors), and in upstream projects that also affect them as they are (Suwayomi-Server, Mihon, Aniyomi, mpv, FFmpeg, yt-dlp, Tauri): report those upstream too, and tell us if Madoyomi needs to update.
