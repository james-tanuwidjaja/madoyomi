import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { h, type Component } from "vue";
import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";

import VirtualGrid from "../VirtualGrid.vue";

/*
 * jsdom has no layout: the grid reads its scroll area's clientWidth (the columns) and clientHeight (the viewport),
 * the virtualizer each row's offsetHeight, and the end check scrollHeight. All are stubbed: an 800 x 600 area, cards
 * at least 150 px wide with 12 px gaps (5 columns), 300 px rows.
 */
const WIDTH = 800;
const VIEWPORT = 600;
const ROW = 300;

const items = (n: number) => Array.from({ length: n }, (_, i) => ({ id: i, title: `Entry ${i}` }));

let wrapper: VueWrapper;
let scrollHeight = 0;
const restore: (() => void)[] = [];

function stub(
  prop: "clientHeight" | "clientWidth" | "offsetHeight" | "scrollHeight",
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
    if (original) Object.defineProperty(HTMLElement.prototype, prop, original);
    else delete (HTMLElement.prototype as unknown as Record<string, unknown>)[prop];
  });
}

function mountGrid(n: number, props: Record<string, unknown> = {}) {
  wrapper = mount(VirtualGrid as unknown as Component, {
    props: { items: items(n), itemKey: (i: { id: number }) => i.id, overscan: 1, ...props },
    slots: {
      default: ({ item }: { item: { title: string } }) => h("span", { class: "title" }, item.title),
      footer: () => h("p", { class: "end" }, "footer"),
    },
    attachTo: document.body,
  });
}

const rendered = () => wrapper.findAll(".title").map((n) => n.text());
const grid = () => wrapper.find(".virtual-grid");

beforeEach(() => {
  scrollHeight = 0;
  stub("clientWidth", (el) => (el.classList.contains("virtual-grid") ? WIDTH : 0));
  stub("clientHeight", (el) => (el.classList.contains("virtual-grid") ? VIEWPORT : 0));
  stub("offsetHeight", (el) => (el.classList.contains("row") ? ROW : 0));
  stub("scrollHeight", (el) => (el.classList.contains("virtual-grid") ? scrollHeight : 0));
});

afterEach(() => {
  wrapper.unmount();
  restore.splice(0).forEach((r) => r());
});

describe("VirtualGrid", () => {
  it("fits as many columns as the width allows", async () => {
    mountGrid(10);
    await flushPromises();
    // (800 + 12) / (150 + 12) = 5.01
    expect(grid().attributes("data-columns")).toBe("5");
    expect(grid().find(".grid").attributes("style")).toContain("repeat(5, minmax(0, 1fr))");

    await wrapper.setProps({ minItemWidth: 250 });
    expect(grid().attributes("data-columns")).toBe("3");
  });

  it("renders every card of a short list, and its footer", async () => {
    mountGrid(40);
    await flushPromises();

    expect(grid().attributes("data-virtual")).toBe("off");
    expect(rendered()).toHaveLength(40);
    expect(wrapper.find(".end").exists()).toBe(true);
  });

  it("renders only the rows in view of a long list, and the rest as it scrolls", async () => {
    // an estimate that matches the stubbed rows (plus the 12 px gap), so positions are exact
    mountGrid(1000, { estimateItemHeight: () => ROW - 12 });
    await flushPromises();

    expect(grid().attributes("data-virtual")).toBe("on");
    // 200 rows of 5; 600 / 300 = 2 rows in view, plus 1 overscan
    const first = rendered();
    expect(first[0]).toBe("Entry 0");
    expect(first.length).toBeGreaterThan(0);
    expect(first.length).toBeLessThanOrEqual(4 * 5);
    expect(wrapper.find(".sizer").attributes("style")).toContain(`height: ${200 * ROW}px`);

    const el = grid().element as HTMLElement;
    el.scrollTop = 100 * ROW;
    el.dispatchEvent(new Event("scroll"));
    await flushPromises();

    const later = rendered();
    expect(later).toContain("Entry 500");
    expect(later).not.toContain("Entry 0");
    // whole rows: a multiple of the columns
    expect(later.length % 5).toBe(0);
  });

  it("emits end-reached near the end, and when the items don't fill the view", async () => {
    scrollHeight = 5000;
    mountGrid(100);
    await flushPromises();
    expect(wrapper.emitted("end-reached")).toBeUndefined();

    const el = grid().element as HTMLElement;
    el.scrollTop = 3000; // 5000 - 3000 - 600 = 1400 px left
    el.dispatchEvent(new Event("scroll"));
    await flushPromises();
    expect(wrapper.emitted("end-reached")).toBeUndefined();

    el.scrollTop = 4000; // 400 px left, within the 600 px default
    el.dispatchEvent(new Event("scroll"));
    await flushPromises();
    expect(wrapper.emitted("end-reached")).toHaveLength(1);

    // a page that doesn't fill the view asks for more as soon as it arrives
    scrollHeight = 500;
    el.scrollTop = 0;
    await wrapper.setProps({ items: items(10) });
    await flushPromises();
    expect(wrapper.emitted("end-reached")).toHaveLength(2);
  });

  it("never emits end-reached while it has no height", async () => {
    restore.splice(0).forEach((r) => r());
    stub("clientWidth", () => 0);
    stub("clientHeight", () => 0);
    mountGrid(3);
    await flushPromises();
    expect(wrapper.emitted("end-reached")).toBeUndefined();
  });
});
