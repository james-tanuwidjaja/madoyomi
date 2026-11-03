# Phase 8 — Later

> Back to [PLAN.md](../PLAN.md) · Prev: [Phase 7](phase-7-packaging-release.md)

## 1. Goal

Features that are valuable but not needed for the first release: syncing progress with trackers (AniList, MyAnimeList, Kitsu), Discord Rich Presence, and torrent-based anime sources. Each item is independent and can be picked up in any order after Phase 7. This file is a lighter backlog; expand an item into full steps when work on it starts.

## 2. Depends on / inputs

- Phase 7 released (stable data model, updater in place).
- Progress events from Phase 4 (playback) and Phase 5 (reader).
- Backup format from Phase 6 (tracker bindings must be included).

## 3. Deliverables

- `server/core/track/`: tracker interface + AniList, MyAnimeList, Kitsu implementations; `track` table binding library entries to remote IDs.
- OAuth flows through the system browser + a loopback redirect or deep link (`madoyomi://`) handled by Tauri.
- UI: "Tracking" panel on Details (search remote, bind, status/score/progress), tracker accounts in Settings.
- Discord Rich Presence module (Tauri or backend) with privacy settings.
- Torrent support: a sidecar or library (e.g. libtorrent-based or a webtorrent/`stream` sidecar) that serves a local HTTP stream to mpv.

## 4. Detailed steps

### Trackers
1. Tracker interface: `login`, `logout`, `search(query)`, `bind(entry, remoteId)`, `update(progress, status, score)`, `refresh`.
2. AniList (GraphQL, OAuth implicit), MyAnimeList (OAuth2 PKCE), Kitsu (OAuth password grant / token).
3. Store tokens with Windows Credential Manager (via Tauri plugin or JNA) instead of plain DB fields.
4. Auto-update the remote progress when an episode is watched / chapter read (setting), with an offline queue and retry.
5. Include bindings (not tokens) in backups; restore and re-bind.

### Discord Rich Presence
6. Use the Discord IPC pipe (`\\.\pipe\discord-ipc-0`) with an app client ID.
7. Show "Watching {title} — Episode N" / "Reading {title} — Chapter N", with a toggle and an "incognito per source/entry" option.

### Torrent sources
8. Detect `magnet:` or `.torrent` video URLs from extensions (marked unsupported in Phase 2).
9. Torrent sidecar: add the magnet, pick the right file (episode match), stream it sequentially over `http://127.0.0.1:<port>/…`, and give that URL to mpv.
10. Settings: download dir, max connections, seeding on/off, cache cleanup. Show a clear legal/usage notice.

## 5. Checklist

### Trackers
- [ ] Tracker interface + `track` table
- [ ] AniList
- [ ] MyAnimeList
- [ ] Kitsu
- [ ] OAuth via system browser + redirect handling
- [ ] Tokens in Windows Credential Manager
- [ ] Auto progress sync + offline retry queue
- [ ] Tracking panel on Details + accounts in Settings
- [ ] Bindings included in backups

### Discord Rich Presence
- [ ] Discord IPC client
- [ ] Watching/reading presence
- [ ] Toggle + incognito options

### Torrent sources
- [ ] Detect magnet/torrent videos
- [ ] Torrent streaming sidecar chosen and integrated
- [ ] mpv plays the local stream
- [ ] Torrent settings + cleanup
- [ ] Usage notice shown

## 6. Exit criteria

Per feature:
- [ ] Trackers: watching an episode updates AniList/MAL/Kitsu progress automatically.
- [ ] Discord: presence shows while watching/reading and clears on stop.
- [ ] Torrent: one torrent-based Aniyomi extension plays an episode through mpv.

## 7. Risks & notes

- Tracker APIs and rate limits change; keep each tracker isolated behind the interface.
- Token storage is security-sensitive: never log tokens, and never write them to backups.
- Torrent support has legal and network implications. Keep it opt-in and clearly labeled.

## 8. Decisions closed here

- Torrent engine choice (library vs. separate sidecar process).
- Whether trackers live in the backend (default) or the Tauri shell.
