import {createHash} from "node:crypto";
import {existsSync, readFileSync, readdirSync, realpathSync, statSync} from "node:fs";
import {dirname, resolve} from "node:path";
import {parseArgs} from "node:util";

const {values} = parseArgs({options: {root: {type: "string", default: process.cwd()}}});
const root = realpathSync(values.root);
const visited = new Set();
const packages = new Map();
const missing = [];

function locate(from, name) {
  if (!/^(?:@[a-z0-9._-]+\/)?[a-z0-9._-]+$/i.test(name)) {
    throw new Error("依赖清单包含不符合包名称规则的路径。");
  }
  let current = from;
  while (true) {
    const candidate = resolve(current, "node_modules", name, "package.json");
    if (existsSync(candidate)) {
      return realpathSync(dirname(candidate));
    }
    const parent = dirname(current);
    if (parent === current) {
      return null;
    }
    current = parent;
  }
}

function attributionFiles(directory, packageName) {
  const entries = [];
  const walk = (folder, prefix = "", depth = 0, includeAll = false) => {
    for (const entry of readdirSync(folder, {withFileTypes: true})) {
      const name = prefix + entry.name;
      const file = resolve(folder, entry.name);
      if (entry.isSymbolicLink()) {
        continue;
      }
      if (entry.isDirectory()) {
        const legal = /^(licen[cs]es?|notices?|copyrights?)$/i.test(entry.name);
        if (depth < 6 && (includeAll || legal || name === "dist" || name.startsWith("dist/compiled"))) {
          walk(file, name + "/", depth + 1, includeAll || legal);
        }
        continue;
      }
      const native = packageName.startsWith("@img/sharp-libvips-") && ["README.md", "versions.json"].includes(name);
      const readme = /^readme(?:[._-].*)?$/i.test(entry.name);
      if (!includeAll && !native && !readme && !/^(license|licence|copying|notice|copyright|authors)(?:[._-].*)?$/i.test(entry.name)
        && !/^third[-_ ]?party.*(?:\.(txt|md))?$/i.test(entry.name)) {
        continue;
      }
      const stat = statSync(file);
      if (!stat.isFile() || stat.size > 1024 * 1024) {
        continue;
      }
      const content = readFileSync(file, "utf8");
      if (readme && !native && !includeAll && !/^#{1,6}\s+(?:licen[cs]e|copyright)\b/im.test(content)) {
        continue;
      }
      entries.push({name, sha256: createHash("sha256").update(content).digest("hex"), content});
    }
  };
  walk(directory);
  return entries;
}

function walk(directory, own = false) {
  if (visited.has(directory)) {
    return;
  }
  visited.add(directory);
  const data = JSON.parse(readFileSync(resolve(directory, "package.json"), "utf8"));
  const key = `${data.name}@${data.version}`;
  if (!own && !packages.has(key)) {
    const license = typeof data.license === "string" ? data.license : data.license?.type
      ?? data.licenses?.map((entry) => typeof entry === "string" ? entry : entry.type).join(" OR ") ?? "UNKNOWN";
    packages.set(key, {name: data.name, version: data.version, license,
      homepage: data.homepage ?? null, repository: typeof data.repository === "string" ? data.repository : data.repository?.url ?? null,
      attributions: attributionFiles(directory, data.name)});
  }
  const names = new Set([...Object.keys(data.dependencies ?? {}), ...Object.keys(data.optionalDependencies ?? {}),
    ...Object.keys(data.peerDependencies ?? {}), ...(Array.isArray(data.bundledDependencies) ? data.bundledDependencies : [])]);
  for (const name of names) {
    const found = locate(directory, name);
    if (found) {
      walk(found);
    } else if (!Object.hasOwn(data.optionalDependencies ?? {}, name) && !data.peerDependenciesMeta?.[name]?.optional) {
      missing.push({owner: key, dependency: name});
    }
  }
}

walk(root, true);
const items = [...packages.values()].sort((left, right) => `${left.name}@${left.version}`.localeCompare(`${right.name}@${right.version}`));
const inputFiles = {};
for (const name of ["pnpm-lock.yaml", "pnpm-workspace.yaml", "package-lock.json"]) {
  if (existsSync(resolve(root, name))) {
    inputFiles[name] = createHash("sha256").update(readFileSync(resolve(root, name), "utf8").replaceAll("\r\n", "\n")).digest("hex");
  }
}
process.stdout.write(JSON.stringify({formatVersion: 1, platform: process.platform, architecture: process.arch,
  scope: "从实际安装的生产依赖及其已安装对等依赖递归收集，包含原许可文件；需再核对最终捆绑范围与许可义务",
  inputFiles, packages: items, missing}, null, 2) + "\n");
