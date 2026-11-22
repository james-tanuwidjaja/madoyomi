<div align="center">

# Madoyomi

A Windows desktop app for anime and manga that runs existing Aniyomi and Mihon extensions unchanged.

[![Latest release](https://img.shields.io/github/v/release/james-tanuwidjaja/madoyomi?label=release)](https://github.com/james-tanuwidjaja/madoyomi/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/james-tanuwidjaja/madoyomi/total)](https://github.com/james-tanuwidjaja/madoyomi/releases)
[![License: MPL-2.0](https://img.shields.io/github/license/james-tanuwidjaja/madoyomi)](LICENSE)
[![Discussions](https://img.shields.io/github/discussions/james-tanuwidjaja/madoyomi)](https://github.com/james-tanuwidjaja/madoyomi/discussions)

</div>

The name comes from 窓 *mado* ("window") + 読み *yomi* ("reading").

## Download

Get the latest version from the [Releases page](https://github.com/james-tanuwidjaja/madoyomi/releases/latest). It runs on Windows 11 (64-bit) and brings its own Java runtime, mpv, ffmpeg and yt-dlp, so there is nothing else to install.

- **`Madoyomi_X.Y.Z_x64-setup.exe`** (recommended): installs for your user only, without admin rights, and updates itself.
- **`Madoyomi_X.Y.Z_x64_en-US.msi`**: installs for all users of the PC into Program Files (needs admin). The app tells you when a new version is out.

Use one installer per PC: the MSI refuses to install while the per-user copy is installed.

The installers aren't code-signed yet, so Windows SmartScreen may say "Windows protected your PC". Choose **More info → Run anyway**.

Updating, uninstalling and where your data lives: see [Installing](https://github.com/james-tanuwidjaja/madoyomi/wiki/Installing) in the wiki.

## Features

- **Repos and extensions:** add the Aniyomi (anime) and Mihon (manga) repos you choose, then install, update and trust their extensions. Madoyomi ships with none.
- **Library with categories:** a library per kind, with category tabs, sorts and unread/unwatched badges.
- **Updates and history:** check your library for new episodes and chapters, now or on a schedule; see what you watched or read last and resume it.
- **The reader:** single page, double page or a webtoon strip, with zoom, saved progress, the next chapter following on, and a mode per manga.
- **Playback in mpv:** a video picker with your last choice per source, subtitles and audio tracks, resume, and the next episode starting by itself.
- **Downloads:** chapters as CBZ files and episodes as video files with their subtitles, to read and watch offline.
- **Backups:** your library, progress, history, repos, extensions and settings in one file, now or on a schedule. Restores the manga of a Mihon or Aniyomi backup too.
- **App updater:** opt-in update checks, with the release notes and signed updates.

## Platforms and plans

- **Windows:** supported now (Windows 11, 64-bit).
- **Linux:** planned. It's feasible: Tauri, the JVM backend and mpv all run on Linux. It needs a Linux build of the bundled runtime and tools, packaging (AppImage, .deb/.rpm or Flatpak), CI on Linux, and the per-OS paths in the desktop shell.
- **iOS:** an open research question. iOS can't run the JVM backend or the converted (dex2jar) extensions: there is no JIT, no child processes, and the App Store rules forbid it. It would need another architecture, for example the backend running on a home server.

Ideas and help are welcome in [Discussions → Ideas](https://github.com/james-tanuwidjaja/madoyomi/discussions/categories/ideas).

## Help and community

- **[Wiki](https://github.com/james-tanuwidjaja/madoyomi/wiki):** installing, getting started, FAQ and troubleshooting.
- **[Discussions](https://github.com/james-tanuwidjaja/madoyomi/discussions):** questions (Q&A), ideas, polls and show and tell.
- **[Issues](https://github.com/james-tanuwidjaja/madoyomi/issues/new/choose):** bug reports and feature requests. Security problems go through [SECURITY.md](SECURITY.md) instead.

## Contributing

Pull requests are welcome. [CONTRIBUTING.md](CONTRIBUTING.md) has how to build and test, the pull request flow, and the ground rules.

### Wanted: a logo

Madoyomi doesn't have a logo yet; the app still uses Tauri's default icon. If you'd like to design one, say hello in [Discussions → Ideas](https://github.com/james-tanuwidjaja/madoyomi/discussions/categories/ideas). It's needed as the app and installer icon (it has to read well at 16 px), on the release page and at the top of this README.

## Contributors

<a href="https://github.com/james-tanuwidjaja/madoyomi/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=james-tanuwidjaja/madoyomi" alt="Madoyomi's contributors" title="Madoyomi's contributors"/>
</a>

## Acknowledgements

- [Suwayomi-Server](https://github.com/Suwayomi/Suwayomi-Server) (MPL-2.0): the backend base and the manga extension host.
- [Aniyomi](https://github.com/aniyomiorg/aniyomi) and [Mihon](https://github.com/mihonapp/mihon) (Apache-2.0): the extension APIs Madoyomi implements.
- [mpv](https://mpv.io/) for playback and [Tauri](https://tauri.app/) for the desktop shell.

## Disclaimer

Madoyomi does not host, ship or provide any content or extensions. Users add extension repositories themselves and are responsible for the sources they use. Madoyomi is not affiliated with Aniyomi, Mihon, Suwayomi, or any extension or content provider.

## License

[MPL-2.0](LICENSE). Files derived from upstream projects keep their original license headers. `server/` is a fork of Suwayomi-Server (MPL-2.0); see [server/UPSTREAM.md](server/UPSTREAM.md). The extension APIs from Mihon and Aniyomi are Apache-2.0. Attribution is in [NOTICE](NOTICE), and all third-party code and notable libraries are listed in [THIRD_PARTY.md](THIRD_PARTY.md).
