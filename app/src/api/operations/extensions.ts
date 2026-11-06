import { api } from "../client";
import type {
  Extension,
  ExtensionAction,
  ExtensionKind,
  ExtensionStore,
  MediaKind,
  RepoIndexFormat,
  TrustedSignature,
} from "../types";
import { extensionKindOf } from "../types";

export const EXTENSION_STORE_FIELDS = /* GraphQL */ `
  fragment ExtensionStoreFields on ExtensionStoreType {
    indexUrl
    name
    badgeLabel
    type
    format
    signingKey
    signingKeyTrusted
    lastRefreshedAt
    lastError
    contactWebsite
    extensions {
      totalCount
    }
  }
`;

export const EXTENSION_FIELDS = /* GraphQL */ `
  fragment ExtensionFields on ExtensionType {
    pkgName
    name
    type
    lang
    versionName
    versionCodeLong
    contentWarning
    iconUrl
    storeIndexUrl
    isInstalled
    hasUpdate
    isObsolete
    isLoaded
    isTorrent
    signatureHash
    isTrusted
    compatIssues {
      kind
      target
      origin
      context
      count
    }
  }
`;

export const EXTENSION_STORES_QUERY = /* GraphQL */ `
  query ExtensionStores {
    extensionStores {
      nodes {
        ...ExtensionStoreFields
      }
    }
  }
  ${EXTENSION_STORE_FIELDS}
`;

export const EXTENSIONS_QUERY = /* GraphQL */ `
  query Extensions($type: ExtensionKind) {
    extensions(condition: { type: $type }) {
      nodes {
        ...ExtensionFields
      }
    }
  }
  ${EXTENSION_FIELDS}
`;

/** Refreshes the repos (`fetchExtensions`); each repo's result also arrives as `extensionStoreRefreshed`. */
export const REFRESH_REPOS_MUTATION = /* GraphQL */ `
  mutation RefreshRepos($type: ExtensionKind) {
    fetchExtensions(input: { type: $type }) {
      extensionStores {
        ...ExtensionStoreFields
      }
    }
  }
  ${EXTENSION_STORE_FIELDS}
`;

export async function fetchExtensionStores(signal?: AbortSignal): Promise<ExtensionStore[]> {
  const data = await api.gql<{ extensionStores: { nodes: ExtensionStore[] } }>(
    EXTENSION_STORES_QUERY,
    {},
    { signal },
  );
  return data.extensionStores.nodes;
}

/** Every listed extension of `kind`, or of both kinds. */
export async function fetchExtensions(
  kind?: MediaKind,
  signal?: AbortSignal,
): Promise<Extension[]> {
  const type: ExtensionKind | null = kind ? extensionKindOf(kind) : null;
  const data = await api.gql<{ extensions: { nodes: Extension[] } }>(
    EXTENSIONS_QUERY,
    { type },
    { signal },
  );
  return data.extensions.nodes;
}

export async function refreshRepos(kind?: MediaKind): Promise<ExtensionStore[]> {
  const data = await api.gql<{ fetchExtensions: { extensionStores: ExtensionStore[] } }>(
    REFRESH_REPOS_MUTATION,
    { type: kind ? extensionKindOf(kind) : null },
  );
  return data.fetchExtensions.extensionStores;
}

/**
 * Adds (or re-adds) a repo of one kind. The server fetches it, checks that every package is of that kind
 * (`REPO_TYPE_MISMATCH` otherwise, with `details.packages`), trusts its signing key and lists its extensions. The
 * stored URL is where the index resolved to, which can differ from `indexUrl`.
 */
export const ADD_REPO_MUTATION = /* GraphQL */ `
  mutation AddRepo($indexUrl: String!, $type: ExtensionKind, $format: RepoIndexFormat) {
    addExtensionStore(input: { indexUrl: $indexUrl, type: $type, format: $format }) {
      extensionStore {
        ...ExtensionStoreFields
      }
    }
  }
  ${EXTENSION_STORE_FIELDS}
`;

/** Removes the repo stored under `indexUrl`; the payload is the repo as it was. */
export const REMOVE_REPO_MUTATION = /* GraphQL */ `
  mutation RemoveRepo($indexUrl: String!) {
    removeExtensionStore(input: { indexUrl: $indexUrl }) {
      extensionStore {
        indexUrl
        type
      }
    }
  }
`;

/**
 * Installs, updates or uninstalls one extension. It resolves when the work is done (the steps arrive meanwhile as
 * `extensionInstallProgress`), and fails with what it couldn't do (`NOT_FOUND`, `INSTALL_FAILED`, `SIGNATURE_*`, …).
 */
export const UPDATE_EXTENSION_MUTATION = /* GraphQL */ `
  mutation UpdateExtension(
    $pkgName: String!
    $install: Boolean
    $update: Boolean
    $uninstall: Boolean
  ) {
    updateExtension(
      input: { id: $pkgName, patch: { install: $install, update: $update, uninstall: $uninstall } }
    ) {
      extension {
        ...ExtensionFields
      }
    }
  }
  ${EXTENSION_FIELDS}
`;

/** Trusts the signing certificate of an installed extension (either kind) and loads what it signs. */
export const TRUST_EXTENSION_MUTATION = /* GraphQL */ `
  mutation TrustExtension($pkgName: String!) {
    trustExtension(input: { pkgName: $pkgName }) {
      extension {
        ...ExtensionFields
      }
      trustedSignature {
        hash
        origin
      }
    }
  }
  ${EXTENSION_FIELDS}
`;

export interface AddRepoInput {
  indexUrl: string;
  kind: MediaKind;
  format?: RepoIndexFormat;
}

export async function addRepo(input: AddRepoInput): Promise<ExtensionStore> {
  const data = await api.gql<{ addExtensionStore: { extensionStore: ExtensionStore } }>(
    ADD_REPO_MUTATION,
    {
      indexUrl: input.indexUrl,
      type: extensionKindOf(input.kind),
      format: input.format ?? null,
    },
  );
  return data.addExtensionStore.extensionStore;
}

export async function removeRepo(indexUrl: string): Promise<void> {
  await api.gql(REMOVE_REPO_MUTATION, { indexUrl });
}

/** The extension after the action, or null if the action removed its row (an obsolete extension uninstalled). */
export async function updateExtension(
  pkgName: string,
  action: ExtensionAction,
): Promise<Extension | null> {
  const data = await api.gql<{ updateExtension: { extension: Extension | null } }>(
    UPDATE_EXTENSION_MUTATION,
    { pkgName, [action]: true },
  );
  return data.updateExtension.extension;
}

export async function trustExtension(
  pkgName: string,
): Promise<{ extension: Extension; trustedSignature: TrustedSignature | null }> {
  const data = await api.gql<{
    trustExtension: { extension: Extension; trustedSignature: TrustedSignature | null };
  }>(TRUST_EXTENSION_MUTATION, { pkgName });
  return data.trustExtension;
}
