import { defineConfig, globalIgnores } from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";

const eslintConfig = defineConfig([
  ...nextVitals,
  ...nextTs,
  {
    rules: {
      curly: ["error", "all"],
      "brace-style": ["error", "1tbs", { allowSingleLine: false }],
    },
  },
  // 仅检查维护源码，排除构建与测试生成文件。
  globalIgnores([
    ".test-build/**",
    ".next/**",
    ".next-*/**",
    "out/**",
    "build/**",
    "next-env.d.ts",
  ]),
]);

export default eslintConfig;
