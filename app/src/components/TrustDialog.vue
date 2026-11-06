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
import type { Extension } from "../api/types";
import { mediaKindOf } from "../api/types";
import { useExtensionStores, useTrustExtension } from "../composables/useExtensions";
import ErrorNotice from "./ErrorNotice.vue";

/**
 * The trust prompt (Mihon/Aniyomi model, Phase 1 §9): an installed extension whose signing certificate isn't trusted
 * is never loaded. This shows what would be trusted and asks. Trust calls `trustExtension` (which trusts the
 * certificate and loads everything it signs) and closes; Cancel, Escape or a click outside close and do nothing.
 */
const props = defineProps<{ extension: Extension | null }>();
const open = defineModel<boolean>("open", { required: true });
const emit = defineEmits<{ trusted: [extension: Extension] }>();

const stores = useExtensionStores();
const trust = useTrustExtension();
const copied = ref(false);
const hashEl = ref<HTMLElement | null>(null);

const repo = computed(() => {
  const url = props.extension?.storeIndexUrl;
  if (!url) return null;
  return stores.data.value?.find((s) => s.indexUrl === url) ?? { name: null, indexUrl: url };
});

watch(open, (isOpen) => {
  if (isOpen) {
    trust.reset();
    copied.value = false;
  }
});

async function copyHash() {
  const hash = props.extension?.signatureHash;
  if (!hash) return;
  try {
    await navigator.clipboard.writeText(hash);
    copied.value = true;
  } catch {
    // no clipboard access (an insecure origin): select it, so Ctrl+C copies it
    const el = hashEl.value;
    const selection = window.getSelection();
    if (el && selection) {
      const range = document.createRange();
      range.selectNodeContents(el);
      selection.removeAllRanges();
      selection.addRange(range);
    }
  }
}

async function confirm() {
  const ext = props.extension;
  if (!ext) return;
  try {
    const result = await trust.mutateAsync(ext);
    open.value = false;
    emit("trusted", result.extension);
  } catch {
    // shown below, from trust.error
  }
}
</script>

<template>
  <DialogRoot v-model:open="open">
    <DialogPortal>
      <DialogOverlay class="dialog-overlay" />
      <DialogContent class="dialog" data-testid="trust-dialog">
        <DialogTitle class="dialog-title">Trust this extension?</DialogTitle>
        <DialogDescription class="dialog-description">
          Its signing certificate isn't trusted yet, so Madoyomi installed it but doesn't run it.
        </DialogDescription>

        <dl v-if="extension" class="facts">
          <dt>Extension</dt>
          <dd>
            {{ extension.name }}
            <span class="muted">({{ mediaKindOf(extension.type) }})</span>
          </dd>
          <dt>Package</dt>
          <dd>
            <code data-testid="trust-pkg">{{ extension.pkgName }}</code>
          </dd>
          <dt>Version</dt>
          <dd>{{ extension.versionName }}</dd>
          <dt>Repo</dt>
          <dd data-testid="trust-repo">
            <template v-if="repo">
              {{ repo.name ?? "No added repo lists it any more" }}
              <span class="muted url">{{ repo.indexUrl }}</span>
            </template>
            <template v-else>None (sideloaded)</template>
          </dd>
          <dt>Signing certificate (SHA-256)</dt>
          <dd class="hash-row">
            <code ref="hashEl" class="hash" data-testid="trust-hash">{{
              extension.signatureHash ?? "unknown"
            }}</code>
            <button
              v-if="extension.signatureHash"
              type="button"
              class="btn copy"
              data-action="copy-hash"
              @click="copyHash"
            >
              {{ copied ? "Copied" : "Copy" }}
            </button>
          </dd>
        </dl>

        <div class="warning" role="note" data-testid="trust-warning">
          <p class="warning-title">Extensions are programs with full access to this PC.</p>
          <p>
            A trusted extension runs inside Madoyomi's backend with the same rights as you: it can
            read and change your files, use your network and see what you browse in it. Trust it
            only if you trust whoever signed it. Trusting this certificate also trusts every other
            extension signed with it.
          </p>
        </div>

        <ErrorNotice
          v-if="trust.error.value"
          title="Couldn't trust it"
          :error="trust.error.value"
        />

        <div class="dialog-actions">
          <DialogClose class="btn" data-action="cancel-trust">Cancel</DialogClose>
          <button
            type="button"
            class="btn btn-accent"
            data-action="confirm-trust"
            :disabled="!extension || trust.isPending.value"
            @click="confirm"
          >
            {{ trust.isPending.value ? "Trusting…" : "Trust and load it" }}
          </button>
        </div>
      </DialogContent>
    </DialogPortal>
  </DialogRoot>
</template>

<style scoped>
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
  overflow-wrap: anywhere;
}

.url {
  display: block;
  font-size: var(--font-size-caption);
  overflow-wrap: anywhere;
}

.hash-row {
  display: flex;
  align-items: flex-start;
  gap: var(--space-2);
}

.hash {
  flex: 1;
  padding: var(--space-1) var(--space-2);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-surface-sunken);
  overflow-wrap: anywhere;
  user-select: all;
}

.copy {
  flex: none;
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
