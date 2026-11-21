/** CHANGELOG.md's section of a version: "" when it has no text, null when there is no "## X.Y.Z" heading. */
export function changelogSection(changelog: string, version: string): string | null;
