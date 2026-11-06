import { api } from "../client";
import type { Extension, ExtensionKind, ExtensionStore, MediaKind } from "../types";
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
