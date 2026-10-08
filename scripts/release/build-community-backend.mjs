import {mkdirSync, readFileSync, writeFileSync} from "node:fs";
import {homedir} from "node:os";
import {resolve} from "node:path";
import {parseArgs} from "node:util";
import {communityRoot, communityBuildPath, readCommunitySource, checksum} from "./community-backend.mjs";
import {runMaven} from "./maven-command.mjs";

const {values} = parseArgs({options: {release: {type: "boolean", default: false},
  "skip-tests": {type: "boolean", default: false}, "local-cache": {type: "boolean", default: false}}});
if (values.release && (values["skip-tests"] || values["local-cache"])) {
  throw new Error("正式社区构建必须执行测试并使用独立的 Maven 依赖缓存。");
}
const source = readCommunitySource(communityRoot, {release: values.release});
const output = communityBuildPath(source.root, ".build/backend");
const artifactsDirectory = communityBuildPath(source.root, ".build/backend/artifacts");
const recordPath = communityBuildPath(source.root, ".build/backend/build-inputs.json");
const repository = values["local-cache"] ? resolve(homedir(), ".m2/repository")
  : communityBuildPath(source.root, ".build/maven-repository");
mkdirSync(output, {recursive: true});
const record = {formatVersion: 1, version: source.version, communityCommit: source.commit, sourceDirty: source.dirty,
  release: values.release, testsExecuted: false, status: "building"};
const save = () => writeFileSync(recordPath, JSON.stringify(record, null, 2) + "\n");
save();
try {
  const args = ["-B", "--no-transfer-progress", `-Dmaven.repo.local=${repository}`,
    `-Dagenteam.build.directory=${artifactsDirectory}`];
  if (values["skip-tests"]) {
    args.push("-DskipTests");
  }
  await runMaven(resolve(source.root, "agenteam-server"), [...args, "clean", "package"]);
  const after = readCommunitySource(source.root, {release: values.release});
  if (after.commit !== source.commit || after.version !== source.version) {
    throw new Error("构建期间社区提交或版本发生变化，不能登记为完成。");
  }
  record.artifacts = {};
  for (const [name, suffix] of [["community", ""], ["communityExecutable", "-exec"]]) {
    const file = `agenteam-${source.version}${suffix}.jar`;
    record.artifacts[name] = {file, sha256: checksum(readFileSync(resolve(artifactsDirectory, file)))};
  }
  record.testsExecuted = !values["skip-tests"];
  record.status = "complete";
  save();
  console.log(`独立社区后端构建完成：${recordPath}`);
} catch (failure) {
  record.status = "failed";
  save();
  console.error("独立社区后端构建失败：", failure);
  process.exitCode = 1;
}
