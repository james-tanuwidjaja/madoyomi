// CHANGELOG.md's section of a version (the heading's match fixed in 0.1.1), used by
// tools/release-notes.mjs and tested by app/src/utils/__tests__/changelog.spec.ts.
//
// The section is everything under a heading "## X.Y.Z" (also "## [X.Y.Z]", "## X.Y.Z - 2026-11-21" or
// "## X.Y.Z (2026-11-21)") up to the next "## " heading, trimmed. Only spaces and tabs may follow the version on the
// heading's line: the earlier `\s*` also matched the line breaks after it, so a section starting with a "- " bullet
// lost that bullet to the "heading" (found when 0.1.1's one-bullet section came out empty).

const VERSION = /^\d+\.\d+\.\d+$/;

/** Escapes every RegExp metacharacter, so the text matches literally. */
function escapeRegExp(text) {
  return text.replace(/[.*+?^${}()|[\]\\/]/g, '\\$&');
}

/**
 * @param {string} changelog CHANGELOG.md's text (CRLF or LF)
 * @param {string} version X.Y.Z
 * @returns {string | null} the section (an empty string when it has no text), or null when there is no heading
 * @throws {RangeError} when the version isn't X.Y.Z
 */
export function changelogSection(changelog, version) {
  // Only a plain X.Y.Z goes into the heading's RegExp, and it is escaped in full, so nothing in it acts as a
  // pattern (escaping only the dots let a version such as "0.1.*" match other headings).
  if (typeof version !== 'string' || !VERSION.test(version)) {
    throw new RangeError(`not a version (X.Y.Z): ${JSON.stringify(version)}`);
  }
  const text = changelog.replace(/\r\n/g, '\n');
  const esc = escapeRegExp(version);
  const heading = new RegExp(`^## \\[?${esc}\\]?(?:[ \\t]+(?:-|\\().*|[ \\t]*)$`, 'm');
  const m = heading.exec(text);
  if (!m) return null;
  const rest = text.slice(m.index + m[0].length);
  const next = rest.search(/^## /m);
  return (next === -1 ? rest : rest.slice(0, next)).trim();
}
