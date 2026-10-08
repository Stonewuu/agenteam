import { readFileSync, statSync, existsSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import ts from "typescript";

const sourceRoot = fileURLToPath(new URL("../src/", import.meta.url));
const require = createRequire(import.meta.url);
const cache = new Map();

/** 加载实际语言实现，测试中的组件替身只替换上下文入口。 */
export function loadSource(name) {
  const base = resolve(sourceRoot, name);
  const file = [base, `${base}.ts`, `${base}.tsx`, `${base}.json`].find(file => existsSync(file) && statSync(file).isFile());
  if (!file) {
    throw new Error(`找不到测试源码：${name}`);
  }
  if (cache.has(file)) {
    return cache.get(file).exports;
  }
  const loaded = { exports: {} };
  cache.set(file, loaded);
  if (file.endsWith(".json")) {
    loaded.exports = JSON.parse(readFileSync(file, "utf8"));
    return loaded.exports;
  }
  const compiled = ts.transpileModule(readFileSync(file, "utf8"), { compilerOptions: {
    target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX, esModuleInterop: true,
  } }).outputText;
  const localRequire = name => {
    if (name.startsWith("@/")) {
      return loadSource(name.slice(2));
    }
    if (name.startsWith(".")) {
      return loadSource(resolve(dirname(file), name));
    }
    return require(name);
  };
  new Function("require", "module", "exports", compiled)(localRequire, loaded, loaded.exports);
  return loaded.exports;
}

export const { createTranslator, localizeCatalog } = loadSource("lib/i18n/translate");
export const chineseText = createTranslator("zh-CN");
