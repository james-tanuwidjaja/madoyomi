import { afterEach, beforeAll, describe, expect, it } from "vitest";
import { flushPromises, mount, type DOMWrapper, type VueWrapper } from "@vue/test-utils";

import { ApiError } from "../../api/client";
import type { Preference, SourcePreferenceChange } from "../../api/types";
import type { PreferenceStatus } from "../../composables/usePreferences";
import { allPreferenceTypes } from "../../test/preferenceFixtures";
import PreferenceRenderer from "../PreferenceRenderer.vue";

/*
 * PreferenceRenderer on the shared fixtures (src/test/preferenceFixtures.ts): each kind's row and control, the one
 * change each control sends (by its position in the full list), and the status lines. Reka's Select and Dialog portal
 * to <body>, so the wrapper is attached there.
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

function mountRenderer(
  preferences: Preference[] = allPreferenceTypes,
  status: Record<number, PreferenceStatus> = {},
) {
  const changes: SourcePreferenceChange[] = [];
  wrapper = mount(PreferenceRenderer, {
    props: { preferences, status, owner: "AnimePahe", onChange: (c) => changes.push(c) },
    attachTo: document.body,
  });
  return changes;
}

const at = (pos: number) => wrapper.find(`[data-position="${pos}"]`);
const dialog = () => document.body.querySelector<HTMLElement>("[data-testid='preference-dialog']");
const dialogAction = (name: string) =>
  document.body.querySelector<HTMLElement>(
    `[data-testid='preference-dialog'] [data-action='${name}']`,
  )!;

async function pickOption(field: DOMWrapper<Element>, label: string) {
  await field.find("button[role='combobox']").trigger("keydown", { key: "Enter" });
  await flushPromises();
  const option = [...document.body.querySelectorAll<HTMLElement>("[role='option']")].find((o) =>
    o.textContent?.trim().startsWith(label),
  );
  expect(option, `option ${label}`).toBeTruthy();
  option!.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", bubbles: true }));
  await flushPromises();
}

describe("PreferenceRenderer", () => {
  it("renders every kind with its title and summary, and leaves out a hidden one", () => {
    mountRenderer();

    const kinds = wrapper.findAll("[data-preference]").map((e) => e.attributes("data-preference"));
    expect(kinds).toEqual([
      "ListPreference",
      "SwitchPreference",
      "CheckBoxPreference",
      "EditTextPreference",
      "MultiSelectListPreference",
      "SwitchPreference",
    ]);
    // the hidden list keeps its position: nothing renders at 6
    expect(at(6).exists()).toBe(false);

    expect(at(0).text()).toContain("Preferred quality");
    expect(at(0).find("[data-testid='preference-summary']").text()).toBe("1080p"); // "%s"
    expect(at(0).find("button[role='combobox']").text()).toContain("1080p");
    expect(at(1).find("[role='switch']").attributes("aria-checked")).toBe("false");
    expect(at(1).text()).toContain("Instead of counting from 1");
    expect(at(2).find("[role='checkbox']").attributes("aria-checked")).toBe("true");
    expect(at(3).find("[data-testid='preference-summary']").text()).toBe("Mozilla/5.0");
    expect(at(4).find("[data-action='edit']").text()).toBe("2 selected");
  });

  it("disables a preference the extension disables", async () => {
    const changes = mountRenderer();
    const sw = at(5).find("[role='switch']");

    expect(at(5).classes()).toContain("disabled");
    expect(sw.attributes("disabled")).toBeDefined();
    await sw.trigger("click");
    expect(changes).toEqual([]);
  });

  it("sends a switch, a checkbox and a list change at once, by position", async () => {
    const changes = mountRenderer();

    await at(1).find("[role='switch']").trigger("click");
    await at(2).find("[role='checkbox']").trigger("click");
    await pickOption(at(0), "720p");
    // the entry already chosen sends nothing
    await pickOption(at(0), "1080p");

    expect(changes).toEqual([
      { position: 1, switchState: true },
      { position: 2, checkBoxState: false },
      { position: 0, listState: "720" },
    ]);
  });

  it("edits a text in a dialog with its title and message, saved by Enter or Save", async () => {
    const changes = mountRenderer();

    await at(3).find("[data-action='edit']").trigger("click");
    await flushPromises();
    expect(dialog()?.textContent).toContain("Custom user agent");
    expect(dialog()?.textContent).toContain("Leave empty for the default.");
    const input = dialog()!.querySelector<HTMLInputElement>("[data-testid='preference-text']")!;
    expect(input.value).toBe("Mozilla/5.0");

    input.value = "Edge/1";
    input.dispatchEvent(new Event("input"));
    input.form!.dispatchEvent(new Event("submit", { cancelable: true }));
    await flushPromises();
    expect(dialog()).toBeNull();
    expect(changes).toEqual([{ position: 3, editTextState: "Edge/1" }]);

    // Cancel and an unchanged Save send nothing
    await at(3).find("[data-action='edit']").trigger("click");
    await flushPromises();
    const again = dialog()!.querySelector<HTMLInputElement>("[data-testid='preference-text']")!;
    again.value = "thrown away";
    again.dispatchEvent(new Event("input"));
    dialogAction("cancel").click();
    await flushPromises();
    await at(3).find("[data-action='edit']").trigger("click");
    await flushPromises();
    dialogAction("save").click();
    await flushPromises();
    expect(changes).toHaveLength(1);
  });

  it("picks a multi-select's values in a dialog and sends the whole set on Save", async () => {
    const changes = mountRenderer();

    await at(4).find("[data-action='edit']").trigger("click");
    await flushPromises();
    expect(dialog()?.textContent).toContain("Default content rating");
    const boxes = [...dialog()!.querySelectorAll<HTMLElement>("[role='checkbox']")];
    expect(boxes.map((b) => b.getAttribute("aria-checked"))).toEqual(["true", "true", "false"]);

    boxes[2].click(); // + Erotica
    await flushPromises();
    boxes[0].click(); // - Safe
    await flushPromises();
    dialogAction("save").click();
    await flushPromises();

    // in the entries' order
    expect(changes).toEqual([{ position: 4, multiSelectState: ["suggestive", "erotica"] }]);
  });

  it("shows saving, a refusal with the kept value, and a readable error", () => {
    mountRenderer(allPreferenceTypes, {
      0: { state: "refused" },
      1: { state: "saving" },
      2: {
        state: "error",
        error: new ApiError({
          kind: "graphql",
          code: "BAD_REQUEST",
          message: "Preference change at 2: …",
          details: null,
        }),
      },
    });

    expect(at(0).find("[data-testid='preference-refused']").text()).toBe(
      "AnimePahe didn't accept that value and kept 1080p.",
    );
    expect(at(1).find("[data-testid='preference-saving']").exists()).toBe(true);
    const error = at(2).find("[role='alert']");
    expect(error.text()).toContain("Couldn't save this setting");
    expect(error.text()).toContain("The backend refused the request.");
    expect(error.text()).toContain("BAD_REQUEST");
    expect(error.find("button").exists()).toBe(false);
  });
});
