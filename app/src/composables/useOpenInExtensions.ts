import { useRouter } from "vue-router";
import { useExtensionFiltersStore } from "../stores/extensionFilters";

/**
 * Opens Extensions on its Installed tab, searched for one package: where an untrusted extension's Trust button, or
 * an unloadable one's error, is. Browse and the source page use it for a source the backend can't use.
 */
export function useOpenInExtensions() {
  const filters = useExtensionFiltersStore();
  const router = useRouter();
  return (pkgName: string) => {
    filters.tab = "installed";
    filters.lang = "";
    filters.search = pkgName;
    return router.push("/extensions");
  };
}
