<script setup lang="ts">
import { computed, ref, watch } from "vue";
import {
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogOverlay,
  DialogPortal,
  DialogRoot,
  DialogTitle,
} from "reka-ui";
import { validateBackup } from "../api/operations";
import type { BackupReport, RestoreMode } from "../api/types";
import { mediaKindOf } from "../api/types";
import { useRestoreBackup } from "../composables/useBackups";
import {
  animeSummary,
  mangaSummary,
  restoreDefaults,
  settingsSummary,
  shortHash,
} from "../utils/backups";
import { clockTime } from "../utils/updates";
import ErrorNotice from "./ErrorNotice.vue";

/**
 * Restoring a backup file: what it holds, checked against this install first (`validateMadoyomiBackup`),
 * then merge or replace, what to restore, and the trust prompt for what it would add: the repos (adding one trusts
 * its signing key), the certificates the user had trusted, and the extensions reinstalled from their repos. Only what
 * stays checked is added, trusted or installed. Restore starts it in the background and closes; Settings shows the
 * progress.
 */
const props = defineProps<{ name: string | null }>();
const open = defineModel<boolean>("open", { required: true });

const restore = useRestoreBackup();
const report = ref<BackupReport | null>(null);
const loadError = ref<unknown>(null);
const loading = ref(false);
const mode = ref<RestoreMode>("MERGE");
const library = ref(true);
const settings = ref(true);
const repos = ref(new Set<string>());
const certificates = ref(new Set<string>());
const extensions = ref(new Set<string>());

watch(
  () => [open.value, props.name] as const,
  async ([isOpen, name]) => {
    if (!isOpen || !name) return;
    restore.reset();
    report.value = null;
    loadError.value = null;
    loading.value = true;
    mode.value = "MERGE";
    library.value = true;
    settings.value = true;
    try {
      const r = await validateBackup(name);
      if (props.name !== name) return;
      report.value = r;
      const d = restoreDefaults(r);
      repos.value = new Set(d.repos);
      certificates.value = new Set(d.certificates);
      extensions.value = new Set(d.extensions);
    } catch (e) {
      loadError.value = e;
    } finally {
      loading.value = false;
    }
  },
  { immediate: true },
);

const isMadoyomi = computed(() => report.value?.format === "MADOYOMI");
const newRepos = computed(() => report.value?.repos.filter((r) => !r.added) ?? []);
const newCertificates = computed(() => report.value?.certificates.filter((c) => !c.trusted) ?? []);
const newExtensions = computed(() => report.value?.extensions.filter((e) => !e.installed) ?? []);
const hasTrust = computed(
  () => newRepos.value.length + newCertificates.value.length + newExtensions.value.length > 0,
);
const trusting = computed(() => repos.value.size + certificates.value.size > 0);

function toggle(set: Set<string>, key: string, on: boolean) {
  if (on) set.add(key);
  else set.delete(key);
}

const checked = (e: Event) => (e.target as HTMLInputElement).checked;

async function confirm() {
  const name = props.name;
  if (!name || !report.value) return;
  try {
    await restore.mutateAsync({
      name,
      mode: mode.value,
      library: library.value,
      settings: settings.value,
      repos: [...repos.value],
      certificates: [...certificates.value],
      extensions: [...extensions.value],
    });
    open.value = false;
  } catch {
    // shown below, from restore.error
  }
}
</script>

<template>
  <DialogRoot v-model:open="open">
    <DialogPortal>
      <DialogOverlay class="dialog-overlay" />
      <DialogContent class="dialog restore-dialog" data-testid="restore-dialog">
        <DialogTitle class="dialog-title">Restore this backup?</DialogTitle>
        <DialogDescription class="dialog-description">
          <code>{{ name }}</code>
          <template v-if="report?.createdAt">
            · made {{ clockTime(report.createdAt) }}
            <template v-if="report.appVersion">by Madoyomi {{ report.appVersion }}</template>
          </template>
        </DialogDescription>

        <p v-if="loading" class="muted">Reading the backup…</p>
        <ErrorNotice v-else-if="loadError" title="Couldn't read this backup" :error="loadError" />

        <template v-else-if="report">
          <p v-if="!isMadoyomi" class="note" data-testid="restore-tachibk">
            A Mihon, Aniyomi or Suwayomi backup: only its manga part (library, categories, chapters,
            history) is read.
          </p>
          <dl class="facts" data-testid="restore-summary">
            <template v-if="isMadoyomi">
              <dt>Anime</dt>
              <dd data-testid="restore-anime">{{ animeSummary(report) }}</dd>
            </template>
            <dt>Manga</dt>
            <dd data-testid="restore-manga">{{ mangaSummary(report) }}</dd>
            <template v-if="isMadoyomi">
              <dt>Settings</dt>
              <dd data-testid="restore-settings-summary">{{ settingsSummary(report) }}</dd>
            </template>
          </dl>

          <div v-if="report.missingSources.length" class="missing" data-testid="restore-missing">
            <p class="label">Sources not installed here</p>
            <ul>
              <li v-for="s in report.missingSources" :key="`${s.kind}-${s.id}`">
                {{ s.name || `Source ${s.id}` }} ({{ mediaKindOf(s.kind) }}, {{ s.entries }}
                {{ s.entries === 1 ? "entry" : "entries" }}):
                <template v-if="s.extension">comes back with its extension below</template>
                <template v-else
                  >no extension in the backup provides it; the entries come back and open once you
                  install one</template
                >
              </li>
            </ul>
          </div>

          <fieldset class="choices">
            <legend>Restore</legend>
            <label class="choice">
              <input v-model="mode" type="radio" value="MERGE" data-testid="mode-merge" />
              <span>
                <strong>Merge</strong>
                <span class="hint"
                  >Keeps what is here and adds the backup's. Watched and read marks are kept from
                  both, the newer position wins.</span
                >
              </span>
            </label>
            <label class="choice">
              <input v-model="mode" type="radio" value="REPLACE" data-testid="mode-replace" />
              <span>
                <strong>Replace</strong>
                <span class="hint"
                  >The library, categories, progress and history become the backup's. Entries it
                  doesn't have leave the library (nothing is deleted).</span
                >
              </span>
            </label>
            <label class="choice">
              <input v-model="library" type="checkbox" data-testid="restore-library" />
              <span>Library, categories, progress and history</span>
            </label>
            <label v-if="isMadoyomi" class="choice">
              <input v-model="settings" type="checkbox" data-testid="restore-settings" />
              <span
                >Settings (player, reader, updates, downloads, backups) and source settings</span
              >
            </label>
          </fieldset>

          <fieldset v-if="hasTrust" class="choices" data-testid="restore-trust">
            <legend>Extensions and trust</legend>
            <div class="warning" role="note">
              <p class="warning-title">Extensions are programs with full access to this PC.</p>
              <p>
                Adding a repo trusts its signing key, and the extensions signed with it run inside
                Madoyomi's backend with your rights. Keep checked only what you trusted before.
              </p>
            </div>
            <label v-for="r in newRepos" :key="r.indexUrl" class="choice">
              <input
                type="checkbox"
                :checked="repos.has(r.indexUrl)"
                :data-repo="r.indexUrl"
                @change="toggle(repos, r.indexUrl, checked($event))"
              />
              <span>
                Add the {{ mediaKindOf(r.kind) }} repo <strong>{{ r.name || r.indexUrl }}</strong>
                <span class="hint" :title="r.signingKey"
                  >{{ r.indexUrl }} · trusts the key {{ shortHash(r.signingKey) }}</span
                >
              </span>
            </label>
            <label v-for="c in newCertificates" :key="c.hash" class="choice">
              <input
                type="checkbox"
                :checked="certificates.has(c.hash)"
                :data-certificate="c.hash"
                @change="toggle(certificates, c.hash, checked($event))"
              />
              <span>
                Trust the certificate <code :title="c.hash">{{ shortHash(c.hash) }}</code>
                <span class="hint">You trusted it in the install the backup came from.</span>
              </span>
            </label>
            <label v-for="e in newExtensions" :key="e.pkgName" class="choice">
              <input
                type="checkbox"
                :checked="extensions.has(e.pkgName)"
                :disabled="!e.inRepo"
                :data-extension="e.pkgName"
                @change="toggle(extensions, e.pkgName, checked($event))"
              />
              <span>
                Reinstall <strong>{{ e.name || e.pkgName }}</strong> ({{ mediaKindOf(e.kind) }}
                {{ e.versionName }})
                <span v-if="!e.inRepo" class="hint">No repo here or in the backup lists it.</span>
                <span v-else-if="!e.trustedAfterRestore" class="hint"
                  >Its certificate won't be trusted: it installs but doesn't run until you trust
                  it.</span
                >
              </span>
            </label>
          </fieldset>
        </template>

        <ErrorNotice
          v-if="restore.error.value"
          title="Couldn't start the restore"
          :error="restore.error.value"
        />

        <div class="dialog-actions">
          <DialogClose class="btn" data-action="cancel-restore">Cancel</DialogClose>
          <button
            type="button"
            class="btn btn-accent"
            data-action="confirm-restore"
            :disabled="!report || restore.isPending.value || (!library && !settings && !hasTrust)"
            @click="confirm"
          >
            {{ restore.isPending.value ? "Starting…" : trusting ? "Trust and restore" : "Restore" }}
          </button>
        </div>
      </DialogContent>
    </DialogPortal>
  </DialogRoot>
</template>

<style scoped>
.restore-dialog {
  max-height: calc(100vh - 4rem);
  overflow-y: auto;
}

.facts {
  display: grid;
  grid-template-columns: max-content 1fr;
  gap: var(--space-2) var(--space-4);
  margin: 0;
}

dt {
  color: var(--color-text-secondary);
}

dd {
  min-width: 0;
  margin: 0;
}

.choices {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  margin: 0;
  padding: 0;
  border: none;
}

legend {
  margin-bottom: var(--space-2);
  font-weight: var(--font-weight-semibold);
}

.choice {
  display: flex;
  align-items: flex-start;
  gap: var(--space-2);
}

.choice input {
  margin-top: 0.2em;
}

.hint {
  display: block;
  color: var(--color-text-secondary);
  font-size: var(--font-size-caption);
  overflow-wrap: anywhere;
}

.missing ul {
  margin: 0;
  padding-left: var(--space-5);
}

.label {
  margin: 0 0 var(--space-1);
  color: var(--color-text-secondary);
}

.note {
  margin: 0;
  color: var(--color-text-secondary);
}

.warning {
  padding: var(--space-3) var(--space-4);
  border: 1px solid var(--color-border-strong);
  border-left: 3px solid var(--color-warning);
  border-radius: var(--radius-md);
  background: var(--color-surface-sunken);
}

.warning p {
  margin: 0;
}

.warning-title {
  margin-bottom: var(--space-1) !important;
  color: var(--color-warning);
  font-weight: var(--font-weight-semibold);
}

.muted {
  color: var(--color-text-tertiary);
}
</style>
