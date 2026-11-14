import { describe, expect, it } from "vitest";
import {
  DEFAULT_READER_PREFS,
  genreToken,
  isWebtoonSource,
  MANGA_DIRECTION_KEY,
  MANGA_MODE_KEY,
  modeOriginLabel,
  parseMangaMeta,
  parseReaderPrefs,
  resolveDirection,
  resolveReaderMode,
  webtoonGenre,
} from "../readerSettings";

/* The reader's settings (Phase 5 Stage 5): the stored values read field by field, a manga's meta, and the mode by
   the manga's own, its genres, its source's name and the global default (§8). */

describe("parseReaderPrefs", () => {
  it("gives the defaults for nothing, or for something that isn't an object", () => {
    expect(parseReaderPrefs(null)).toEqual(DEFAULT_READER_PREFS);
    expect(parseReaderPrefs("webtoon")).toEqual(DEFAULT_READER_PREFS);
    expect(parseReaderPrefs([1, 2])).toEqual(DEFAULT_READER_PREFS);
  });

  it("keeps the known values and replaces each unknown one by its default", () => {
    expect(
      parseReaderPrefs({
        mode: "webtoon",
        direction: "ltr",
        fit: "width",
        stripWidth: 1200,
        pageGap: true,
        background: "white",
        preload: 8,
      }),
    ).toEqual({
      mode: "webtoon",
      direction: "ltr",
      fit: "width",
      stripWidth: 1200,
      pageGap: true,
      background: "white",
      preload: 8,
    });
    expect(
      parseReaderPrefs({
        mode: "vertical",
        direction: "ttb",
        fit: "stretch",
        stripWidth: 700,
        pageGap: "yes",
        background: "#123456",
        preload: 100,
        extra: 1,
      }),
    ).toEqual(DEFAULT_READER_PREFS);
    // a partial object (an older version's): the rest are defaults
    expect(parseReaderPrefs({ fit: "original", stripWidth: 0 })).toEqual({
      ...DEFAULT_READER_PREFS,
      fit: "original",
      stripWidth: 0,
    });
  });

  it("defaults to Single, right to left, fit screen, 800 px, no gap, dark, 4 pages", () => {
    expect(DEFAULT_READER_PREFS).toEqual({
      mode: "paged",
      direction: "rtl",
      fit: "screen",
      stripWidth: 800,
      pageGap: false,
      background: "dark",
      preload: 4,
    });
  });
});

describe("parseMangaMeta", () => {
  it("reads the manga's own mode and direction among its other meta", () => {
    expect(
      parseMangaMeta([
        { key: "flutter_readerMode", value: "x" },
        { key: MANGA_MODE_KEY, value: "double" },
        { key: MANGA_DIRECTION_KEY, value: "ltr" },
      ]),
    ).toEqual({ mode: "double", direction: "ltr" });
  });

  it("reads none, or an unknown value, as not set", () => {
    expect(parseMangaMeta([])).toEqual({ mode: null, direction: null });
    expect(
      parseMangaMeta([
        { key: MANGA_MODE_KEY, value: "Webtoon" },
        { key: MANGA_DIRECTION_KEY, value: "" },
      ]),
    ).toEqual({ mode: null, direction: null });
  });
});

describe("the mode by content (§8)", () => {
  it("normalizes a genre: case, parentheses, spaces, hyphens", () => {
    expect(genreToken("Long Strip")).toBe("longstrip");
    expect(genreToken("long-strip")).toBe("longstrip");
    expect(genreToken("Manhwa (Korean)")).toBe("manhwa");
    expect(genreToken(" MANHUA ")).toBe("manhua");
  });

  it("finds the genres and tags that mean a vertical strip, and not the others", () => {
    expect(webtoonGenre(["Action", "Long Strip", "Full Color"])).toBe("Long Strip");
    expect(webtoonGenre(["Long strip"])).toBe("Long strip");
    expect(webtoonGenre(["Fantasy", "Manhwa (Korean)"])).toBe("Manhwa (Korean)");
    expect(webtoonGenre(["Manhua"])).toBe("Manhua");
    expect(webtoonGenre(["Webtoons"])).toBe("Webtoons");
    expect(webtoonGenre(["Webtoon"])).toBe("Webtoon");
    expect(webtoonGenre(["Action", "Manga", "Fantasy", "Strip Club", "Long"])).toBeNull();
    expect(webtoonGenre([])).toBeNull();
  });

  it("knows a webtoon site by its source's name", () => {
    expect(isWebtoonSource("Webtoons.com")).toBe(true);
    expect(isWebtoonSource("WEBTOON XYZ")).toBe(true);
    expect(isWebtoonSource("MangaPill")).toBe(false);
    expect(isWebtoonSource(null)).toBe(false);
    expect(isWebtoonSource(undefined)).toBe(false);
  });

  it("picks the manga's own mode first, then a genre, then the source, then the default", () => {
    const base = { genres: ["Long Strip"], sourceName: "Webtoons.com", global: "double" as const };
    expect(resolveReaderMode({ ...base, manga: "paged" })).toEqual({
      mode: "paged",
      origin: { kind: "manga" },
    });
    expect(resolveReaderMode({ ...base, manga: null })).toEqual({
      mode: "webtoon",
      origin: { kind: "genre", genre: "Long Strip" },
    });
    expect(resolveReaderMode({ ...base, manga: null, genres: ["Fantasy"] })).toEqual({
      mode: "webtoon",
      origin: { kind: "source", source: "Webtoons.com" },
    });
    expect(
      resolveReaderMode({
        manga: null,
        genres: ["Fantasy"],
        sourceName: "MangaPill",
        global: "double",
      }),
    ).toEqual({ mode: "double", origin: { kind: "default" } });
    expect(resolveReaderMode({ manga: null, genres: [], global: "paged" })).toEqual({
      mode: "paged",
      origin: { kind: "default" },
    });
  });

  it("takes the manga's direction, else the global one", () => {
    expect(resolveDirection("ltr", "rtl")).toBe("ltr");
    expect(resolveDirection(null, "rtl")).toBe("rtl");
  });

  it("says where a mode came from", () => {
    expect(modeOriginLabel({ kind: "manga" })).toBe("This manga's");
    expect(modeOriginLabel({ kind: "genre", genre: "Manhwa" })).toBe("From the genre “Manhwa”");
    expect(modeOriginLabel({ kind: "source", source: "Webtoons.com" })).toBe(
      "From the source (Webtoons.com)",
    );
    expect(modeOriginLabel({ kind: "default" })).toBe("The default");
  });
});
