#!/usr/bin/env node
// The release notes of a version (Phase 7 Stage 5), from CHANGELOG.md's "## X.Y.Z" section.
//
//   node tools/release-notes.mjs [X.Y.Z] [--notes <file>] [--body <file>] [--repo owner/name]
//
// The version defaults to the repo's VERSION. The section is everything under a heading "## X.Y.Z" (also
// "## [X.Y.Z]", "## X.Y.Z - 2026-11-21" or "## X.Y.Z (2026-11-21)") up to the next "## " heading. It fails (exit 1)
// when the section is missing or empty, so a release can't go out without notes.
//   --notes <file>  writes the section as it is: build-release.ps1 -NotesFile puts it into latest.json, and the
//                   app's update dialog shows it (plain text);
//   --body <file>   writes the GitHub release's text: the section, then how to install (the SmartScreen note) and
//                   the bundled programs with their sources (from tools/binaries.json).
// With neither, it prints the section. Keep CHANGELOG.md's sections short: the update dialog shows them whole.

import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const args = process.argv.slice(2);
const opts = { repo: 'james-tanuwidjaja/madoyomi' };
let version;
for (let i = 0; i < args.length; i++) {
  const a = args[i];
  if (a === '--notes' || a === '--body' || a === '--repo') {
    if (i + 1 >= args.length) fail(`${a} needs a value`);
    opts[a.slice(2)] = args[++i];
  } else if (a.startsWith('--')) fail(`unknown option ${a}`);
  else version = a;
}
if (!version) version = readFileSync(join(repoRoot, 'VERSION'), 'utf8').trim();
if (!/^\d+\.\d+\.\d+$/.test(version)) fail(`not a version: ${version}`);

function fail(msg) {
  console.error(`release-notes: ${msg}`);
  process.exit(1);
}

const changelog = readFileSync(join(repoRoot, 'CHANGELOG.md'), 'utf8').replace(/\r\n/g, '\n');
const esc = version.replace(/\./g, '\\.');
const heading = new RegExp(`^## \\[?${esc}\\]?(?:\\s*(?:-|\\(|$).*)?$`, 'm');
const m = heading.exec(changelog);
if (!m) fail(`no "## ${version}" section in CHANGELOG.md`);
const rest = changelog.slice(m.index + m[0].length);
const next = rest.search(/^## /m);
const section = (next === -1 ? rest : rest.slice(0, next)).trim();
if (!section) fail(`the "## ${version}" section of CHANGELOG.md is empty`);

if (!opts.notes && !opts.body) {
  console.log(section);
  process.exit(0);
}
if (opts.notes) writeFileSync(opts.notes, `${section}\n`, 'utf8');
if (opts.body) {
  const tools = JSON.parse(readFileSync(join(repoRoot, 'tools', 'binaries.json'), 'utf8')).tools;
  const tag = `v${version}`;
  const blob = `https://github.com/${opts.repo}/blob/${tag}`;
  const body = [
    section,
    '',
    '### Installing',
    '',
    `- **\`Madoyomi_${version}_x64-setup.exe\`**: the installer for most people. It installs for you only (no admin) and can update itself: Settings → General → "Check for app updates".`,
    `- **\`Madoyomi_${version}_x64_en-US.msi\`**: installs for all users (needs admin). An MSI install doesn't update itself; the app says when a new version is out.`,
    '- The installers aren\'t code-signed yet, so Windows SmartScreen may say "Windows protected your PC": choose **More info → Run anyway**.',
    '- `SHA256SUMS.txt` has the SHA-256 of each file; `latest.json` is the app updater\'s feed.',
    '- Updating keeps your library, history, downloads and settings. Uninstalling keeps them too unless you tick "Delete my library, settings and downloads too".',
    '',
    '### Third-party software',
    '',
    'The installers bundle a Java runtime linked from Eclipse Temurin JDK 21 and these programs, each with its license texts in its own folder:',
    '',
    ...tools.map((t) => `- ${t.name} ${t.version}: ${t.license}. Source: ${t.source}`),
    '',
    `mpv and FFmpeg are GPL: [THIRD_PARTY.md](${blob}/THIRD_PARTY.md) lists the source of each, and of the libraries in mpv's build, at the versions bundled. Madoyomi's own source (MPL-2.0) is this release's tag, \`${tag}\`.`,
    '',
  ].join('\n');
  writeFileSync(opts.body, body, 'utf8');
}
console.log(`release-notes: ${version}: ${section.split('\n').length} line(s) from CHANGELOG.md`);
