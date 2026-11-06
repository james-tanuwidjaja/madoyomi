import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { h, type Component } from "vue";
import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";

import VirtualList from "../VirtualList.vue";

/*
 * jsdom has no layout: the virtualizer reads the scroll area's clientHeight and each row's offsetHeight, so both are
 * stubbed (a 400 px viewport, 50 px rows).
 */
const VIEWPORT = 400;
const ROW = 50;

const items = (n: number) =>
  Array.from({ length: n }, (_, i) => ({ id: `pkg${i}`, name: `Ext ${i}` }));

let wrapper: VueWrapper;
const restore: (() => void)[] = [];

function stub(
  prop: "clientHeight" | "clientWidth" | "offsetHeight",
  get: (el: HTMLElement) => number,
) {
  const original = Object.getOwnPropertyDescriptor(HTMLElement.prototype, prop);
  Object.defineProperty(HTMLElement.prototype, prop, {
    configurable: true,
    get() {
      return get(this as HTMLElement);
    },
  });
  restore.push(() => {
    // jsdom defines these on Element.prototype, so usually there is nothing of our own to put back
    if (original) Object.defineProperty(HTMLElement.prototype, prop, original);
    else delete (HTMLElement.prototype as unknown as Record<string, unknown>)[prop];
  });
}

function mountList(n: number) {
  const list = items(n);
  // a generic SFC: mounted through the plain Component type, its props and slot given as for any component
  wrapper = mount(VirtualList as unknown as Component, {
    props: { items: list, itemKey: (i: { id: string }) => i.id, estimateSize: ROW, overscan: 2 },
    slots: {
      default: ({ item }: { item: { name: string } }) => h("span", { class: "name" }, item.name),
    },
    attachTo: document.body,
  });
}

const rendered = () => wrapper.findAll(".name").map((n) => n.text());

beforeEach(() => {
  stub("clientHeight", (el) => (el.classList.contains("virtual-list") ? VIEWPORT : 0));
  stub("clientWidth", (el) => (el.classList.contains("virtual-list") ? 800 : 0));
  stub("offsetHeight", (el) => (el.classList.contains("item") ? ROW : 0));
});

afterEach(() => {
  wrapper.unmount();
  restore.splice(0).forEach((r) => r());
});

describe("VirtualList", () => {
  it("renders every row of a short list", async () => {
    mountList(30);
    await flushPromises();

    expect(wrapper.find(".virtual-list").attributes("data-virtual")).toBe("off");
    expect(rendered()).toHaveLength(30);
  });

  it("renders only the rows in view of a long list, and the rest as it scrolls", async () => {
    mountList(1469);
    await flushPromises();

    const scroller = wrapper.find(".virtual-list");
    expect(scroller.attributes("data-virtual")).toBe("on");
    const first = rendered();
    // 400 / 50 = 8 rows in view, plus 2 overscan below
    expect(first.length).toBeGreaterThan(0);
    expect(first.length).toBeLessThanOrEqual(12);
    expect(first[0]).toBe("Ext 0");
    expect(wrapper.find(".sizer").attributes("style")).toContain(`height: ${1469 * ROW}px`);

    const el = scroller.element as HTMLElement;
    el.scrollTop = 1000 * ROW;
    el.dispatchEvent(new Event("scroll"));
    await flushPromises();

    const later = rendered();
    expect(later).toContain("Ext 1000");
    expect(later).not.toContain("Ext 0");
    expect(later.length).toBeLessThanOrEqual(14);
  });
});
