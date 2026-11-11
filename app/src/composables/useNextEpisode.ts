import { onScopeDispose, ref, shallowRef, watch } from "vue";
import { fetchEpisodeVideos, fetchHosterVideos, stopPlayback } from "../api/operations";
import type { Episode } from "../api/types";
import { usePlaybackStore } from "../stores/playback";
import { NEXT_EPISODE_COUNTDOWN_SECONDS, usePlayerPrefsStore } from "../stores/playerPrefs";
import { useVideoChoicesStore } from "../stores/videoChoices";
import { episodeProgress, resumePoint } from "../utils/progress";
import { initialPick, rememberedHoster, videoOf } from "../utils/videos";
import { usePlayEpisode } from "./usePlayback";

/** What the picker shows when it opens because something failed, and the video it preselects instead. */
export interface PickerFailure {
  title: string;
  error: unknown;
  /** The video to preselect (by hoster name, title, resolution), if there is one. */
  preselect: { hosterName: string; videoTitle: string; resolution: number | null } | null;
}

export interface NextEpisodeOptions {
  animeId: () => number;
  episodes: () => readonly Episode[];
  sourceId: () => string;
  /** Opens the video picker for an episode (auto-play couldn't play it by itself). */
  openPicker: (episodeId: number, failure: PickerFailure) => void;
}

/**
 * The next-episode flow (Phase 4 steps 8 and 15) of one anime's details page. When a session of this anime ENDS
 * with a next episode (the end of the file, or a quit past the watched threshold), `offer` is that episode with a
 * countdown of {@link NEXT_EPISODE_COUNTDOWN_SECONDS} s (null without auto-play). When it reaches 0, or on `playNow`,
 * the next episode plays without the picker (Stage 3's decision): the videos are fetched, the remembered choice for
 * the source is taken (else the source's pick), and it plays from its resume point (as the picker's default), else
 * from the start. If that can't work, the picker opens for that episode with why.
 *
 * `cancel`, a new session (another Play), and the page going away (the scope's end) stop it. `cancel` and the page
 * going away while it starts also stop what it started: a start still fetching the videos plays nothing, and one whose
 * `playEpisode` was already on its way is stopped (`stopPlayback`) once it returns. Opening the picker only dismisses
 * the offer (`dismiss`): the user is about to play something themselves.
 */
export function useNextEpisode(options: NextEpisodeOptions) {
  const playback = usePlaybackStore();
  const prefs = usePlayerPrefsStore();
  const choices = useVideoChoicesStore();
  const play = usePlayEpisode();

  const offer = shallowRef<{ episode: Episode; secondsLeft: number | null } | null>(null);
  const starting = ref(false);
  let timer: ReturnType<typeof setInterval> | null = null;
  /** The session whose end the offer follows. */
  let endedSession: string | null = null;
  /** Bumped by every new offer and cancel: a start that is overtaken drops its result. */
  let generation = 0;
  /** The generation whose start a cancel asked to stop, should its mpv start anyway. */
  let stopRequested: number | null = null;

  function stopTimer() {
    if (timer !== null) clearInterval(timer);
    timer = null;
  }

  /** Ends the offer; with `stopStarted`, a start in flight is stopped too (see above). */
  function end(stopStarted: boolean) {
    if (starting.value && stopStarted) stopRequested = generation;
    generation++;
    stopTimer();
    offer.value = null;
    starting.value = false;
  }

  /** Cancel (the toast's button, the page going away): nothing plays, even a start already on its way. */
  function cancel() {
    end(true);
  }

  /** Hides the offer and drops a start's result, but leaves what already started playing. */
  function dismiss() {
    end(false);
  }

  function show(episode: Episode, sessionId: string) {
    dismiss();
    endedSession = sessionId;
    const countdown = prefs.autoPlayNext ? NEXT_EPISODE_COUNTDOWN_SECONDS : null;
    offer.value = { episode, secondsLeft: countdown };
    if (countdown === null) return;
    timer = setInterval(() => {
      const current = offer.value;
      if (!current || current.secondsLeft === null) return stopTimer();
      const left = current.secondsLeft - 1;
      if (left > 0) {
        offer.value = { ...current, secondsLeft: left };
      } else {
        offer.value = { ...current, secondsLeft: 0 };
        void playNow();
      }
    }, 1000);
  }

  async function playNow() {
    const current = offer.value;
    if (!current || starting.value) return;
    stopTimer();
    starting.value = true;
    const mine = ++generation;
    const episode = current.episode;
    const fail = (title: string, error: unknown) => {
      if (mine !== generation) return;
      offer.value = null;
      starting.value = false;
      options.openPicker(episode.id, { title, error, preselect: null });
    };
    const sourceId = options.sourceId();
    const remembered = choices.choiceOf(sourceId);
    try {
      let hosters = await fetchEpisodeVideos(episode.id);
      if (mine !== generation) return;
      // the remembered hoster loads only on request (as in the picker)
      const lazy = rememberedHoster(hosters, remembered);
      if (lazy && lazy.state === "IDLE") {
        const loaded = await fetchHosterVideos(episode.id, lazy.index).catch(() => null);
        if (mine !== generation) return;
        if (loaded) hosters = hosters.map((h) => (h.index === loaded.index ? loaded : h));
      }
      const pick = initialPick(hosters, remembered);
      const chosen = videoOf(hosters, pick);
      if (!chosen) {
        return fail("Couldn't play the next episode", new Error("There was no video to play."));
      }
      const resumeAt = resumePoint(episode.isWatched, episodeProgress(episode, null));
      if (mine !== generation) return;
      await play.mutateAsync({
        episodeId: episode.id,
        hosterIndex: chosen.hoster.index,
        videoIndex: chosen.video.index,
        startSeconds: resumeAt,
        sourceId,
        remember: {
          hosterName: chosen.hoster.name,
          videoTitle: chosen.video.videoTitle,
          resolution: chosen.video.resolution,
        },
      });
      if (mine !== generation) {
        // cancelled while playEpisode was on its way: mpv started anyway, so stop it
        if (stopRequested === mine) {
          stopRequested = null;
          await stopPlayback().catch(() => null);
        }
        return;
      }
      offer.value = null;
      starting.value = false;
    } catch (e) {
      fail("Couldn't play the next episode", e);
    }
  }

  watch(
    () => playback.ended,
    (event) => {
      if (!event || event.nextEpisodeId === null || event.session.animeId !== options.animeId()) {
        return;
      }
      const next = options.episodes().find((e) => e.id === event.nextEpisodeId);
      if (next) show(next, event.session.id);
    },
  );

  // Another Play (a new session) overtakes the offer; the one this flow starts has cleared it already.
  watch(
    () => playback.session?.id,
    (id, previous) => {
      if (id && id !== previous && id !== endedSession && offer.value && !starting.value) dismiss();
    },
  );

  onScopeDispose(cancel);

  return { offer, starting, playNow, cancel, dismiss };
}
