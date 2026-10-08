import {createHash} from "node:crypto";
import {lstatSync, readFileSync, readdirSync} from "node:fs";
import {dirname, resolve} from "node:path";

const checksum = (value) => createHash("sha256").update(value).digest("hex");

function regular(file) {
  let current = resolve(file);
  while (true) {
    if (lstatSync(current).isSymbolicLink()) {
      throw new Error("第三方资料不能经由文件或目录链接读取其他内容。");
    }
    const parent = dirname(current);
    if (parent === current) {
      break;
    }
    current = parent;
  }
  if (!lstatSync(file).isFile()) {
    throw new Error("第三方资料必须是普通文件。");
  }
  return readFileSync(file);
}

/** 只复制索引及其逐字节核对的原文，不把资料目录中的其他文件一起交付。 */
export function readThirdPartyInputs(directory) {
  const root = resolve(directory);
  const raw = regular(resolve(root, "index.json"));
  const index = JSON.parse(raw);
  if (index.formatVersion !== 1 || !Array.isArray(index.components) || !index.components.length
    || !Array.isArray(index.withoutAttributions) || index.withoutAttributions.length
    || !/^[a-f0-9]{64}$/.test(index.applicationArtifact?.sha256 ?? "")) {
    throw new Error("第三方资料索引不完整，或尚未记录对应的后端内容摘要。");
  }
  const files = new Map([["index.json", checksum(raw)], ["README.md", checksum(regular(resolve(root, "README.md")))]]);
  for (const component of index.components) {
    if (!Array.isArray(component.notices) || !component.notices.length) {
      throw new Error("第三方资料中仍有未附任何原始信息的组件。");
    }
    for (const notice of component.notices) {
      if (!/^notices\/[a-f0-9]{64}\.txt$/.test(notice.file) || notice.file !== `notices/${notice.sha256}.txt`) {
        throw new Error("第三方原文路径或摘要格式不正确。");
      }
      if (!files.has(notice.file)) {
        if (checksum(regular(resolve(root, notice.file))) !== notice.sha256) {
          throw new Error("第三方原文与索引中的内容摘要不一致。");
        }
        files.set(notice.file, notice.sha256);
      }
    }
  }
  const actual = [];
  const walk = (folder, prefix = "") => {
    for (const entry of readdirSync(folder, {withFileTypes: true})) {
      const name = prefix + entry.name;
      if (entry.isSymbolicLink()) {
        throw new Error("第三方资料目录中不能含有链接。");
      }
      if (entry.isDirectory()) {
        if (name !== "notices") {
          throw new Error("第三方资料目录中包含未登记的目录。");
        }
        walk(resolve(folder, entry.name), `${name}/`);
      } else {
        actual.push(name);
      }
    }
  };
  walk(root);
  if (index.uniqueNoticeFiles !== files.size - 2 || actual.sort().join("\n") !== [...files.keys()].sort().join("\n")) {
    throw new Error("第三方资料有缺少或未登记的文件，不能放入镜像。");
  }
  return {root, index, indexSha256: checksum(raw), files};
}

function verifyFiles(bundle, family, directory, names, node = false) {
  const binding = bundle.index.inputBindings?.[family];
  if (node && (binding?.platform !== "linux" || binding?.architecture !== "x64")) {
    throw new Error("本批镜像的 Node 依赖声明必须来自 Linux x64 构建环境。");
  }
  for (const name of names) {
    const actual = checksum(regular(resolve(directory, name)).toString("utf8").replaceAll("\r\n", "\n"));
    if (binding?.files?.[name] !== actual) {
      throw new Error(`第三方资料与当前依赖文件不一致：${family}/${name}`);
    }
  }
}

/** 后端绑定实际文件，前端和办公依赖绑定产生原文报告的锁文件。正式许可审核仍由发布检查承担。 */
export function verifyThirdPartyInputs({product, shared, backend, frontend, office}) {
  if (!shared) {
    throw new Error("缺少两版办公镜像共用的第三方资料。");
  }
  if (product.index.applicationArtifact.sha256 !== checksum(regular(backend))) {
    throw new Error("第三方资料对应另一个后端文件，请按当前产物重新收集。");
  }
  verifyFiles(product, "frontend", frontend, ["pnpm-lock.yaml", "pnpm-workspace.yaml"], true);
  verifyFiles(shared, "office-node", office, ["package-lock.json"], true);
  verifyFiles(shared, "office-python", office, ["requirements.txt", "requirements.lock"]);
}
