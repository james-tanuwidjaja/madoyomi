import { isApiError } from "./client";

/** What the UI says about a failed request: a readable line for the code, plus the server's own message. */
export interface ErrorDescription {
  /** The error code, if the error has one (`REPO_FETCH_FAILED`, `NETWORK`, …). */
  code: string | null;
  /** One readable sentence about what went wrong, or null for a code without one. */
  hint: string | null;
  /** The message the backend (or the client) gave. */
  message: string;
}

/** A readable sentence per error code (server codes from madoyomi.core.error.ErrorCode, client codes from client.ts). */
const HINTS: Record<string, string> = {
  REPO_FETCH_FAILED:
    "Couldn't download the repo's index. Check the URL and your internet connection.",
  REPO_FORMAT_MISMATCH:
    "That URL isn't a repo index Madoyomi can read (index.min.json, repo.json or index.pb).",
  REPO_TYPE_MISMATCH: "The repo's extensions aren't of the kind it was added as.",
  SIGNATURE_INVALID: "The extension's APK isn't properly signed, so it wasn't installed.",
  SIGNATURE_CHANGED:
    "The update is signed with a different certificate than the installed version. Trust the new certificate first, or keep the installed version.",
  UNTRUSTED: "The extension's signing certificate isn't trusted, so it isn't loaded.",
  PACKAGE_MISMATCH: "The downloaded APK doesn't match what the repo lists for it.",
  INSTALL_FAILED: "The extension couldn't be installed, updated or removed.",
  LOAD_FAILED: "The extension was installed but couldn't be loaded.",
  EXTENSION_INCOMPATIBLE: "The extension needs an extension API this version of Madoyomi lacks.",
  SOURCE_FAILED: "The source's website failed or couldn't be reached.",
  PLAYER_NOT_FOUND:
    "mpv wasn't found. Install mpv and put it on PATH, or set its path in Settings → Player.",
  PLAYER_FAILED: "mpv couldn't be started. The backend's log has the details.",
  NOT_FOUND: "It no longer exists on the backend.",
  BAD_REQUEST: "The backend refused the request.",
  UNAUTHORIZED: "The backend refused the app's token.",
  FORBIDDEN: "The backend doesn't allow that.",
  INTERNAL: "Something went wrong inside the backend. Its log has the details.",
  NETWORK: "The backend didn't answer. It may be restarting.",
  BACKEND_UNAVAILABLE: "The backend isn't running right now. It may be restarting.",
  BAD_RESPONSE: "The backend gave an answer the app doesn't understand.",
};

export function describeCode(code: string | null | undefined): string | null {
  return code ? (HINTS[code] ?? null) : null;
}

export function describeError(e: unknown): ErrorDescription {
  if (isApiError(e)) return { code: e.code, hint: describeCode(e.code), message: e.message };
  return { code: null, hint: null, message: e instanceof Error ? e.message : String(e) };
}
