import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import { mount, type VueWrapper } from "@vue/test-utils";

import { READER_PREFS_KEY, useReaderStore } from "../../../stores/reader";
import { DEFAULT_READER_PREFS } from "../../../utils/readerSettings";
import ReaderSettings from "../ReaderSettings.vue";

/* The reader's settings sheet (Phase 5 Stage 5): the manga's own mode and direction with where the mode comes from,
   "Use default" and the reset, and the global settings on this device. */

let wrapper: VueWrapper;

const props = {
  mangaTitle: "Osora",
  own: { mode: null, direction: null },
  mode: "webtoon" as const,
  origin: { kind: "source" as const, source: "Webtoons.com" },
  direction: "rtl" as const,
  fallbackMode: "webtoon" as const,
  fallbackDirection: "rtl" as const,
  error: null,
};

function mountSheet(over: Record<string, unknown> = {}) {
  wrapper = mount(ReaderSettings, { props: { ...props, ...over }, attachTo: document.body });
}

beforeEach(() => {
  localStorage.clear();
  setActivePinia(createPinia());
});

afterEach(() => wrapper?.unmount());

describe("ReaderSettings", () => {
  it("shows the manga's mode, where it comes from, and what Use default gives", () => {
    mountSheet();
    expect(wrapper.find("h2").text()).toBe("Reader settings");
    expect(wrapper.text()).toContain("Osora");
    expect(wrapper.find('[data-testid="mode-origin"]').text()).toBe(
      "Webtoon · From the source (Webtoons.com)",
    );
    const mode = wrapper.find<HTMLSelectElement>('[data-manga-pref="mode"]');
    expect(mode.element.value).toBe("");
    expect(mode.findAll("option").map((o) => o.text())).toEqual([
      "Default (Webtoon)",
      "Single",
      "Double",
      "Webtoon",
    ]);
    expect(wrapper.find('[data-action="reset-manga-prefs"]').attributes("disabled")).toBeDefined();
    // the close button has the focus, so Esc and Tab work from the start
    expect(document.activeElement).toBe(wrapper.find('[data-action="close-settings"]').element);
  });

  it("emits the manga's choices, null for Use default, the reset and close", async () => {
    mountSheet({
      own: { mode: "paged", direction: "ltr" },
      mode: "paged",
      origin: { kind: "manga" },
    });
    expect(wrapper.find<HTMLSelectElement>('[data-manga-pref="mode"]').element.value).toBe("paged");
    expect(wrapper.find('[data-testid="mode-origin"]').text()).toBe("Single · This manga's");

    await wrapper.find('[data-manga-pref="mode"]').setValue("double");
    await wrapper.find('[data-manga-pref="mode"]').setValue("");
    await wrapper.find('[data-manga-pref="direction"]').setValue("rtl");
    await wrapper.find('[data-action="reset-manga-prefs"]').trigger("click");
    await wrapper.find('[data-action="close-settings"]').trigger("click");
    expect(wrapper.emitted("setMode")).toEqual([["double"], [null]]);
    expect(wrapper.emitted("setDirection")).toEqual([["rtl"]]);
    expect(wrapper.emitted("resetManga")).toHaveLength(1);
    expect(wrapper.emitted("close")).toHaveLength(1);
  });

  it("changes the global settings on this device, and resets them", async () => {
    mountSheet();
    const r = useReaderStore();
    await wrapper.find('[data-pref="mode"]').setValue("double");
    await wrapper.find('[data-pref="direction"]').setValue("ltr");
    await wrapper.find('[data-pref="fit"]').setValue("height");
    await wrapper.find('[data-pref="stripWidth"]').setValue("1200");
    await wrapper.find('[data-pref="pageGap"]').setValue(true);
    await wrapper.find('[data-pref="background"]').setValue("gray");
    await wrapper.find('[data-pref="preload"]').setValue("12");
    expect({ ...r.prefs }).toEqual({
      mode: "double",
      direction: "ltr",
      fit: "height",
      stripWidth: 1200,
      pageGap: true,
      background: "gray",
      preload: 12,
    });
    expect(JSON.parse(localStorage.getItem(READER_PREFS_KEY)!)).toMatchObject({ preload: 12 });

    await wrapper.find('[data-action="reset-reader-prefs"]').trigger("click");
    expect({ ...r.prefs }).toEqual(DEFAULT_READER_PREFS);
    expect(wrapper.find<HTMLSelectElement>('[data-pref="preload"]').element.value).toBe("4");
  });

  it("shows a manga setting that couldn't be saved", () => {
    mountSheet({ error: new Error("down") });
    expect(wrapper.find('[data-testid="manga-prefs-error"]').exists()).toBe(true);
  });
});
