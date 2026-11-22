import { describe, expect, it } from "vitest";
import { changelogSection } from "../../../../tools/lib/changelog.mjs";

/*
 * The release notes' section of CHANGELOG.md (tools/lib/changelog.mjs, used by tools/release-notes.mjs for the
 * GitHub release and latest.json, so the update dialog shows it). Here so CI's app tests cover the release tooling.
 */

const changelog = [
  "# Changelog",
  "",
  "Intro text.",
  "",
  "## 0.1.10",
  "",
  "- Ten.",
  "",
  "## 0.1.1",
  "",
  "- The first bullet.",
  "- The second bullet.",
  "",
  "## [0.1.0] - 2026-11-21",
  "",
  "The first release.",
  "",
  "- A bullet.",
  "",
  "## 0.0.9 (2026-11-01)",
  "",
  "## 0.0.8",
  "",
].join("\r\n");

describe("changelogSection", () => {
  it("keeps a section's first bullet: the heading ends at its own line", () => {
    expect(changelogSection(changelog, "0.1.1")).toBe("- The first bullet.\n- The second bullet.");
  });

  it("matches the version exactly, with brackets, a date after a dash or in brackets", () => {
    expect(changelogSection(changelog, "0.1.10")).toBe("- Ten.");
    expect(changelogSection(changelog, "0.1.0")).toBe("The first release.\n\n- A bullet.");
    expect(changelogSection(changelog, "0.0.9")).toBe("");
  });

  it("says when there is no section, and gives an empty one as empty", () => {
    expect(changelogSection(changelog, "0.2.0")).toBeNull();
    expect(changelogSection(changelog, "0.0.8")).toBe("");
  });
});
