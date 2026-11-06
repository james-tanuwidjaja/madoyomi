/** Every install and update step, of every extension (no `pkgName` filter: the app follows all of them). */
export const EXTENSION_INSTALL_PROGRESS_SUBSCRIPTION = /* GraphQL */ `
  subscription ExtensionInstallProgress {
    extensionInstallProgress {
      pkgName
      type
      state
      errorCode
      message
    }
  }
`;

/** One event per repo refresh (`fetchExtensions`, the startup refresh), of both kinds. */
export const EXTENSION_STORE_REFRESHED_SUBSCRIPTION = /* GraphQL */ `
  subscription ExtensionStoreRefreshed {
    extensionStoreRefreshed {
      indexUrl
      type
      ok
      error
      extensionCount
    }
  }
`;
