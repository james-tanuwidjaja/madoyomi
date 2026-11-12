import {
  createRouter,
  createWebHistory,
  type RouteRecordRaw,
  type RouterHistory,
} from "vue-router";
import type { MediaKind } from "../api/types";
import { useMediaKindStore } from "../stores/mediaKind";

/** The sidebar items (src/components/AppShell.vue). */
export type NavItem = "library" | "browse" | "extensions" | "downloads" | "settings";

declare module "vue-router" {
  interface RouteMeta {
    /** The page title (and the document title). */
    title?: string;
    /** The sidebar item to mark active. */
    nav?: NavItem;
    /**
     * A page reached from several sections (a details page, from Browse or the Library) keeps marking the section it
     * was opened from; `nav` is only the default, for a page opened directly.
     */
    navFromPrevious?: boolean;
    /** The route's own kind: opening it sets the anime/manga switch. */
    kind?: MediaKind;
    /** Where to go when the switch changes to another kind: the route only makes sense for one kind. */
    kindScopedTo?: string;
    /** Shown without the app shell (the reader takes the whole window). */
    bare?: boolean;
  }
}

/**
 * Phase 3 step 10's routes, plus `/downloads` (the sidebar's placeholder), a not-found page and, since Phase 5, the
 * reader (`/manga/:id/read/:chapterId?page=N`, without the shell).
 */
export const routes: RouteRecordRaw[] = [
  { path: "/", redirect: "/library" },
  {
    path: "/library",
    name: "library",
    component: () => import("../views/LibraryView.vue"),
    meta: { title: "Library", nav: "library" },
  },
  {
    path: "/browse",
    name: "browse",
    component: () => import("../views/BrowseView.vue"),
    meta: { title: "Browse", nav: "browse" },
  },
  {
    path: "/browse/:sourceId",
    name: "source",
    component: () => import("../views/SourceView.vue"),
    props: true,
    meta: { title: "Source", nav: "browse", kindScopedTo: "/browse" },
  },
  {
    path: "/anime/:id(\\d+)",
    name: "anime",
    component: () => import("../views/AnimeDetailsView.vue"),
    props: (route) => ({ id: Number(route.params.id) }),
    meta: {
      title: "Anime",
      nav: "library",
      navFromPrevious: true,
      kind: "anime",
      kindScopedTo: "/library",
    },
  },
  {
    path: "/manga/:id(\\d+)",
    name: "manga",
    component: () => import("../views/MangaDetailsView.vue"),
    props: (route) => ({ id: Number(route.params.id) }),
    meta: {
      title: "Manga",
      nav: "library",
      navFromPrevious: true,
      kind: "manga",
      kindScopedTo: "/library",
    },
  },
  {
    path: "/manga/:id(\\d+)/read/:chapterId(\\d+)",
    name: "reader",
    component: () => import("../views/ReaderView.vue"),
    props: (route) => ({ id: Number(route.params.id), chapterId: Number(route.params.chapterId) }),
    meta: { title: "Reader", kind: "manga", bare: true },
  },
  {
    path: "/extensions",
    name: "extensions",
    component: () => import("../views/ExtensionsView.vue"),
    meta: { title: "Extensions", nav: "extensions" },
  },
  {
    path: "/repos",
    name: "repos",
    component: () => import("../views/ReposView.vue"),
    meta: { title: "Repos", nav: "extensions" },
  },
  {
    path: "/downloads",
    name: "downloads",
    component: () => import("../views/DownloadsView.vue"),
    meta: { title: "Downloads", nav: "downloads" },
  },
  {
    path: "/settings",
    name: "settings",
    component: () => import("../views/SettingsView.vue"),
    meta: { title: "Settings", nav: "settings" },
  },
  {
    path: "/settings/source/:id",
    name: "source-settings",
    component: () => import("../views/SourceSettingsView.vue"),
    props: true,
    meta: { title: "Source settings", nav: "settings", kindScopedTo: "/settings" },
  },
  {
    path: "/:pathMatch(.*)*",
    name: "not-found",
    component: () => import("../views/NotFoundView.vue"),
    meta: { title: "Not found" },
  },
];

export function createAppRouter(history: RouterHistory = createWebHistory()) {
  const router = createRouter({ history, routes });
  router.beforeEach((to) => {
    // `/anime/:id` and `/manga/:id` switch the sidebar to their kind.
    if (to.meta.kind) useMediaKindStore().set(to.meta.kind);
  });
  router.afterEach((to) => {
    document.title = to.meta.title ? `${to.meta.title} · Madoyomi` : "Madoyomi";
  });
  return router;
}

export const router = createAppRouter();
