import { afterEach, beforeAll, describe, expect, it } from "vitest";
import { ref, type Ref } from "vue";
import { flushPromises, mount, type DOMWrapper, type VueWrapper } from "@vue/test-utils";

import type { Filter } from "../../api/types";
import {
  allFilterTypes,
  serverFixtureChanges,
  serverFixtureFilters,
} from "../../test/filterFixtures";
import { defaultFilterState, filterChanges, type FilterState } from "../../utils/filters";
import FilterRenderer from "../FilterRenderer.vue";
import FilterSheet from "../FilterSheet.vue";

/*
 * FilterRenderer and FilterSheet on the shared fixtures (src/test/filterFixtures.ts): each kind's control, the state
 * it emits, and the change list that state sends. Reka's Select and Dialog portal to <body>, so the wrappers are
 * attached there and the portalled parts are found through document.body.
 */

beforeAll(() => {
  // jsdom lacks these; Reka's Select calls them
  Element.prototype.scrollIntoView ??= () => {};
  Element.prototype.hasPointerCapture ??= () => false;
  Element.prototype.releasePointerCapture ??= () => {};
});

let wrapper: VueWrapper;
afterEach(() => {
  wrapper?.unmount();
  document.body.innerHTML = "";
});

/** FilterRenderer with its state in a ref, as a parent with v-model has it. */
function mountRenderer(filters: Filter[], initial?: FilterState) {
  const state: Ref<FilterState> = ref(initial ?? defaultFilterState(filters));
  // v-model as a parent has it: the emitted state goes back in as the prop
  wrapper = mount(FilterRenderer, {
    props: {
      filters,
      modelValue: state.value,
      "onUpdate:modelValue": (s: FilterState) => {
        state.value = s;
        return wrapper.setProps({ modelValue: s });
      },
    },
    attachTo: document.body,
  });
  return { state, changes: () => filterChanges(filters, state.value) };
}

const at = (pos: string) => wrapper.find(`[data-position="${pos}"]`);
const bodyFind = (selector: string) => document.body.querySelector<HTMLElement>(selector);

async function pickOption(field: DOMWrapper<Element>, label: string) {
  const trigger = field.find("button[role='combobox']");
  await trigger.trigger("keydown", { key: "Enter" });
  await flushPromises();
  const option = [...document.body.querySelectorAll<HTMLElement>("[role='option']")].find((o) =>
    o.textContent?.trim().startsWith(label),
  );
  expect(option, `option ${label}`).toBeTruthy();
  option!.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", bubbles: true }));
  await flushPromises();
}

describe("FilterRenderer", () => {
  it("renders every kind of filter", () => {
    mountRenderer(allFilterTypes);

    const kinds = wrapper.findAll("[data-filter]").map((e) => e.attributes("data-filter"));
    expect(kinds).toEqual([
      "header",
      "select",
      "text",
      "separator",
      "checkbox",
      "tristate",
      "sort",
      "sort",
      "group",
    ]);
    expect(at("0").text()).toBe("Search options");
    expect(at("1").find("button[role='combobox']").text()).toContain("TV");
    expect((at("2").find("input").element as HTMLInputElement).value).toBe("2024");
    expect(at("4").find("[role='checkbox']").attributes("aria-checked")).toBe("true");
    expect(at("5").find("[data-filter='tristate']").attributes("data-state")).toBe("INCLUDE");
    expect(at("6").find("button[role='combobox']").text()).toContain("None");
    expect(at("7").find("button[role='combobox']").text()).toContain("Popularity");
    expect(at("7").find("[data-action='sort-direction']").text()).toContain("Descending");
  });

  it("emits the state, which sends the server test's change list", async () => {
    const { changes } = mountRenderer(serverFixtureFilters);

    await pickOption(at("1"), "1080p");
    await at("2").find("input").setValue("MAPPA");
    await at("3").find("[role='checkbox']").trigger("click");
    // the group's members only render when it's open
    await at("4").find("button").trigger("click");
    await flushPromises();
    const drama = at("4.1").find("[role='checkbox']");
    await drama.trigger("click"); // IGNORE → INCLUDE
    await drama.trigger("click"); // INCLUDE → EXCLUDE

    expect(changes()).toEqual(serverFixtureChanges);
    expect(at("4").find(".badge").text()).toBe("1");
  });

  it("cycles a tri-state through include, exclude and ignore", async () => {
    const { state } = mountRenderer(serverFixtureFilters);
    await at("4").find("button").trigger("click");
    await flushPromises();
    const action = () => at("4.0");

    const seen: string[] = [];
    for (let i = 0; i < 3; i++) {
      await action().find("[role='checkbox']").trigger("click");
      seen.push((state.value[4] as string[])[0]);
      seen.push(action().find("[role='checkbox']").attributes("aria-label")!);
    }
    expect(seen).toEqual([
      "INCLUDE",
      "Action: included",
      "EXCLUDE",
      "Action: excluded",
      "IGNORE",
      "Action: ignored",
    ]);
    expect(action().find("[role='checkbox']").attributes("aria-checked")).toBe("false");
  });

  it("picks a sort field (descending first) and flips its direction", async () => {
    const { state, changes } = mountRenderer(allFilterTypes);
    const direction = () => at("6").find("[data-action='sort-direction']");
    expect(direction().attributes("disabled")).toBeDefined();

    await pickOption(at("6"), "Score");
    expect(state.value[6]).toEqual({ index: 1, ascending: false });
    await direction().trigger("click");
    expect(state.value[6]).toEqual({ index: 1, ascending: true });
    expect(direction().text()).toContain("Ascending");

    // the source's default has a field: "None" isn't offered there
    await at("7").find("button[role='combobox']").trigger("keydown", { key: "Enter" });
    await flushPromises();
    const options = [...document.body.querySelectorAll("[role='option']")].map((o) =>
      o.textContent?.trim(),
    );
    expect(options.some((o) => o?.startsWith("None"))).toBe(false);
    expect(options).toHaveLength(2);
    bodyFind("[role='option']")!.dispatchEvent(
      new KeyboardEvent("keydown", { key: "Escape", bubbles: true }),
    );
    await flushPromises();

    expect(changes()).toEqual([{ position: 6, sortState: { index: 1, ascending: true } }]);
  });

  it("opens and closes a group", async () => {
    mountRenderer(allFilterTypes);
    const trigger = at("8").find("button");

    expect(at("8.0").exists()).toBe(false);
    await trigger.trigger("click");
    await flushPromises();
    expect(trigger.attributes("aria-expanded")).toBe("true");
    expect(wrapper.findAll("[data-position^='8.']")).toHaveLength(3);
    await trigger.trigger("click");
    await flushPromises();
    expect(trigger.attributes("aria-expanded")).toBe("false");
  });
});

describe("FilterSheet", () => {
  function mountSheet(applied: FilterState | null) {
    const open = ref(true);
    const appliedEvents: FilterState[] = [];
    wrapper = mount(FilterSheet, {
      props: {
        filters: serverFixtureFilters,
        applied,
        open: open.value,
        "onUpdate:open": (v: boolean) => {
          open.value = v;
          return wrapper.setProps({ open: v });
        },
        onApply: (s: FilterState) => appliedEvents.push(s),
      },
      attachTo: document.body,
    });
    return { open, appliedEvents };
  }

  const sheet = () => bodyFind("[data-testid='filter-sheet']");
  const action = (name: string) =>
    bodyFind(`[data-testid='filter-sheet'] [data-action='${name}']`)!;

  it("applies the edited copy and closes", async () => {
    const { open, appliedEvents } = mountSheet(null);
    await flushPromises();
    expect(sheet()?.textContent).toContain("The source's defaults.");

    const dubbed = sheet()!.querySelector<HTMLElement>("[data-position='3'] [role='checkbox']")!;
    dubbed.click();
    await flushPromises();
    expect(sheet()?.textContent).toContain("1 filter changed");

    action("apply").click();
    await flushPromises();
    expect(open.value).toBe(false);
    expect(appliedEvents).toHaveLength(1);
    expect(filterChanges(serverFixtureFilters, appliedEvents[0])).toEqual([
      { position: 3, checkBoxState: true },
    ]);
  });

  it("starts from the applied values, and Reset brings back the defaults", async () => {
    const applied = defaultFilterState(serverFixtureFilters);
    applied[3] = true;
    applied[2] = "MAPPA";
    const { appliedEvents } = mountSheet(applied);
    await flushPromises();

    expect(sheet()?.textContent).toContain("2 filters changed");
    expect(sheet()!.querySelector<HTMLInputElement>("[data-position='2'] input")!.value).toBe(
      "MAPPA",
    );

    action("reset").click();
    await flushPromises();
    expect(sheet()?.textContent).toContain("The source's defaults.");
    expect((action("reset") as HTMLButtonElement).disabled).toBe(true);

    action("apply").click();
    await flushPromises();
    expect(filterChanges(serverFixtureFilters, appliedEvents[0])).toEqual([]);
    // the applied values themselves weren't touched
    expect(applied[3]).toBe(true);
  });

  it("throws the draft away on Cancel", async () => {
    const { open, appliedEvents } = mountSheet(null);
    await flushPromises();
    sheet()!.querySelector<HTMLElement>("[data-position='3'] [role='checkbox']")!.click();
    await flushPromises();

    action("cancel").click();
    await flushPromises();
    expect(open.value).toBe(false);
    expect(appliedEvents).toEqual([]);

    await wrapper.setProps({ open: true });
    await flushPromises();
    expect(sheet()?.textContent).toContain("The source's defaults.");
  });
});
