import { globalIgnores } from "eslint/config";
import { defineConfigWithVueTs, vueTsConfigs } from "@vue/eslint-config-typescript";
import pluginVue from "eslint-plugin-vue";
import skipFormatting from "@vue/eslint-config-prettier/skip-formatting";

export default defineConfigWithVueTs(
  { name: "app/files-to-lint", files: ["**/*.{ts,mts,tsx,vue}"] },
  globalIgnores(["**/dist/**", "**/coverage/**", "src-tauri/**"]),
  pluginVue.configs["flat/recommended"],
  vueTsConfigs.recommended,
  // Prettier owns formatting; ESLint only checks code quality.
  skipFormatting,
);
