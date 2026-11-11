import { defineStore } from "pinia";
import { ref } from "vue";
import type { PlaybackEvent, PlaybackSession } from "../api/types";

/**
 * The session mpv is playing, as the backend's `playbackEvents` report it (Phase 4 Stage 3): the episode list draws
 * its row's progress from here while it plays (PROGRESS at most once a second), so nothing is refetched for it. Set
 * by every event of a session, cleared by its CLOSED (the stored progress is then the episode's own).
 */
export const usePlaybackStore = defineStore("playback", () => {
  const session = ref<PlaybackSession | null>(null);

  function apply(event: PlaybackEvent) {
    if (event.type === "CLOSED") {
      if (!session.value || session.value.id === event.session.id) session.value = null;
      return;
    }
    session.value = event.session;
  }

  function clear() {
    session.value = null;
  }

  return { session, apply, clear };
});
