#!/usr/bin/env node
// Checks a release's updater files: latest.json and the NSIS installer it points at.
//
//   node tools/verify-updater.mjs <latest.json> <installer.exe> [--version X.Y.Z] [--repo owner/name] [--config path]
//
// What it checks:
//   - latest.json: version (default: the repo's VERSION), notes, an RFC 3339 pub_date, and the same URL and signature
//     under windows-x86_64-nsis and windows-x86_64 (what app/src-tauri/src/updater.rs expects);
//   - the URL: https://github.com/<repo>/releases/download/v<version>/<installer's file name> (the URL the asset has
//     once the draft is published; --repo default: james-tanuwidjaja/madoyomi; --url-any skips this check);
//   - the signature (minisign, as tauri-plugin-updater checks it): its key id is the public key's in tauri.conf.json
//     (plugins.updater.pubkey; --config for another file), the Ed25519 signature over the installer's BLAKE2b-512
//     (or over the bytes, for a legacy "Ed" signature), the global signature over the trusted comment, and the
//     trusted comment's file name;
//   - <installer>.sig, when it is there, holds the same signature.
// Prints one line per check and exits 1 when any fails. Needs only Node (18 or newer); reads no secret.

import { createHash, createPublicKey, verify } from 'node:crypto';
import { createReadStream, existsSync, readFileSync } from 'node:fs';
import { basename, dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');

function usage(msg) {
  if (msg) console.error(`error: ${msg}`);
  console.error(
    'usage: node tools/verify-updater.mjs <latest.json> <installer.exe> [--version X.Y.Z] [--repo owner/name] [--config tauri.conf.json] [--url-any]',
  );
  process.exit(2);
}

const args = process.argv.slice(2);
const positional = [];
const opts = { repo: 'james-tanuwidjaja/madoyomi', config: join(repoRoot, 'app', 'src-tauri', 'tauri.conf.json') };
for (let i = 0; i < args.length; i++) {
  const a = args[i];
  if (a === '--url-any') opts.urlAny = true;
  else if (a === '--version' || a === '--repo' || a === '--config') {
    if (i + 1 >= args.length) usage(`${a} needs a value`);
    opts[a.slice(2)] = args[++i];
  } else if (a.startsWith('--')) usage(`unknown option ${a}`);
  else positional.push(a);
}
if (positional.length !== 2) usage();
const [latestPath, installerPath] = positional;
if (!opts.version) opts.version = readFileSync(join(repoRoot, 'VERSION'), 'utf8').trim();

let failed = 0;
function check(name, ok, detail = '') {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? `: ${detail}` : ''}`);
  if (!ok) failed++;
  return ok;
}

const readJson = (p) => JSON.parse(readFileSync(p, 'utf8').replace(/^\uFEFF/, ''));
const b64 = (s) => Buffer.from(s.trim(), 'base64');

// A minisign key or signature file: lines, the untrusted comment first.
function minisignLines(text) {
  return text.replace(/\r/g, '').split('\n').filter((l) => l.length > 0);
}

function ed25519Key(raw32) {
  return createPublicKey({ key: { kty: 'OKP', crv: 'Ed25519', x: raw32.toString('base64url') }, format: 'jwk' });
}

async function blake2b512(path) {
  const h = createHash('blake2b512');
  for await (const chunk of createReadStream(path)) h.update(chunk);
  return h.digest();
}

const latest = readJson(latestPath);
const installerName = basename(installerPath);
check('installer exists', existsSync(installerPath), installerPath);

// --- latest.json ---
check('version', latest.version === opts.version, `${latest.version} (expected ${opts.version})`);
check('notes', typeof latest.notes === 'string' && latest.notes.trim().length > 0, JSON.stringify(latest.notes ?? null).slice(0, 80));
check(
  'pub_date',
  typeof latest.pub_date === 'string' && /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ$/.test(latest.pub_date) && !isNaN(Date.parse(latest.pub_date)),
  latest.pub_date,
);
const nsis = latest.platforms?.['windows-x86_64-nsis'];
const plain = latest.platforms?.['windows-x86_64'];
check('platforms', !!nsis && !!plain, Object.keys(latest.platforms ?? {}).join(', '));
if (!nsis || !plain) process.exit(1);
check('same entry under both platforms', nsis.url === plain.url && nsis.signature === plain.signature);
const wantUrl = `https://github.com/${opts.repo}/releases/download/v${opts.version}/${encodeURIComponent(installerName)}`;
if (opts.urlAny) check('url names the installer', nsis.url.endsWith(`/${installerName}`), nsis.url);
else check('url', nsis.url === wantUrl, nsis.url);

// --- the signature ---
const pubkeyText = b64(readJson(opts.config).plugins.updater.pubkey).toString('utf8');
const pk = b64(minisignLines(pubkeyText)[1]);
check('public key', pk.length === 42 && pk.subarray(0, 2).toString() === 'Ed', `key id ${Buffer.from(pk.subarray(2, 10)).reverse().toString('hex').toUpperCase()}`);
const keyId = pk.subarray(2, 10);
const publicKey = ed25519Key(pk.subarray(10, 42));

const sigText = b64(nsis.signature).toString('utf8');
const lines = minisignLines(sigText);
const ok4 = check('signature layout', lines.length === 4 && lines[0].startsWith('untrusted comment:') && lines[2].startsWith('trusted comment: '));
if (!ok4) process.exit(1);
const sig = b64(lines[1]);
const alg = sig.subarray(0, 2).toString();
check('signature algorithm', sig.length === 74 && (alg === 'ED' || alg === 'Ed'), alg === 'ED' ? 'ED (BLAKE2b-512 prehashed)' : alg);
check('key id matches tauri.conf.json', sig.subarray(2, 10).equals(keyId));
const message = alg === 'ED' ? await blake2b512(installerPath) : readFileSync(installerPath);
check('signature over the installer', verify(null, message, publicKey, sig.subarray(10, 74)), `${installerName}, ${readFileSync(installerPath).length} bytes`);
const trusted = lines[2].slice('trusted comment: '.length);
const globalSig = b64(lines[3]);
check('global signature over the trusted comment', verify(null, Buffer.concat([sig.subarray(10, 74), Buffer.from(trusted, 'utf8')]), publicKey, globalSig));
const fileField = /(?:^|\t)file:([^\t]*)/.exec(trusted)?.[1];
check("trusted comment's file", fileField === installerName, fileField ?? '(none)');

const sigFile = `${installerPath}.sig`;
if (existsSync(sigFile)) check('.sig file is the same signature', readFileSync(sigFile, 'utf8').trim() === nsis.signature.trim());
else console.log(`skip  ${basename(sigFile)} not beside the installer`);

console.log(failed === 0 ? 'OK: latest.json and the installer verify' : `${failed} check(s) failed`);
process.exit(failed === 0 ? 0 : 1);
