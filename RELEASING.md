# Releasing Madoyomi

The release build runs in GitHub Actions ([`.github/workflows/release.yml`](.github/workflows/release.yml)) on `windows-latest`. A pushed tag `vX.Y.Z` runs the tests, builds the signed release and uploads it to a **draft** release. You check the draft, smoke-test it on a clean machine, then publish it by hand. Publishing it is what makes installed copies update: the app's updater reads `https://github.com/james-tanuwidjaja/madoyomi/releases/latest/download/latest.json`.

## What the workflow does

| Step | What |
|---|---|
| Toolchain | Temurin JDK 21.0.13, Node 26.13.0, pnpm 12.16.1, Rust 1.99.1: the dev desktop's ([CONTRIBUTING.md](CONTRIBUTING.md#prerequisites), "Prerequisites"). The JDK's patch is pinned because the jlink runtime sits at its 60 MB cap. Actions are pinned by commit |
| Caches | Gradle (`setup-gradle`'s basic cache), the pnpm store, cargo (`rust-cache`), the pinned downloads in `tools/bin/downloads` (keyed on `tools/binaries.json`) and the test APKs in `tools/apks` |
| Version | `tools/version.ps1` (VERSION and its five copies) and its `-SelfTest`; on a tag, the tag must be `v` + VERSION |
| Notes | `node tools/release-notes.mjs X.Y.Z`: `CHANGELOG.md`'s `## X.Y.Z` section. A tag run fails without it |
| Tests | `tools/fetch-upstream-assets.ps1` and `tools/fetch-test-apks.ps1` (pinned by SHA-256), then `.\gradlew.bat build` (the server's tests and ktlint), `pnpm lint`, `format:check`, `typecheck`, `test` and `build`, `cargo fmt --check`, `cargo clippy --all-targets -- -D warnings`, `cargo test`. Any failure stops the run before the build. Not in CI: `tools/smoke-api.ps1` and the live spec (they drive real extension sites), and the compat harness's APK tests (`tools/compat-test/apks` isn't pinned, so those tests skip) |
| Build | `tools/build-release.ps1 -NotesFile <the section> -DownloadUrl https://github.com/<repo>/releases/download/vX.Y.Z/Madoyomi_X.Y.Z_x64-setup.exe`, with `TAURI_SIGNING_PRIVATE_KEY` and `TAURI_SIGNING_PRIVATE_KEY_PASSWORD` from the repo's secrets, as environment variables of that step only |
| Checks | `tools/check-release.ps1 -WriteSums`: the files and sizes, the installers' versions, the MSI's upgrade code, `latest.json` and its signature against `tauri.conf.json`'s public key (`tools/verify-updater.mjs`), `SHA256SUMS.txt`. `tools/scan-secrets.mjs`: the key and its password in none of the outputs |
| Release | On a tag: `gh release create vX.Y.Z --draft` with `Madoyomi_X.Y.Z_x64-setup.exe`, its `.sig`, `Madoyomi_X.Y.Z_x64_en-US.msi`, `latest.json` and `SHA256SUMS.txt`. The release text is the section plus how to install (the SmartScreen note) and the bundled programs with their sources. A rerun replaces a draft's files; a published release is never touched |
| Dry run | Actions → Release → **Run workflow**: the same tests, signed build and checks, no release. The small files (`latest.json`, the `.sig` files, `SHA256SUMS.txt`, the release text) are kept as the run's artifact for 7 days; the installers aren't (577 MiB) |

A run takes about 26–31 minutes with warm caches (0.1.0 and 0.1.1). A private repo pays for its Windows minutes (about $0.01 a minute past the plan's allowance); a public repo's are free.

## One-time setup

1. **The repo is public before the first release is published** (done). The updater can't read a private repo's releases. Drafts work while a repo is private.
2. **The updater's key as secrets** (done; again only after a rotation). The key and its password are kept outside the repo folder ([The updater's key](#the-updaters-key)). Install the GitHub CLI once (`winget install GitHub.cli`, then `gh auth login`), restore the two files from the private drive to a folder outside the repo (`$k` below), then in PowerShell:

   ```powershell
   $k = 'X:\madoyomi-updater-key'   # the restored folder, outside the repo
   cmd /c "gh secret set TAURI_SIGNING_PRIVATE_KEY --repo james-tanuwidjaja/madoyomi < `"$k\madoyomi.key`""
   cmd /c "gh secret set TAURI_SIGNING_PRIVATE_KEY_PASSWORD --repo james-tanuwidjaja/madoyomi < `"$k\password.txt`""
   gh secret list --repo james-tanuwidjaja/madoyomi
   ```

   `cmd`'s `<` sends the files' bytes as they are (PowerShell's pipe would add a line break). Neither file ends with a line break; if you ever recreate one, keep it that way. Never paste the key into a workflow, an issue or a log. Remove the restored copy when you're done.

## Making a release

1. **Branch and bump.** On a branch off an up-to-date main: `.\tools\version.ps1 -Set X.Y.Z` (VERSION and its copies), then `.\tools\version.ps1` to check.
2. **Notes.** In [`CHANGELOG.md`](CHANGELOG.md), rename `## Unreleased` to `## X.Y.Z` and tidy its lines: short, plain words, since the app's update dialog shows the section whole. Check that nothing user-visible from the merged pull requests is missing (`git log vA.B.C..main --merges --oneline`, from the last tag). Then add a new, empty `## Unreleased` above it, and check the section with `node tools/release-notes.mjs X.Y.Z`.
3. **Merge** the branch into main (the usual review).
4. **Optional dry run:** Actions → Release → Run workflow on main. It proves the build before a tag exists.
5. **Tag and push:**

   ```powershell
   git switch main; git pull
   git tag -a vX.Y.Z -m "Madoyomi X.Y.Z"
   git push origin vX.Y.Z
   ```

   The workflow starts on the tag (`gh run watch` follows it). A tag that isn't `v` + VERSION fails at once.
6. **Check the draft.** Its page (Releases → Draft) lists five files. Download and check them:

   ```powershell
   gh release download vX.Y.Z --repo james-tanuwidjaja/madoyomi --dir $env:TEMP\madoyomi-X.Y.Z
   .\tools\check-release.ps1 -Dir $env:TEMP\madoyomi-X.Y.Z -Version X.Y.Z
   ```

   `check-release.ps1` must end with `OK`. Its checks: the four files and `SHA256SUMS.txt`; the installers' versions; the MSI's upgrade code; `latest.json`'s version and notes; its URL, which must be `https://github.com/james-tanuwidjaja/madoyomi/releases/download/vX.Y.Z/Madoyomi_X.Y.Z_x64-setup.exe` (a draft's own download links are `…/download/untagged-…/…` until it is published; the URL in `latest.json` is the one it gets then); and the signature against the public key in `tauri.conf.json`. Read the release text too: the notes, the SmartScreen line, the sources.
7. **Smoke test on a clean Windows 11 machine** (no Java, mpv, ffmpeg or yt-dlp; or the dev desktop with nothing on PATH and a fresh `--data-dir`): install `Madoyomi_X.Y.Z_x64-setup.exe` from the draft's download. It isn't code-signed, so SmartScreen says "Windows protected your PC": choose **More info → Run anyway**. SmartScreen only checks a file with the Mark of the Web: browsers write it, but a download manager that takes over the download (Internet Download Manager, on the dev desktop) doesn't: Alt+click the link to bypass it, or turn its browser integration off, to see the warning. Then add a repo, install an anime and a manga extension, play an episode, read a chapter, Export logs, and uninstall (keeping the data). For an update, install the previous version first and update to this one once it's published (step 9).
8. **Publish:** on the draft's page, tick **Set as the latest release** and **Create a discussion for this release** (category: Announcements), then **Publish release**. From the command line: `gh release edit vX.Y.Z --repo james-tanuwidjaja/madoyomi --draft=false --latest --discussion-category Announcements`. Publishing doesn't move the tag. From then on `releases/latest/download/latest.json` is this version's.
9. **After publishing:** `Invoke-RestMethod https://github.com/james-tanuwidjaja/madoyomi/releases/latest/download/latest.json` shows the new version. An installed earlier version with "Check for app updates" on offers it at its next start, or on Settings → General → Check now.

### When something is wrong

- **In a draft:** fix it on a branch and merge. Then move the tag: `git push --delete origin vX.Y.Z; git tag -d vX.Y.Z`, tag the new commit and push it. The rerun replaces the draft's files and text. Or delete the draft (`gh release delete vX.Y.Z --yes`) first.
- **In a published release:** never replace its files. Installed copies may already have it, and the signature ties the version to the file. Release X.Y.(Z+1) instead.
- **A failed run:** the job's summary has the server's test counts; on failure the test reports are kept as the run's artifact (`test-reports`, 7 days). `server/` flakes seen before: `LooperTest`, and `CefTest` after a leaked proxy (rerun the job).

### Known issues

- **0.1.0 and 0.1.1's MSI after the per-user (NSIS) installer** (fixed in 0.1.2): Tauri's MSI reused the install folder recorded in `HKCU\Software\Madoyomi contributors\Madoyomi`, which the NSIS installer writes and its uninstall keeps, so on a PC that had the NSIS install those MSIs went into `%LOCALAPPDATA%\Madoyomi`. Since 0.1.2 `app/src-tauri/installer/msi-folder.wxs` keeps the MSI in Program Files (an earlier MSI install keeps its own folder) and refuses while the per-user copy is installed; `tools/check-release.ps1` checks both in the MSI's tables.
- **A silent NSIS install over an MSI install** (`Madoyomi_X.Y.Z_x64-setup.exe /S`): Tauri's installer removes an MSI install only on its interactive reinstall page, so a silent one installs beside it. Run the NSIS installer normally, or uninstall the MSI first.

## The updater's key

The private key signs every installer the updater accepts. Installed copies only accept installers signed by the key whose public half they were built with (`plugins.updater.pubkey` in `tauri.conf.json`).

- **Kept:** in your password manager, on a private drive, and as the two repo secrets (`TAURI_SIGNING_PRIVATE_KEY`, `TAURI_SIGNING_PRIVATE_KEY_PASSWORD`), which CI uses. **No copy in the repo folder** (not even the gitignored `tools/dev-data/`), and none in a log, an artifact or a zip. The workflow's scan fails the run if the key or its password turns up in an output.
- **A local signed build** (rarely needed; CI makes the releases) needs the key restored first: copy `madoyomi.key` and `password.txt` from the private drive to a folder outside the repo, then `$env:TAURI_SIGNING_PRIVATE_KEY = '<that folder>\madoyomi.key'` and `$env:TAURI_SIGNING_PRIVATE_KEY_PASSWORD = Get-Content -Raw '<that folder>\password.txt'` before `.\tools\build-release.ps1`. Without them the script stops and says so; `.\tools\build-release.ps1 -NoUpdater` builds unsigned installers with no key at all. Delete the restored copy afterwards.
- **If the key leaks:** someone could sign an installer that installed copies accept, if they could also serve it from the feed (the GitHub release). Rotate it (below), and delete the secrets right away.
- **If the key is lost, or to rotate it:**
  1. `pnpm tauri signer generate -w X:\madoyomi-updater-key\madoyomi.key` in `app/`, writing to a folder **outside the repo** (with a new password into `password.txt` beside it, no trailing line break). Back both up in the password manager and on the private drive.
  2. Put the new public key (`madoyomi.key.pub`'s text) into `plugins.updater.pubkey` in `tauri.conf.json`, and replace both secrets (the commands above).
  3. Release a new version with it. **Installed copies built with the old key can't update to it:** the updater rejects the new signature. Their users must download and run the new installer once by hand (an install over the old one keeps the data). Say so in that release's notes and on the release page.
  4. If the old key still exists, one bridge release is possible: sign version N with the **old** key but carry the **new** public key in its `tauri.conf.json`. Old copies update to N, and N then accepts N+1 signed with the new key. Without the old key there is no bridge.
