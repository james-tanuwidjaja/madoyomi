import { describe, expect, it } from "vitest";
import { mount } from "@vue/test-utils";
import NextEpisodeToast from "../NextEpisodeToast.vue";

/* The next-episode toast's states and buttons (Phase 4 step 15). */

describe("NextEpisodeToast", () => {
  it("shows the next episode with its countdown, and emits Play now and Cancel", async () => {
    const w = mount(NextEpisodeToast, {
      props: { name: "Episode 4", secondsLeft: 7, starting: false },
    });
    expect(w.find('[data-testid="next-episode-name"]').text()).toBe("Next: Episode 4");
    expect(w.find('[data-testid="next-episode-countdown"]').text()).toBe("Plays in 7 s");
    expect(w.attributes("role")).toBe("status");
    await w.find('[data-action="play-next"]').trigger("click");
    await w.find('[data-action="cancel-next"]').trigger("click");
    expect(w.emitted("play")).toHaveLength(1);
    expect(w.emitted("cancel")).toHaveLength(1);
  });

  it("says auto-play is off without a countdown, and Starting… while it starts", async () => {
    const w = mount(NextEpisodeToast, {
      props: { name: "Episode 4", secondsLeft: null, starting: false },
    });
    expect(w.find('[data-testid="next-episode-countdown"]').text()).toBe("Auto-play is off");
    await w.setProps({ starting: true });
    expect(w.find('[data-testid="next-episode-countdown"]').text()).toBe("Starting…");
    expect(w.find('[data-action="play-next"]').attributes("disabled")).toBeDefined();
  });
});
