import { describe, expect, it } from "vitest";

import {
  changeFor,
  editTextValueLine,
  changeTaken,
  preferenceSummary,
  preferenceValue,
  valueLabel,
  withChange,
} from "../preferences";
import {
  allPreferenceTypes,
  contentRatingMulti,
  nsfwCheckBox,
  qualityList,
  siteNumberSwitch,
  userAgentText,
} from "../../test/preferenceFixtures";

describe("preference values", () => {
  it("reads the stored value, or the default when none is stored", () => {
    expect(allPreferenceTypes.slice(0, 5).map(preferenceValue)).toEqual([
      "1080", // nothing stored: the default
      false,
      true,
      "Mozilla/5.0",
      ["safe", "suggestive"],
    ]);
    expect(preferenceValue({ ...contentRatingMulti, multiSelectCurrentValue: null })).toEqual([
      "safe",
    ]);
    expect(
      preferenceValue({
        ...userAgentText,
        editTextCurrentValue: null,
        text: null,
        editTextDefault: null,
      }),
    ).toBe("");
  });

  it("builds the one-field change for each kind", () => {
    expect(changeFor(0, qualityList, "720")).toEqual({ position: 0, listState: "720" });
    expect(changeFor(1, siteNumberSwitch, true)).toEqual({ position: 1, switchState: true });
    expect(changeFor(2, nsfwCheckBox, false)).toEqual({ position: 2, checkBoxState: false });
    expect(changeFor(3, userAgentText, "x")).toEqual({ position: 3, editTextState: "x" });
    expect(changeFor(4, contentRatingMulti, ["erotica"])).toEqual({
      position: 4,
      multiSelectState: ["erotica"],
    });
  });

  it("applies a change to a preference, and ignores a change of another kind", () => {
    const changed = allPreferenceTypes
      .slice(0, 5)
      .map((p, i) =>
        preferenceValue(
          withChange(p, changeFor(i, p, ["360", true, false, "Edge", ["erotica"]][i] as never)),
        ),
      );
    expect(changed).toEqual(["360", true, false, "Edge", ["erotica"]]);
    expect(withChange(qualityList, { position: 0, switchState: true })).toBe(qualityList);
  });

  it("tells a change the server took from one the extension refused", () => {
    const sent = { position: 0, listState: "720" };
    expect(changeTaken(withChange(qualityList, sent), sent)).toBe(true);
    expect(changeTaken(qualityList, sent)).toBe(false);

    // a multi-select is a set: the order doesn't count
    const multi = { position: 4, multiSelectState: ["suggestive", "safe"] };
    expect(changeTaken(contentRatingMulti, multi)).toBe(true);
    expect(changeTaken(contentRatingMulti, { position: 4, multiSelectState: ["safe"] })).toBe(
      false,
    );
  });

  it("formats a list's %s summary with its entry, and labels values", () => {
    expect(preferenceSummary(qualityList)).toBe("1080p");
    expect(preferenceSummary({ ...qualityList, summary: "Now %s (100%%)" })).toBe(
      "Now 1080p (100%)",
    );
    expect(preferenceSummary(siteNumberSwitch)).toBe("Instead of counting from 1");
    // an edit text without a summary shows its value
    expect(preferenceSummary(userAgentText)).toBe("Mozilla/5.0");
    expect(preferenceSummary(nsfwCheckBox)).toBeNull();

    // an edit text with a summary shows its value on a line of its own
    expect(editTextValueLine(userAgentText)).toBeNull(); // no summary: the summary line is the value
    expect(editTextValueLine({ ...userAgentText, summary: "Sent to the site" })).toBe(
      "Mozilla/5.0",
    );
    expect(
      editTextValueLine({ ...userAgentText, summary: "x", editTextCurrentValue: "", text: "" }),
    ).toBeNull();

    expect(valueLabel(qualityList)).toBe("1080p");
    expect(valueLabel(siteNumberSwitch)).toBe("off");
    expect(valueLabel(userAgentText)).toBe('"Mozilla/5.0"');
    expect(valueLabel(contentRatingMulti)).toBe("Safe, Suggestive");
  });
});
