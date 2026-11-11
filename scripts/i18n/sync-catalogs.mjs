import {readFileSync, writeFileSync, mkdirSync, existsSync} from "node:fs";
import {dirname, resolve} from "node:path";
import {fileURLToPath} from "node:url";

const root = fileURLToPath(new URL("../../", import.meta.url));

/** 前端和后端共用原始目录，生成文件只用于打包，不另行维护文案副本。 */
export function readMessageCatalog(server) {
  const resources = resolve(server, "src/main/resources");
  const index = resolve(resources, "META-INF/agenteam/messages.index");
  const messages = {};
  for (const name of readFileSync(index, "utf8").split(/\r?\n/).map(line => line.trim()).filter(line => line && !line.startsWith("#"))) {
    if (!/^i18n\/[A-Za-z0-9_/-]+\.json$/.test(name)) {
      throw new Error(`消息资源路径无效：${name}`);
    }
    const entries = JSON.parse(readFileSync(resolve(resources, name), "utf8"));
    for (const [key, translations] of Object.entries(entries)) {
      if (Object.hasOwn(messages, key)) {
        throw new Error(`消息代码重复：${key}`);
      }
      for (const locale of ["zh-CN", "en"]) {
        if (typeof translations[locale] !== "string" || !translations[locale].trim()) {
          throw new Error(`缺少翻译：${key}/${locale}`);
        }
      }
      const parameters = text => [...new Set(text.match(/\{\w+\}/g) ?? [])].sort();
      if (JSON.stringify(parameters(translations["zh-CN"])) !== JSON.stringify(parameters(translations.en))) {
        throw new Error(`消息参数不一致：${key}`);
      }
      messages[key] = translations;
    }
  }
  return messages;
}

export function frontendMessages(messages) {
  const entries = Object.entries(messages).filter(([key]) => !/^(?:pro\.)?(?:log|protocol)\./.test(key))
    .sort(([left], [right]) => left.localeCompare(right, "en"));
  return {
    "zh-CN": Object.fromEntries(entries.map(([key, value]) => [key, value["zh-CN"]])),
    en: Object.fromEntries(entries.map(([key, value]) => [key, value.en])),
    legacy: Object.fromEntries(entries.filter(([key]) => !key.startsWith("label."))
      .map(([, value]) => [value["zh-CN"], value.en])),
  };
}

export function writeCatalog(file, value, check) {
  const expected = JSON.stringify(value, null, 2) + "\n";
  if (check) {
    if (!existsSync(file) || readFileSync(file, "utf8").replaceAll("\r\n", "\n") !== expected) {
      throw new Error(`生成目录与来源不同，请运行翻译同步命令：${file}`);
    }
    return;
  }
  mkdirSync(dirname(file), {recursive: true});
  writeFileSync(file, expected);
}

if (resolve(process.argv[1] ?? "") === fileURLToPath(import.meta.url)) {
  const check = process.argv.includes("--check");
  const output = resolve(root, "agenteam-web/src/lib/i18n/generated");
  writeCatalog(resolve(output, "backend.json"), frontendMessages(readMessageCatalog(resolve(root, "agenteam-server"))), check);
  writeCatalog(resolve(output, "builtin-plugins.json"), JSON.parse(readFileSync(resolve(root,
    "agenteam-server/src/main/resources/catalog/builtin-plugins.json"), "utf8")), check);
  console.log(check ? "前后端语言资源一致。" : "前端语言资源已从后端目录生成。");
}
