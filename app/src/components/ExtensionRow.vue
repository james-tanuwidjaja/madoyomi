<script setup lang="ts">
import { computed } from "vue";
import { PopoverContent, PopoverPortal, PopoverRoot, PopoverTrigger } from "reka-ui";
import { ApiError, useAssetUrl } from "../api/client";
import { describeError } from "../api/errorText";
import type { Extension, ExtensionAction, ExtensionInstallProgress } from "../api/types";
import type { ExtensionActionState } from "../composables/useExtensions";
import { formatExtensionLang } from "../utils/format";

/**
 * One extension in the Extensions list: what it is, its state (trusted, loaded, update, compat issues), the live
 * install step (from the events store) and its last error, and the actions that apply to it.
 */
const props = defineProps<{
  extension: Extension;
  repoName: string | null;
  /** Its latest action from this app session (useExtensionActionState). */
  actionState?: ExtensionActionState;
  /** Its latest `extensionInstallProgress` (useEventsStore().installProgress). */
  progress?: ExtensionInstallProgress;
}>();
const emit = defineEmits<{ action: [action: ExtensionAction]; trust: [] }>();

const icon = useAssetUrl(() => props.extension.iconUrl);

const STEP_LABELS: Record<string, string> = {
  DOWNLOADING: "Downloading…",
  VERIFYING: "Verifying the signature…",
  CONVERTING: "Converting…",
};

const pending = computed(() => props.actionState?.status === "pending");

/** What it's doing now, or null when idle. */
const step = computed(() => {
  const p = props.progress;
  if (p && STEP_LABELS[p.state]) return STEP_LABELS[p.state];
  if (!pending.value) return null;
  if (p?.state === "INSTALLED") return "Finishing…";
  switch (props.actionState?.action) {
    case "uninstall":
      return "Uninstalling…";
    case "update":
      return "Starting the update…";
    default:
      return "Starting…";
  }
});

/** Its last failure: the action's own error, else a FAILED step (e.g. an install started before a reload). */
const error = computed(() => {
  const a = props.actionState;
  if (a?.status === "error") return describeError(a.error);
  const p = props.progress;
  if (p?.state === "FAILED" && !pending.value) {
    return describeError(
      new ApiError({
        kind: "graphql",
        code: p.errorCode ?? "INSTALL_FAILED",
        message: p.message ?? "The install failed",
      }),
    );
  }
  return null;
});

const untrusted = computed(() => props.extension.isInstalled && !props.extension.isTrusted);
const notLoaded = computed(
  () => props.extension.isInstalled && props.extension.isTrusted && !props.extension.isLoaded,
);
const issues = computed(() => props.extension.compatIssues ?? []);
</script>

<template>
  <div
    class="ext-row"
    :class="{ busy: step !== null }"
    :data-pkg="extension.pkgName"
    data-testid="extension-row"
  >
    <img v-if="icon" class="icon" :src="icon" alt="" width="40" height="40" loading="lazy" />
    <span v-else class="icon placeholder" aria-hidden="true" />

    <div class="main">
      <p class="title">
        <span class="name">{{ extension.name }}</span>
        <span class="version">{{ extension.versionName }}</span>
        <span class="badge">{{ formatExtensionLang(extension.lang) }}</span>
        <span v-if="extension.contentWarning === 'NSFW'" class="badge danger">18+</span>
        <span v-else-if="extension.contentWarning === 'MIXED'" class="badge">Some 18+</span>
        <span v-if="extension.isTorrent" class="badge">Torrent</span>
        <span
          v-if="extension.isObsolete"
          class="badge warn"
          title="No added repo lists it any more"
        >
          Obsolete
        </span>
        <span v-if="untrusted" class="badge warn" data-testid="badge-untrusted">Untrusted</span>
        <span
          v-if="notLoaded"
          class="badge warn"
          title="Trusted, but it couldn't be loaded. The backend's log says why."
        >
          Not loaded
        </span>
        <span v-if="extension.isInstalled && extension.hasUpdate" class="badge ok">Update</span>
      </p>
      <p class="sub">
        <code>{{ extension.pkgName }}</code>
        <template v-if="repoName"> · {{ repoName }}</template>
      </p>

      <div v-if="step" class="progress" role="status" data-testid="extension-step">
        <span>{{ step }}</span>
        <span class="bar" aria-hidden="true"><span /></span>
      </div>
      <p v-else-if="error" class="error" role="alert" data-testid="extension-error">
        <code>{{ error.code }}</code>
        {{ error.hint ?? "" }}
        <span class="error-message">{{ error.message }}</span>
      </p>
    </div>

    <PopoverRoot v-if="issues.length > 0">
      <PopoverTrigger class="btn btn-small" data-action="compat-issues">
        {{ issues.length }} API {{ issues.length === 1 ? "gap" : "gaps" }}
      </PopoverTrigger>
      <PopoverPortal>
        <PopoverContent class="flyout" side="bottom" align="end" :side-offset="4">
          <p class="flyout-title">APIs this extension needs and Madoyomi lacks</p>
          <ul class="issues">
            <li v-for="i in issues" :key="`${i.kind}-${i.target}-${i.origin}`">
              <code>{{ i.target }}</code>
              <span class="muted">
                {{ i.kind }} · {{ i.origin === "STATIC" ? "found at install" : "hit at runtime" }}
                <template v-if="i.context"> · {{ i.context }}</template>
                · {{ i.count }}×
              </span>
            </li>
          </ul>
        </PopoverContent>
      </PopoverPortal>
    </PopoverRoot>

    <div class="actions">
      <template v-if="!extension.isInstalled">
        <button
          type="button"
          class="btn btn-small btn-accent"
          data-action="install"
          :disabled="pending"
          @click="emit('action', 'install')"
        >
          Install
        </button>
      </template>
      <template v-else>
        <button
          v-if="untrusted"
          type="button"
          class="btn btn-small btn-accent"
          data-action="trust"
          :disabled="pending"
          @click="emit('trust')"
        >
          Trust…
        </button>
        <button
          v-if="extension.hasUpdate"
          type="button"
          class="btn btn-small"
          :class="{ 'btn-accent': !untrusted }"
          data-action="update"
          :disabled="pending"
          @click="emit('action', 'update')"
        >
          Update
        </button>
        <button
          type="button"
          class="btn btn-small"
          data-action="uninstall"
          :disabled="pending"
          @click="emit('action', 'uninstall')"
        >
          Uninstall
        </button>
      </template>
    </div>
  </div>
</template>

<style scoped>
.ext-row {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface-raised);
}

p {
  margin: 0;
}

.icon {
  flex: none;
  width: 40px;
  height: 40px;
  border-radius: var(--radius-sm);
}

.icon.placeholder {
  background: var(--color-surface-sunken);
}

.main {
  display: grid;
  flex: 1;
  gap: 2px;
  min-width: 0;
}

.title {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-1) var(--space-2);
}

.name {
  font-weight: var(--font-weight-semibold);
}

.version,
.sub {
  color: var(--color-text-secondary);
  font-size: var(--font-size-caption);
}

.sub {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.progress {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  color: var(--color-accent);
  font-size: var(--font-size-caption);
}

.bar {
  position: relative;
  overflow: hidden;
  width: 120px;
  height: 3px;
  border-radius: 2px;
  background: var(--color-border-strong);
}

.bar > span {
  position: absolute;
  inset: 0 auto 0 0;
  width: 40%;
  border-radius: 2px;
  background: var(--color-accent);
  animation: indeterminate 1.2s var(--easing-standard) infinite;
}

@keyframes indeterminate {
  from {
    transform: translateX(-100%);
  }
  to {
    transform: translateX(250%);
  }
}

@media (prefers-reduced-motion: reduce) {
  .bar > span {
    animation: none;
    width: 100%;
    opacity: 0.6;
  }
}

.error {
  color: var(--color-danger);
  font-size: var(--font-size-caption);
}

.error-message {
  display: block;
  color: var(--color-text-secondary);
  overflow-wrap: anywhere;
}

.actions {
  display: flex;
  flex: none;
  gap: var(--space-2);
}

.flyout-title {
  margin-bottom: var(--space-2);
  font-weight: var(--font-weight-semibold);
}

.issues {
  display: grid;
  gap: var(--space-2);
  margin: 0;
  padding: 0;
  list-style: none;
  font-size: var(--font-size-caption);
}

.issues li {
  display: grid;
}

.issues code {
  overflow-wrap: anywhere;
}

.muted {
  color: var(--color-text-tertiary);
}
</style>
