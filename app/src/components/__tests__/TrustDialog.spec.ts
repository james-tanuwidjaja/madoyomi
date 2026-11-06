import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia, type Pinia } from "pinia";
import { flushPromises, type VueWrapper } from "@vue/test-utils";
import type { QueryClient } from "@tanstack/vue-query";

import { createQueryClient } from "../../api/realtime";
import { makeExtension, makeStore, YUZONO_STORE_URL } from "../../test/fixtures";
import { mountView, setBackendReady } from "../../test/mountView";

const ops = vi.hoisted(() => ({
  fetchExtensionStores: vi.fn(),
  trustExtension: vi.fn(),
}));

vi.mock("../../api/operations", async (importOriginal) => ({
  ...(await importOriginal<object>()),
  fetchExtensionStores: ops.fetchExtensionStores,
  trustExtension: ops.trustExtension,
}));

import TrustDialog from "../TrustDialog.vue";

const HASH = "50ab1d722bf6ddac0a4d4b8a02e0c4a2c5d8b8ab3a37b6e0e20a3a9e1ee6aa51";
const untrusted = makeExtension({
  isInstalled: true,
  isTrusted: false,
  signatureHash: HASH,
  versionName: "14.7",
});

let pinia: Pinia;
let queryClient: QueryClient;
let wrapper: VueWrapper | null = null;

const dialog = () => document.body.querySelector<HTMLElement>('[data-testid="trust-dialog"]');
const button = (action: string) =>
  document.body.querySelector<HTMLButtonElement>(`[data-action="${action}"]`)!;

async function open() {
  wrapper = mountView(TrustDialog, { pinia, queryClient }, { open: true, extension: untrusted });
  await flushPromises();
  return wrapper;
}

beforeEach(() => {
  pinia = createPinia();
  setActivePinia(pinia);
  queryClient = createQueryClient();
  setBackendReady();
  ops.fetchExtensionStores.mockResolvedValue([
    makeStore({ indexUrl: YUZONO_STORE_URL, name: "Yuzono Anime", type: "ANIME" }),
  ]);
});

afterEach(() => {
  wrapper?.unmount();
  wrapper = null;
  queryClient.clear();
  vi.clearAllMocks();
});

describe("TrustDialog", () => {
  it("shows the package, version, repo, certificate hash and the warning", async () => {
    await open();

    const d = dialog()!;
    expect(d).not.toBeNull();
    expect(d.querySelector('[data-testid="trust-pkg"]')!.textContent).toBe(untrusted.pkgName);
    expect(d.textContent).toContain("AnimeGG");
    expect(d.textContent).toContain("14.7");
    expect(d.querySelector('[data-testid="trust-repo"]')!.textContent).toContain("Yuzono Anime");
    expect(d.querySelector('[data-testid="trust-hash"]')!.textContent).toBe(HASH);
    expect(d.querySelector('[data-testid="trust-warning"]')!.textContent).toContain(
      "full access to this PC",
    );
  });

  it("trusts the extension and closes", async () => {
    ops.trustExtension.mockResolvedValue({
      extension: { ...untrusted, isTrusted: true, isLoaded: true },
      trustedSignature: { hash: HASH, origin: "USER" },
    });
    const w = await open();

    button("confirm-trust").click();
    await flushPromises();

    expect(ops.trustExtension).toHaveBeenCalledExactlyOnceWith(untrusted.pkgName);
    expect(w.emitted("update:open")).toEqual([[false]]);
    expect(w.emitted("trusted")?.[0]?.[0]).toMatchObject({ isTrusted: true });
  });

  it("shows why trusting failed and stays open", async () => {
    const { ApiError } = await import("../../api/client");
    ops.trustExtension.mockRejectedValue(
      new ApiError({ kind: "graphql", code: "NOT_FOUND", message: "No extension x" }),
    );
    const w = await open();

    button("confirm-trust").click();
    await flushPromises();

    expect(dialog()!.textContent).toContain("NOT_FOUND");
    expect(w.emitted("update:open")).toBeUndefined();
  });

  it("does nothing on Cancel", async () => {
    const w = await open();

    button("cancel-trust").click();
    await flushPromises();

    expect(ops.trustExtension).not.toHaveBeenCalled();
    expect(w.emitted("update:open")).toEqual([[false]]);
  });

  it("does nothing on Escape", async () => {
    const w = await open();

    dialog()!.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", bubbles: true }));
    await flushPromises();

    expect(ops.trustExtension).not.toHaveBeenCalled();
    expect(w.emitted("update:open")).toEqual([[false]]);
  });
});
