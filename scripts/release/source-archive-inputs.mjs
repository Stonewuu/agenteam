import {createHash} from "node:crypto";
import {closeSync, lstatSync, openSync, readFileSync, readSync, readdirSync} from "node:fs";
import {dirname, resolve} from "node:path";

const validHash = (value) => /^[a-f0-9]{64}$/.test(value ?? "");
const validVersion = (value) => /^\d+\.\d+\.\d+(?:-[a-z0-9.-]+)?$/.test(value ?? "");

export function verifySourcePath(file) {
  let current = resolve(file);
  while (true) {
    try {
      if (lstatSync(current).isSymbolicLink()) {
        throw new Error("源码附件及其父目录不能使用链接。");
      }
    } catch (failure) {
      if (failure.code !== "ENOENT") {
        throw failure;
      }
    }
    const parent = dirname(current);
    if (parent === current) {
      break;
    }
    current = parent;
  }
}

export function sourceFileStat(file) {
  verifySourcePath(file);
  const stat = lstatSync(file);
  if (!stat.isFile()) {
    throw new Error("源码附件必须是普通文件。");
  }
  return stat;
}

/** 分块计算大型源码附件的摘要，不把整个文件同时读入内存。 */
export function sourceFileHash(file) {
  sourceFileStat(file);
  const hash = createHash("sha256");
  const buffer = Buffer.alloc(1024 * 1024);
  const handle = openSync(file, "r");
  try {
    let count;
    while ((count = readSync(handle, buffer, 0, buffer.length, null)) > 0) {
      hash.update(buffer.subarray(0, count));
    }
  } finally {
    closeSync(handle);
  }
  return hash.digest("hex");
}

export function sourceDownloadUrl(version, name) {
  if (!validVersion(version) || !/^[a-zA-Z0-9][a-zA-Z0-9.-]+$/.test(name)) {
    throw new Error("源码下载地址需要明确版本及附件文件名。");
  }
  return `https://github.com/Stonewuu/agenteam/releases/download/v${version}/${name}`;
}

export function isSourceArchiveName(version, name) {
  if (!validVersion(version) || typeof name !== "string") {
    return false;
  }
  const escapedVersion = version.replaceAll(".", "\\.");
  return new RegExp(`^agenteam-third-party-sources-[a-z0-9][a-z0-9-]*-${escapedVersion}\\.tar$`).test(name);
}

export function sourceImageIdentities(images) {
  if (!Array.isArray(images) || !images.length) {
    throw new Error("源码附件缺少实际运行镜像的对应记录。");
  }
  const identities = new Map();
  for (const image of images) {
    if (!image || !["community", "pro"].includes(image.edition) || !["backend", "frontend", "executor", "office"].includes(image.kind)
      || !/^sha256:[a-f0-9]{64}$/.test(image.imageId ?? "")) {
      throw new Error("源码附件登记的镜像类型或内容编号不正确。");
    }
    const key = `${image.edition}/${image.kind}`;
    if (identities.has(key)) {
      throw new Error("源码附件中存在重复的镜像对应记录。");
    }
    identities.set(key, image.imageId);
  }
  return [...identities].sort(([left], [right]) => left.localeCompare(right));
}

/** 原始整理记录仅供校验；对外索引只保留版本、镜像、文件和下载地址。 */
export function sourceCatalog(manifest) {
  return {formatVersion: 1, version: manifest.version,
    description: "本版本第三方源码、原补丁及构建资料。各组件继续适用其自身许可。",
    runtimeImages: manifest.runtimeImages.map(({edition, kind, imageId}) => ({edition, kind, imageId})),
    archives: manifest.archives.map(({file, sha256, bytes}) => ({name: file, sha256, bytes,
      url: sourceDownloadUrl(manifest.version, file)}))};
}

export function readSourceArchiveInputs(directory, {version, runtimeImages}) {
  const root = resolve(directory);
  const file = resolve(root, "source-archives.json");
  if (sourceFileStat(file).size > 8 * 1024 * 1024) {
    throw new Error("源码附件清单超过允许的大小。");
  }
  const raw = readFileSync(file);
  const manifest = JSON.parse(raw);
  if (!validVersion(version) || !manifest || manifest.formatVersion !== 1 || manifest.complete !== true || manifest.version !== version
    || !Array.isArray(manifest.archives) || !manifest.archives.length) {
    throw new Error("源码附件未完成、版本不一致或清单不正确。");
  }
  if (JSON.stringify(sourceImageIdentities(manifest.runtimeImages)) !== JSON.stringify(sourceImageIdentities(runtimeImages))) {
    throw new Error("源码附件不对应本批实际运行镜像，请重新核对最终成品。");
  }
  const names = new Set(["source-archives.json"]);
  for (const archive of manifest.archives) {
    if (!archive || !isSourceArchiveName(version, archive.file) || names.has(archive.file)
      || !validHash(archive.sha256) || !Number.isSafeInteger(archive.bytes) || archive.bytes <= 0
      || archive.bytes >= 2 ** 31 || !Number.isSafeInteger(archive.sourceFilesVerified) || archive.sourceFilesVerified <= 0
      || !validHash(archive.indexSha256)) {
      throw new Error("源码附件文件名、大小或内容摘要不正确。");
    }
    names.add(archive.file);
    const path = resolve(root, archive.file);
    if (sourceFileStat(path).size !== archive.bytes || sourceFileHash(path) !== archive.sha256) {
      throw new Error(`源码附件原文件与已经核对的清单不同：${archive.file}`);
    }
  }
  if (readdirSync(root).sort().join("\n") !== [...names].sort().join("\n")) {
    throw new Error("源码附件目录有缺少或未登记的内容，不能整体加入发布。");
  }
  return {root, raw, manifest, sha256: createHash("sha256").update(raw).digest("hex"), catalog: sourceCatalog(manifest)};
}
