import { describe, expect, it } from "vitest";
import {
  clampZoom,
  FIT,
  isZoomed,
  MAX_ZOOM,
  panBy,
  panKeyDirection,
  panStep,
  toggleZoomAt,
  wheelZoomFactor,
  zoomAt,
  zoomKeyAction,
  type Rect,
} from "../zoom";

/*
 * Zoom and pan (Phase 5 Stage 4): a 1000 × 800 box with a portrait page fitted in its middle (400 px wide, full
 * height), and a spread that fills the box's width.
 */
const view = { width: 1000, height: 800 };
const page: Rect = { left: 300, top: 0, width: 400, height: 800 };
const full: Rect = { left: 0, top: 0, width: 1000, height: 800 };

/** Where a point of the content (at scale 1) is on screen. */
const onScreen = (z: { scale: number; x: number; y: number }, p: { x: number; y: number }) => ({
  x: z.x + p.x * z.scale,
  y: z.y + p.y * z.scale,
});

describe("zoomAt", () => {
  it("keeps the point under the pointer still", () => {
    const point = { x: 600, y: 200 };
    const z = zoomAt(FIT, 2, point, view, full);
    expect(z.scale).toBe(2);
    expect(onScreen(z, point)).toEqual(point);
  });

  it("stays between fit and the maximum", () => {
    expect(zoomAt(FIT, 10, { x: 500, y: 400 }, view, full).scale).toBe(MAX_ZOOM);
    expect(zoomAt({ scale: 2, x: -500, y: -400 }, 0.5, { x: 500, y: 400 }, view, full)).toEqual(
      FIT,
    );
  });

  it("never shows past the content's edges", () => {
    // zoomed about the box's corner: the content's corner stays at the box's corner
    const z = zoomAt(FIT, 2, { x: 0, y: 0 }, view, full);
    expect(z).toEqual({ scale: 2, x: 0, y: 0 });
    const far = zoomAt(FIT, 2, { x: 1000, y: 800 }, view, full);
    expect(onScreen(far, { x: 1000, y: 800 })).toEqual({ x: 1000, y: 800 });
  });

  it("centres a page that is still narrower than the box", () => {
    // a 400 px page at 2× is 800 px wide: centred in the 1000 px box whatever the pointer
    const z = zoomAt(FIT, 2, { x: 350, y: 400 }, view, page);
    expect(onScreen(z, { x: 300, y: 0 }).x).toBe(100);
    expect(onScreen(z, { x: 700, y: 0 }).x).toBe(900);
    // the height is wider than the box: the point's row stays put
    expect(onScreen(z, { x: 0, y: 400 }).y).toBe(400);
  });
});

describe("panBy", () => {
  it("moves with a drag, inside the content", () => {
    const z = zoomAt(FIT, 2, { x: 500, y: 400 }, view, full); // x -500, y -400
    expect(panBy(z, 100, 50, view, full)).toEqual({ scale: 2, x: -400, y: -350 });
    expect(panBy(z, 10_000, 10_000, view, full)).toEqual({ scale: 2, x: 0, y: 0 });
    expect(panBy(z, -10_000, -10_000, view, full)).toEqual({ scale: 2, x: -1000, y: -800 });
  });

  it("does nothing at fit", () => {
    expect(panBy(FIT, 100, 100, view, full)).toBe(FIT);
  });

  it("pans a portrait page only as far as its own edges", () => {
    const z = zoomAt(FIT, 3, { x: 500, y: 400 }, view, page); // 1200 px wide
    const left = panBy(z, 10_000, 0, view, page);
    expect(onScreen(left, { x: 300, y: 0 }).x).toBe(0);
    const right = panBy(z, -10_000, 0, view, page);
    expect(onScreen(right, { x: 700, y: 0 }).x).toBe(1000);
  });
});

describe("clampZoom and isZoomed", () => {
  it("is fit at scale 1, whatever the translation", () => {
    expect(clampZoom({ scale: 1, x: -50, y: 30 }, view, full)).toEqual(FIT);
    expect(isZoomed(FIT)).toBe(false);
    expect(isZoomed({ scale: 1.5, x: 0, y: 0 })).toBe(true);
  });
});

describe("toggleZoomAt (a double-click)", () => {
  it("zooms 2× about the point at fit, and back to fit when zoomed", () => {
    const z = toggleZoomAt(FIT, { x: 400, y: 300 }, view, full);
    expect(z.scale).toBe(2);
    expect(onScreen(z, { x: 400, y: 300 })).toEqual({ x: 400, y: 300 });
    expect(toggleZoomAt(z, { x: 10, y: 10 }, view, full)).toEqual(FIT);
  });
});

describe("wheelZoomFactor", () => {
  it("zooms in on a wheel up and out on a wheel down, about 1.2× a notch", () => {
    expect(wheelZoomFactor(-100)).toBeGreaterThan(1.2);
    expect(wheelZoomFactor(-100)).toBeLessThan(1.25);
    expect(wheelZoomFactor(100)).toBeCloseTo(1 / wheelZoomFactor(-100));
    expect(wheelZoomFactor(0)).toBe(1);
    // a huge delta is capped
    expect(wheelZoomFactor(-10_000)).toBe(wheelZoomFactor(-300));
  });
});

describe("keys", () => {
  it("zooms with Ctrl+=, Ctrl+− and Ctrl+0 only", () => {
    expect(zoomKeyAction({ key: "=", ctrlKey: true })).toBe("zoomIn");
    expect(zoomKeyAction({ key: "+", ctrlKey: true, shiftKey: true })).toBe("zoomIn");
    expect(zoomKeyAction({ key: "-", ctrlKey: true })).toBe("zoomOut");
    expect(zoomKeyAction({ key: "0", ctrlKey: true })).toBe("zoomReset");
    expect(zoomKeyAction({ key: "0" })).toBeNull();
    expect(zoomKeyAction({ key: "=", ctrlKey: true, altKey: true })).toBeNull();
  });

  it("pans with the arrows, without modifiers", () => {
    expect(panKeyDirection({ key: "ArrowDown" })).toEqual({ x: 0, y: 1 });
    expect(panKeyDirection({ key: "ArrowUp" })).toEqual({ x: 0, y: -1 });
    expect(panKeyDirection({ key: "ArrowLeft" })).toEqual({ x: -1, y: 0 });
    expect(panKeyDirection({ key: "ArrowRight" })).toEqual({ x: 1, y: 0 });
    expect(panKeyDirection({ key: "ArrowRight", shiftKey: true })).toBeNull();
    expect(panKeyDirection({ key: " " })).toBeNull();
  });

  it("pans 15 % of the box, at least 60 px", () => {
    expect(panStep(view)).toEqual({ x: 150, y: 120 });
    expect(panStep({ width: 200, height: 100 })).toEqual({ x: 60, y: 60 });
  });
});
