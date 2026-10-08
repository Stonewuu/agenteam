import {execFileSync} from "node:child_process";
import {createHash} from "node:crypto";
import {existsSync, lstatSync, readFileSync, realpathSync} from "node:fs";
import {resolve} from "node:path";
import {fileURLToPath} from "node:url";

export const communityRoot = fileURLToPath(new URL("../../", import.meta.url));
export const checksum = (bytes) => createHash("sha256").update(bytes).digest("hex");
const git = (root, args) => execFileSync("git", ["-C", root, ...args], {encoding: "utf8", maxBuffer: 16 * 1024 * 1024}).trim();

/** 输出及清理位置固定在本仓库内，不能通过目录链接指向其他工作区。 */
export function communityBuildPath(root, name) {
  if (!/^\.build\/(?:backend(?:\/artifacts)?|maven-repository|backend\/build-inputs\.json)$/.test(name)) {
    throw new Error("社区构建只能使用规定的本地输出目录。");
  }
  let current = realpathSync(root);
  for (const part of name.split("/")) {
    current = resolve(current, part);
    if (lstatSync(current, {throwIfNoEntry: false})?.isSymbolicLink()) {
      throw new Error("社区构建输出不能经过目录或文件链接。");
    }
  }
  return current;
}

export function readCommunitySource(root = communityRoot, {release = false} = {}) {
  const directory = realpathSync(root);
  if (realpathSync(git(directory, ["rev-parse", "--show-toplevel"])) !== directory) {
    throw new Error("请从独立社区代码仓库执行构建。");
  }
  const web = JSON.parse(readFileSync(resolve(directory, "agenteam-web/package.json"), "utf8"));
  const pom = readFileSync(resolve(directory, "agenteam-server/pom.xml"), "utf8");
  const own = pom.includes("</parent>") ? pom.slice(pom.indexOf("</parent>") + 9) : pom;
  const backendVersion = own.match(/<version>([^<]+)<\/version>/)?.[1];
  const deployment = readFileSync(resolve(directory, "deploy/registry/release.env"), "utf8")
    .match(/^RELEASE_TAG=(.+)$/m)?.[1]?.trim();
  if (!/^\d+\.\d+\.\d+$/.test(web.version) || backendVersion !== web.version || deployment !== web.version) {
    throw new Error("社区前后端和部署版本不一致，构建已停止。");
  }
  const commit = git(directory, ["rev-parse", "HEAD"]);
  const dirty = Boolean(git(directory, ["status", "--porcelain"]));
  if (release && dirty) {
    throw new Error("正式社区构建要求工作区无未提交修改。");
  }
  return {root: directory, version: web.version, commit, dirty};
}

/** 文件名或相同应用版本不足以证明源码来源，必须同时核对构建记录与实际字节。 */
export function verifyCommunityBackend(root = communityRoot, {release = false, backend} = {}) {
  const source = readCommunitySource(root, {release});
  const recordPath = communityBuildPath(source.root, ".build/backend/build-inputs.json");
  if (!existsSync(recordPath)) {
    throw new Error("请先执行 node scripts/release/build-community-backend.mjs，生成独立社区后端构建记录。");
  }
  const record = JSON.parse(readFileSync(recordPath, "utf8"));
  if (record.formatVersion !== 1 || record.status !== "complete" || record.version !== source.version
    || record.communityCommit !== source.commit) {
    throw new Error("社区后端构建记录与当前提交或版本不一致，请重新构建。");
  }
  if (release && (record.release !== true || record.testsExecuted !== true || record.sourceDirty !== false)) {
    throw new Error("正式社区镜像需要已执行测试的正式后端构建记录。");
  }
  const directory = communityBuildPath(source.root, ".build/backend/artifacts");
  for (const [key, suffix] of [["community", ""], ["communityExecutable", "-exec"]]) {
    const expected = `agenteam-${source.version}${suffix}.jar`;
    const artifact = record.artifacts?.[key];
    if (artifact?.file !== expected || !/^[a-f0-9]{64}$/.test(artifact?.sha256 ?? "")) {
      throw new Error("社区构建记录包含不正确的产物名称或摘要。");
    }
    const path = resolve(directory, expected);
    if (!lstatSync(path).isFile() || lstatSync(path).isSymbolicLink() || checksum(readFileSync(path)) !== artifact.sha256) {
      throw new Error("社区后端产物已被替换，不能继续使用原构建记录。");
    }
  }
  const artifact = record.artifacts.communityExecutable;
  const selected = backend ? resolve(backend) : resolve(directory, artifact.file);
  if (checksum(readFileSync(selected)) !== artifact.sha256) {
    throw new Error("指定后端不是当前社区提交构建的可执行文件。");
  }
  return {source, backend: selected, record, recordSha256: checksum(readFileSync(recordPath))};
}
