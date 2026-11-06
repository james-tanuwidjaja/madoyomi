import { computed, toValue, type MaybeRefOrGetter } from "vue";
import {
  useMutation,
  useMutationState,
  useQuery,
  useQueryClient,
  type Mutation,
} from "@tanstack/vue-query";
import { useBackendStore } from "../stores/backend";
import { useEventsStore } from "../stores/events";
import {
  addRepo,
  fetchExtensionStores,
  fetchExtensions,
  refreshRepos,
  removeRepo,
  trustExtension,
  updateExtension,
} from "../api/operations";
import { isApiError } from "../api/client";
import type {
  Extension,
  ExtensionAction,
  ExtensionStore,
  MediaKind,
  RepoIndexFormat,
} from "../api/types";
import { mediaKindOf } from "../api/types";
import { queryKeys } from "./queryKeys";

/** The repos of both kinds. They change by user action and refreshes, both of which invalidate this. */
export function useExtensionStores() {
  const backend = useBackendStore();
  return useQuery({
    queryKey: queryKeys.extensionStores(),
    queryFn: ({ signal }) => fetchExtensionStores(signal),
    enabled: computed(() => backend.ready),
    staleTime: 60_000,
  });
}

/** Every listed extension of `kind` (installed or not), or of both kinds. */
export function useExtensions(kind?: MaybeRefOrGetter<MediaKind | undefined>) {
  const backend = useBackendStore();
  return useQuery({
    queryKey: computed(() => queryKeys.extensions(toValue(kind))),
    queryFn: ({ signal }) => fetchExtensions(toValue(kind), signal),
    enabled: computed(() => backend.ready),
    staleTime: 30_000,
  });
}

/** Invalidates what a change to `kind`'s repos or extensions can change. */
function invalidateKind(queryClient: ReturnType<typeof useQueryClient>, kind: MediaKind) {
  void queryClient.invalidateQueries({ queryKey: queryKeys.extensionStores() });
  void queryClient.invalidateQueries({ queryKey: queryKeys.extensions(kind) });
  void queryClient.invalidateQueries({ queryKey: queryKeys.sources(kind) });
}

const REFRESH_REPOS_KEY = ["refreshRepos"] as const;

/**
 * Refreshes the repos (`fetchExtensions`) of one kind, or of both. The server refreshes a whole kind at a time (its
 * pruning needs every repo of the kind), so there is no single-repo refresh. The per-repo results arrive as
 * `extensionStoreRefreshed` too; this invalidates the lists itself as well, so it works while the WebSocket is
 * reconnecting.
 */
export function useRefreshRepos() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationKey: REFRESH_REPOS_KEY,
    mutationFn: (kind?: MediaKind) => refreshRepos(kind),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.extensionStores() });
      void queryClient.invalidateQueries({ queryKey: queryKeys.extensions() });
    },
  });
}

/** The kinds whose repos are being refreshed right now (`"all"` for a refresh of both). */
export function useRepoRefreshState() {
  const pending = useMutationState({
    filters: { mutationKey: REFRESH_REPOS_KEY, status: "pending" },
    select: (m) => (m.state.variables as MediaKind | undefined) ?? "all",
  });
  return computed(() => new Set(pending.value));
}

// ---------------------------------------------------------------------------------------------------------------
// Adding and removing repos

export interface AddRepoRequest {
  indexUrl: string;
  /** `"auto"`: detected (a guess from the URL, then the other kind if the server says it's wrong). */
  kind: MediaKind | "auto";
  format?: RepoIndexFormat;
}

export interface AddRepoOutcome {
  store: ExtensionStore;
  kind: MediaKind;
  /** The kind was detected, not chosen. */
  detected: boolean;
  /** The stored URL was already in the list: a re-add, not a new repo. */
  existed: boolean;
}

/** The first kind to try for a URL: anime if it says so (yuzono's `anime-repo`), else manga (the server's default). */
export function guessRepoKind(indexUrl: string): MediaKind {
  return /anime/i.test(indexUrl) ? "anime" : "manga";
}

/**
 * `REPO_TYPE_MISMATCH` from the server's package check (some packages aren't of the kind: `details.packages`), as
 * opposed to "already added as the other kind", which has no `packages` and no other kind to try.
 */
function isPackageKindMismatch(e: unknown): boolean {
  if (!isApiError(e) || e.code !== "REPO_TYPE_MISMATCH") return false;
  const details = e.details as { packages?: unknown } | null;
  return Array.isArray(details?.packages);
}

/**
 * Adds a repo, detecting its kind when asked to. The server reads either kind's index format, then checks every
 * package against the kind it was given, so a wrong guess fails with `REPO_TYPE_MISMATCH` before anything is
 * written, and the other kind is tried once. `existingUrls` are the stored URLs before the add: the server stores
 * where the index resolved to, so a re-add is recognised by the URL it returns.
 */
export async function addRepoDetectingKind(
  request: AddRepoRequest,
  existingUrls: readonly string[],
): Promise<AddRepoOutcome> {
  const indexUrl = request.indexUrl.trim();
  const detected = request.kind === "auto";
  const first: MediaKind = request.kind === "auto" ? guessRepoKind(indexUrl) : request.kind;
  const finish = (store: ExtensionStore): AddRepoOutcome => ({
    store,
    kind: mediaKindOf(store.type),
    detected,
    existed: existingUrls.includes(store.indexUrl),
  });
  try {
    return finish(await addRepo({ indexUrl, kind: first, format: request.format }));
  } catch (e) {
    if (!detected || !isPackageKindMismatch(e)) throw e;
    const other: MediaKind = first === "anime" ? "manga" : "anime";
    return finish(await addRepo({ indexUrl, kind: other, format: request.format }));
  }
}

/** Adds a repo (see {@link addRepoDetectingKind}). Adding trusts its signing key, which can load installed extensions. */
export function useAddRepo() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: AddRepoRequest) => {
      const existing =
        queryClient.getQueryData<ExtensionStore[]>(queryKeys.extensionStores()) ?? [];
      return addRepoDetectingKind(
        request,
        existing.map((s) => s.indexUrl),
      );
    },
    onSuccess: (outcome) => invalidateKind(queryClient, outcome.kind),
  });
}

/** Removes a repo. Its uninstalled listings go, and installed extensions only it vouched for stop loading. */
export function useRemoveRepo() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (store: Pick<ExtensionStore, "indexUrl" | "type">) => {
      await removeRepo(store.indexUrl);
      return mediaKindOf(store.type);
    },
    onSuccess: (kind) => invalidateKind(queryClient, kind),
  });
}

// ---------------------------------------------------------------------------------------------------------------
// Extension actions and trust

const EXTENSION_ACTION_KEY = ["extensionAction"] as const;

export interface ExtensionActionRequest {
  pkgName: string;
  kind: MediaKind;
  action: ExtensionAction;
}

/**
 * Installs, updates or uninstalls an extension. The steps come from the events store (src/api/realtime.ts follows
 * every install), and a new action forgets the package's last step first, so an old failure doesn't linger. The
 * state of each package's latest action is in {@link useExtensionActionState}.
 */
export function useExtensionAction() {
  const queryClient = useQueryClient();
  const events = useEventsStore();
  return useMutation({
    mutationKey: EXTENSION_ACTION_KEY,
    mutationFn: (request: ExtensionActionRequest) =>
      updateExtension(request.pkgName, request.action),
    onMutate: (request) => events.clearInstall(request.pkgName),
    onSettled: (_data, _error, request) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.extensions(request.kind) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.sources(request.kind) });
    },
  });
}

export interface ExtensionActionState {
  action: ExtensionAction;
  status: "idle" | "pending" | "success" | "error";
  error: unknown;
  /** What the action returned (the extension afterwards), once it succeeded. */
  result: Extension | null;
}

/**
 * The latest action of each package, from vue-query's mutation cache. That cache outlives the view, so an install
 * started before leaving the screen still shows when you come back.
 */
export function useExtensionActionState() {
  const all = useMutationState({
    filters: { mutationKey: EXTENSION_ACTION_KEY },
    select: (m: Mutation<unknown, unknown, unknown>) => ({
      request: m.state.variables as ExtensionActionRequest | undefined,
      state: {
        action: (m.state.variables as ExtensionActionRequest | undefined)?.action ?? "install",
        status: m.state.status,
        error: m.state.error,
        result: (m.state.data as Extension | null | undefined) ?? null,
      } satisfies ExtensionActionState,
    }),
  });
  return computed(() => {
    const byPkg: Record<string, ExtensionActionState> = {};
    // oldest first, so the latest action of a package wins
    for (const { request, state } of all.value) {
      if (request) byPkg[request.pkgName] = state;
    }
    return byPkg;
  });
}

/** Trusts the signing certificate of an installed extension, which loads it (and others signed with it). */
export function useTrustExtension() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (extension: Pick<Extension, "pkgName" | "type">) =>
      trustExtension(extension.pkgName),
    onSuccess: (_data, extension) => invalidateKind(queryClient, mediaKindOf(extension.type)),
  });
}
