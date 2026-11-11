import { defineStore } from "pinia";
import { ref, shallowRef } from "vue";
import type { PlaybackEvent, PlaybackSession } from "../api/types";

/**
 * The session mpv is playing, as the backend's `playbackEvents` report it (Phase 4 Stage 3): the episode list draws
 * its row's progress from here while it plays (PROGRESS at most once a second), so nothing is refetched for it. Set
 * by every event of a session, cleared by its CLOSED (the stored progress is then the episode's own).
 *
 * Stage 4: `retrying` while the backend fetches the videos of a session that couldn't open its video again
 * (RETRYING, until that session's CLOSED), and the last ENDED and ERROR events, for the details page to act on (the
 * next-episode toast, the picker reopened with the next video). Each is a new object per event, so a watcher sees
 * every one; nothing is kept from before the app started.
 */
export const usePlaybackStore = defineStore("playback", () => {
  const session = ref<PlaybackSession | null>(null);
  const retrying = ref(false);
  const ended = shallowRef<PlaybackEvent | null>(null);
  const failed = shallowRef<PlaybackEvent | null>(null);

  function apply(event: PlaybackEvent) {
    if (event.type === "CLOSED") {
      if (!session.value || session.value.id === event.session.id) {
        session.value = null;
        retrying.value = false;
      }
      return;
    }
    if (session.value?.id !== event.session.id) retrying.value = false;
    session.value = event.session;
    if (event.type === "RETRYING") retrying.value = true;
    else if (event.type === "ENDED") ended.value = event;
    else if (event.type === "ERROR") failed.value = event;
  }

  function clear() {
    session.value = null;
    retrying.value = false;
  }

  return { session, retrying, ended, failed, apply, clear };
});
