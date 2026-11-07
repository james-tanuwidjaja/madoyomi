import { describe, expect, it } from "vitest";
import { ApiError } from "../client";
import { describeCode, describeError } from "../errorText";
import { SERVER_ERROR_CODES, type ClientErrorCode } from "../types";

// The server's error model, read as text, so a code added there without a readable line here fails this test. Vite
// refuses a `?raw` import from outside app/, and TypeScript 6 has no node types here, so node:fs is loaded untyped.
// The path is relative to app/, where vitest runs (jsdom's import.meta.url isn't a file URL).
const fs = (await import("node:fs" as string)) as {
  readFileSync(path: string, encoding: "utf8"): string;
};
const errorModelKt = fs.readFileSync(
  "../server/core/src/main/kotlin/madoyomi/core/error/MadoyomiException.kt",
  "utf8",
);

/* Readable error lines (Phase 3 step 22, "API client error handling"): every code the server or the client can give
   has one, and the client's list of server codes is the server's. */

const CLIENT_CODES: ClientErrorCode[] = ["NETWORK", "BACKEND_UNAVAILABLE", "BAD_RESPONSE"];

/** The constants of `enum class ErrorCode(val httpStatus: Int)`: `NAME(status),` lines inside its body. */
function serverCodes(source: string): string[] {
  const start = source.indexOf("enum class ErrorCode");
  expect(start).toBeGreaterThanOrEqual(0);
  const open = source.indexOf("{", start);
  // The body ends at the first `}` at the start of a line (doc comments in it hold `;` and `}` elsewhere).
  const body = source.slice(open, source.indexOf("\n}", open));
  return [...body.matchAll(/^\s*([A-Z][A-Z0-9_]*)\(\d{3}\)/gm)].map((m) => m[1]!);
}

describe("error codes", () => {
  it("lists the server's codes as the server has them", () => {
    const codes = serverCodes(errorModelKt);
    expect(codes.length).toBeGreaterThan(10);
    expect([...SERVER_ERROR_CODES].sort()).toEqual([...codes].sort());
  });

  it.each([...SERVER_ERROR_CODES, ...CLIENT_CODES])("has a readable line for %s", (code) => {
    expect(describeCode(code)).toMatch(/\.$/);
  });

  it("has no line for no code or an unknown one", () => {
    expect(describeCode(null)).toBeNull();
    expect(describeCode(undefined)).toBeNull();
    expect(describeCode("")).toBeNull();
    expect(describeCode("SOMETHING_NEW")).toBeNull();
  });
});

describe("describeError", () => {
  it("describes an ApiError by its code, with the server's message", () => {
    const e = new ApiError({ code: "SOURCE_FAILED", message: "HTTP error 404", kind: "graphql" });
    expect(describeError(e)).toEqual({
      code: "SOURCE_FAILED",
      hint: describeCode("SOURCE_FAILED"),
      message: "HTTP error 404",
    });
  });

  it("keeps an unknown code with no line", () => {
    const e = new ApiError({ code: "SOMETHING_NEW", message: "new", kind: "graphql" });
    expect(describeError(e)).toEqual({ code: "SOMETHING_NEW", hint: null, message: "new" });
  });

  it("describes any other error or value by its message", () => {
    expect(describeError(new TypeError("boom"))).toEqual({
      code: null,
      hint: null,
      message: "boom",
    });
    expect(describeError("plain")).toEqual({ code: null, hint: null, message: "plain" });
  });
});
