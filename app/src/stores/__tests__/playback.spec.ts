import { beforeEach, describe, expect, it } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import { usePlaybackStore } from "../playback";
import { makePlaybackEvent, makePlaybackSession } from "../../test/fixtures";

/* The playback store: the live session, a retry, and the last ENDED and ERROR events (Phase 4 Stages 3-4). */

beforeEach(() => setActivePinia(createPinia()));

describe("playback store", () => {
  it("follows a session until its CLOSED, and ignores another session's CLOSED", () => {
    const store = usePlaybackStore();
    store.apply(
      makePlaybackEvent("PROGRESS", { session: makePlaybackSession({ positionSeconds: 5 }) }),
    );
    expect(store.session?.positionSeconds).toBe(5);
    store.apply(makePlaybackEvent("CLOSED", { session: makePlaybackSession({ id: "other" }) }));
    expect(store.session?.id).toBe("s1");
    store.apply(makePlaybackEvent("CLOSED"));
    expect(store.session).toBeNull();
  });

  it("is retrying from RETRYING until that session's CLOSED, and not for the new session", () => {
    const store = usePlaybackStore();
    store.apply(makePlaybackEvent("STARTED"));
    store.apply(makePlaybackEvent("RETRYING", { httpStatus: 403 }));
    expect(store.retrying).toBe(true);
    store.apply(makePlaybackEvent("CLOSED"));
    expect(store.retrying).toBe(false);

    store.apply(makePlaybackEvent("RETRYING"));
    // the new session started before the old one's CLOSED arrived
    store.apply(makePlaybackEvent("STARTED", { session: makePlaybackSession({ id: "s2" }) }));
    expect(store.retrying).toBe(false);
    expect(store.session?.id).toBe("s2");
  });

  it("keeps each ENDED and ERROR as a new object, for watchers", () => {
    const store = usePlaybackStore();
    const ended = makePlaybackEvent("ENDED", { nextEpisodeId: 1002 });
    store.apply(ended);
    expect(store.ended).toBe(ended);
    const again = makePlaybackEvent("ENDED", { nextEpisodeId: 1002 });
    store.apply(again);
    expect(store.ended).toBe(again);
    const error = makePlaybackEvent("ERROR", { retried: true });
    store.apply(error);
    expect(store.failed).toBe(error);
    store.apply(makePlaybackEvent("CLOSED"));
    expect(store.ended).toBe(again);
  });
});
