import { defineStore } from "pinia";
import { ref, watch } from "vue";
import type { RememberedChoice } from "../utils/videos";

/** `localStorage` key of the last video chosen per anime source: `{"<sourceId>": {hosterName, videoTitle, resolution}}`. */
export const VIDEO_CHOICES_KEY = "madoyomi.videoChoices";

type Choices = Record<string, RememberedChoice>;

function valid(v: unknown): v is RememberedChoice {
  if (!v || typeof v !== "object") return false;
  const c = v as Record<string, unknown>;
  return (
    typeof c.hosterName === "string" &&
    typeof c.videoTitle === "string" &&
    (c.resolution === null || typeof c.resolution === "number")
  );
}

function stored(): Choices {
  try {
    const raw = JSON.parse(localStorage.getItem(VIDEO_CHOICES_KEY) ?? "null") as unknown;
    if (raw && typeof raw === "object") {
      return Object.fromEntries(Object.entries(raw).filter(([, v]) => valid(v))) as Choices;
    }
  } catch {
    // unreadable or disabled storage: nothing remembered
  }
  return {};
}

/**
 * The video picker's last choice per anime source (Phase 4 step 12): the hoster's name and the video's title and
 * resolution, which stay the same between episodes where the indexes don't. Kept on this device, like the other UI
 * preferences (pins, sorts; Phase 3 §9), and preselected the next time an episode of that source is played.
 */
export const useVideoChoicesStore = defineStore("videoChoices", () => {
  const choices = ref<Choices>(stored());

  watch(
    choices,
    (c) => {
      try {
        localStorage.setItem(VIDEO_CHOICES_KEY, JSON.stringify(c));
      } catch {
        // storage full or disabled: remembered for this session only
      }
    },
    { deep: true, flush: "sync" },
  );

  const choiceOf = (sourceId: string): RememberedChoice | null => choices.value[sourceId] ?? null;

  function remember(sourceId: string, choice: RememberedChoice) {
    choices.value[sourceId] = { ...choice };
  }

  return { choices, choiceOf, remember };
});
