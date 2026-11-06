import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia, type Pinia } from "pinia";
import { flushPromises, type VueWrapper } from "@vue/test-utils";
import type { QueryClient } from "@tanstack/vue-query";

import { createQueryClient } from "../../api/realtime";
import { ApiError } from "../../api/client";
import { makeStore, YUZONO_STORE_URL } from "../../test/fixtures";
import { mountView, setBackendReady } from "../../test/mountView";

const ops = vi.hoisted(() => ({
  fetchExtensionStores: vi.fn(),
  addRepo: vi.fn(),
  removeRepo: vi.fn(),
  refreshRepos: vi.fn(),
}));

vi.mock("../../api/operations", async (importOriginal) => ({
  ...(await importOriginal<object>()),
  ...ops,
}));

import ReposView from "../ReposView.vue";

const KEIYOUSHI_URL = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb";
const YUZONO_URL = "https://raw.githubusercontent.com/yuzono/anime-repo/repo/index.min.json";

const yuzono = makeStore({
  indexUrl: YUZONO_STORE_URL,
  name: "Yuzono Anime",
  type: "ANIME",
  extensions: { totalCount: 249 },
});
const keiyoushi = makeStore({
  indexUrl: KEIYOUSHI_URL,
  signingKey: "",
  signingKeyTrusted: false,
  lastError: "HTTP 503",
});

let pinia: Pinia;
let queryClient: QueryClient;
let wrapper: VueWrapper;

async function mountRepos() {
  wrapper = mountView(ReposView, { pinia, queryClient });
  await flushPromises();
}

const rows = () => wrapper.findAll('[data-testid="repo-row"]');

async function addRepo(url: string, kind?: "anime" | "manga") {
  await wrapper.find('[data-testid="repo-url"]').setValue(url);
  if (kind) await wrapper.find('[data-testid="repo-kind"]').setValue(kind);
  await wrapper.find('[data-testid="add-repo"]').trigger("submit");
  await flushPromises();
}

function mismatch() {
  return new ApiError({
    kind: "graphql",
    code: "REPO_TYPE_MISMATCH",
    message: "249 of 249 extensions in … are not manga extensions",
    details: {
      indexUrl: YUZONO_STORE_URL,
      type: "MANGA",
      packages: ["eu.kanade.tachiyomi.animeextension.en.animegg"],
    },
  });
}

beforeEach(() => {
  pinia = createPinia();
  setActivePinia(pinia);
  queryClient = createQueryClient();
  setBackendReady();
  ops.fetchExtensionStores.mockResolvedValue([keiyoushi, yuzono]);
});

afterEach(() => {
  wrapper.unmount();
  queryClient.clear();
  vi.clearAllMocks();
});

describe("ReposView", () => {
  it("lists the repos by kind, with their URL, count, key trust and last error", async () => {
    await mountRepos();

    const anime = wrapper.find('[data-testid="repo-list-anime"]');
    expect(anime.text()).toContain("Yuzono Anime");
    expect(anime.text()).toContain(YUZONO_STORE_URL);
    expect(anime.text()).toContain("249 extensions");
    expect(anime.find('[data-testid="repo-key"]').text()).toBe("Signing key trusted");

    const manga = wrapper.find('[data-testid="repo-list-manga"]');
    expect(manga.text()).toContain("Keiyoushi");
    expect(manga.find('[data-testid="repo-key"]').text()).toBe("No signing key");
    expect(manga.find('[data-testid="repo-error"]').text()).toContain("HTTP 503");
  });

  it("adds a repo with its type guessed from the URL", async () => {
    ops.fetchExtensionStores.mockResolvedValue([]);
    ops.addRepo.mockResolvedValue(keiyoushi);
    await mountRepos();

    await addRepo(KEIYOUSHI_URL);

    expect(ops.addRepo).toHaveBeenCalledExactlyOnceWith({
      indexUrl: KEIYOUSHI_URL,
      kind: "manga",
      format: undefined,
    });
    const added = wrapper.find('[data-testid="repo-added"]').text();
    expect(added).toContain("Added Keiyoushi as a manga repo (type detected)");
    expect(added).not.toContain("stored as");
    // the list is fetched again
    expect(ops.fetchExtensionStores).toHaveBeenCalledTimes(2);
  });

  it("tries the other type when the server says the guess is wrong, and shows the stored URL", async () => {
    ops.fetchExtensionStores.mockResolvedValue([]);
    ops.addRepo.mockRejectedValueOnce(mismatch()).mockResolvedValueOnce(yuzono);
    await mountRepos();

    await addRepo("https://example.org/repo/index.min.json");

    expect(ops.addRepo.mock.calls.map((c) => c[0].kind)).toEqual(["manga", "anime"]);
    const added = wrapper.find('[data-testid="repo-added"]').text();
    expect(added).toContain("as an anime repo (type detected)");
    expect(added).toContain(`stored as ${YUZONO_STORE_URL}`);
  });

  it("says Updated for a repo that was already there", async () => {
    ops.addRepo.mockResolvedValue(yuzono);
    await mountRepos();

    await addRepo(YUZONO_URL);

    expect(ops.addRepo).toHaveBeenCalledWith(expect.objectContaining({ kind: "anime" }));
    expect(wrapper.find('[data-testid="repo-added"]').text()).toContain("Updated Yuzono Anime");
  });

  it("uses the chosen type as is, and shows a mismatch readably", async () => {
    ops.addRepo.mockRejectedValue(mismatch());
    await mountRepos();

    await addRepo(YUZONO_URL, "manga");

    expect(ops.addRepo).toHaveBeenCalledOnce();
    expect(ops.addRepo).toHaveBeenCalledWith(expect.objectContaining({ kind: "manga" }));
    const error = wrapper.find('[data-testid="add-repo"] [role="alert"]');
    expect(error.text()).toContain("REPO_TYPE_MISMATCH");
    expect(error.text()).toContain("aren't of the kind it was added as");
    expect(error.text()).not.toContain("Try again");
  });

  it("explains a repo that can't be fetched", async () => {
    ops.addRepo.mockRejectedValue(
      new ApiError({ kind: "graphql", code: "REPO_FETCH_FAILED", message: "HTTP 404" }),
    );
    await mountRepos();

    await addRepo("https://example.org/nothing/index.min.json");

    const error = wrapper.find('[data-testid="add-repo"] [role="alert"]').text();
    expect(error).toContain("REPO_FETCH_FAILED");
    expect(error).toContain("Couldn't download the repo's index");
    expect(error).toContain("HTTP 404");
    // one attempt: only a package mismatch is worth trying as the other type
    expect(ops.addRepo).toHaveBeenCalledOnce();
  });

  it("removes a repo only after the confirmation", async () => {
    ops.removeRepo.mockResolvedValue(undefined);
    await mountRepos();
    const dialog = () =>
      document.body.querySelector<HTMLElement>('[data-testid="remove-repo-dialog"]');
    const yuzonoRow = () => rows().find((r) => r.attributes("data-url") === YUZONO_STORE_URL)!;

    await yuzonoRow().find('[data-action="remove-repo"]').trigger("click");
    await flushPromises();
    expect(dialog()!.textContent).toContain("Remove Yuzono Anime?");
    dialog()!.querySelector<HTMLButtonElement>('[data-action="cancel-remove"]')!.click();
    await flushPromises();
    expect(dialog()).toBeNull();
    expect(ops.removeRepo).not.toHaveBeenCalled();

    await yuzonoRow().find('[data-action="remove-repo"]').trigger("click");
    await flushPromises();
    dialog()!.querySelector<HTMLButtonElement>('[data-action="confirm-remove"]')!.click();
    await flushPromises();

    expect(ops.removeRepo).toHaveBeenCalledExactlyOnceWith(YUZONO_STORE_URL);
    expect(dialog()).toBeNull();
  });

  it("refreshes one kind's repos, or all of them", async () => {
    ops.refreshRepos.mockResolvedValue([]);
    await mountRepos();

    await wrapper.find('[data-action="refresh-anime"]').trigger("click");
    await flushPromises();
    await wrapper.find('[data-action="refresh-repos"]').trigger("click");
    await flushPromises();

    expect(ops.refreshRepos.mock.calls).toEqual([["anime"], [undefined]]);
  });
});
