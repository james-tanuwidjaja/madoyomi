#!/usr/bin/env node
// Looks for secrets in files: CI runs it on everything a release uploads, with the updater's key
// and its password in the environment. It never prints a secret: only where one was found.
//
//   node tools/scan-secrets.mjs --env NAME [--env NAME ...] <file or folder> ...
//
// Each --env names an environment variable whose value is searched for (as UTF-8 and as UTF-16LE) in every file,
// folders walked recursively. A variable that is unset or shorter than 8 characters is an error (a scan that
// can't find anything proves nothing). Exit 0: none found; 1: found (the files are listed); 2: usage.

import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

const args = process.argv.slice(2);
const names = [];
const paths = [];
for (let i = 0; i < args.length; i++) {
  if (args[i] === '--env') names.push(args[++i]);
  else paths.push(args[i]);
}
if (names.length === 0 || paths.length === 0 || names.some((n) => !n)) {
  console.error('usage: node tools/scan-secrets.mjs --env NAME [--env NAME ...] <file or folder> ...');
  process.exit(2);
}

const needles = [];
for (const name of names) {
  const value = (process.env[name] ?? '').trim();
  if (value.length < 8) {
    console.error(`scan-secrets: ${name} is unset or too short to scan for`);
    process.exit(2);
  }
  needles.push({ name, forms: [Buffer.from(value, 'utf8'), Buffer.from(value, 'utf16le')] });
}

function* files(p) {
  const st = statSync(p);
  if (st.isDirectory()) for (const e of readdirSync(p)) yield* files(join(p, e));
  else if (st.isFile()) yield p;
}

let scanned = 0;
let bytes = 0;
let found = 0;
for (const root of paths) {
  for (const f of files(root)) {
    const data = readFileSync(f);
    scanned++;
    bytes += data.length;
    for (const n of needles) {
      if (n.forms.some((b) => data.indexOf(b) !== -1)) {
        console.log(`FOUND ${n.name} in ${f}`);
        found++;
      }
    }
  }
}
console.log(`scan-secrets: ${scanned} file(s), ${(bytes / 1048576).toFixed(1)} MiB, ${names.length} secret(s): ${found === 0 ? 'none found' : `${found} hit(s)`}`);
process.exit(found === 0 ? 0 : 1);
