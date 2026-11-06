import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { createPinia, setActivePinia, type Pinia } from "pinia";
import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import { createMemoryHistory, type Router } from "vue-router";
import { VueQueryPlugin } from "@tanstack/vue-query";

import App from "../App.vue";
import { createAppRouter } from "../router";
import { createQueryClient } from "../api/realtime";
import { useMediaKindStore } from "../stores/mediaKind";

/*
 * Route-level smoke test (Phase 3 step 22): the whole app in browser mode (no backend), on a memory history. Every
 * route renders inside the shell, with its sidebar item marked.
 */

let pinia: Pinia;
let router: Router;
let wrapper: VueWrapper;

async function mountAt(path: string) {
  router = createAppRouter(createMemoryHistory());
  wrapper = mount(App, {
    global: { plugins: [pinia, router, [VueQueryPlugin, { queryClient: createQueryClient() }]] },
    attachTo: document.body,
  });
  await router.push(path);
  await flushPromises();
}

async function go(path: string) {
  await router.push(path);
  await flushPromises();
}

const heading = () => wrapper.find("main h1").text();
const activeNav = () => wrapper.find(".nav-item.active").attributes("data-nav");
const kindOption = (label: string) =>
  wrapper.findAll(".kind-option").find((b) => b.text() === label)!;

beforeEach(() => {
  localStorage.clear();
  pinia = createPinia();
  setActivePinia(pinia);
});

afterEach(() => {
  wrapper.unmount();
  localStorage.clear();
});

describe("routes", () => {
  it("redirects / to the library", async () => {
    await mountAt("/");

    expect(router.currentRoute.value.path).toBe("/library");
    expect(heading()).toBe("Library");
    expect(wrapper.find(".pane").exists()).toBe(true);
    expect(wrapper.find(".browser-banner").exists()).toBe(true);
  });

  it.each([
    ["/library", "Library", "library"],
    ["/browse", "Browse", "browse"],
    ["/browse/2499283573021220255", "Source", "browse"],
    ["/anime/12", "Anime", "library"],
    ["/manga/34", "Manga", "library"],
    ["/extensions", "Extensions", "extensions"],
    ["/repos", "Repos", "extensions"],
    ["/downloads", "Downloads", "downloads"],
    ["/settings", "Settings", "settings"],
    ["/settings/source/2499283573021220255", "Source settings", "settings"],
  ])("renders %s in the shell", async (path, title, nav) => {
    await mountAt(path);

    expect(heading()).toBe(title);
    expect(activeNav()).toBe(nav);
    expect(document.title).toBe(`${title} · Madoyomi`);
  });

  it("shows a not-found page for an unknown path", async () => {
    await mountAt("/nowhere");

    expect(heading()).toBe("Not found");
  });

  it("navigates from the sidebar", async () => {
    await mountAt("/library");

    await wrapper.find('[data-nav="extensions"]').trigger("click");
    await flushPromises();

    expect(router.currentRoute.value.path).toBe("/extensions");
    expect(heading()).toBe("Extensions");
  });

  it("switches between anime and manga", async () => {
    await mountAt("/browse");
    const kind = useMediaKindStore();
    expect(kind.kind).toBe("anime");
    expect(wrapper.find("main").text()).toContain("Your anime sources");

    await kindOption("Manga").trigger("click");
    await flushPromises();

    expect(kind.kind).toBe("manga");
    expect(kindOption("Manga").attributes("data-state")).toBe("on");
    expect(wrapper.find("main").text()).toContain("Your manga sources");
  });

  it("sets the kind from a details route, and leaves the route when the kind changes", async () => {
    await mountAt("/manga/34");
    const kind = useMediaKindStore();
    expect(kind.kind).toBe("manga");

    await go("/anime/12");
    expect(kind.kind).toBe("anime");

    await kindOption("Manga").trigger("click");
    await flushPromises();
    expect(router.currentRoute.value.path).toBe("/library");
  });
});
