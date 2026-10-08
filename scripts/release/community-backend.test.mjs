import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import {afterEach, test} from "node:test";
import {mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, symlinkSync, writeFileSync} from "node:fs";
import {dirname, resolve} from "node:path";
import {tmpdir} from "node:os";
import {checksum, communityBuildPath, readCommunitySource, verifyCommunityBackend} from "./community-backend.mjs";

const fixtures = [];
afterEach(() => {
  for (const root of fixtures.splice(0)) {
    if (dirname(realpathSync(root)) !== realpathSync(tmpdir()) || !root.includes("agenteam-community-build-")) {
      throw new Error("测试清理路径不符合当前临时目录要求。");
    }
    rmSync(root, {recursive: true});
  }
});

function fixture() {
  const root = mkdtempSync(resolve(tmpdir(), "agenteam-community-build-"));
  fixtures.push(root);
  const git = (...args) => execFileSync("git", ["-C", root, ...args], {encoding: "utf8", stdio: ["ignore", "pipe", "pipe"]}).trim();
  const write = (name, value) => {
    const path = resolve(root, name);
    mkdirSync(dirname(path), {recursive: true});
    writeFileSync(path, value);
    return path;
  };
  git("init", "--initial-branch=main");
  git("config", "core.autocrlf", "false");
  git("config", "user.name", "构建验证");
  git("config", "user.email", "verification@example.invalid");
  git("config", "commit.gpgsign", "false");
  write(".gitignore", "/.build/\n");
  write("agenteam-web/package.json", JSON.stringify({version: "0.2.0"}));
  write("agenteam-server/pom.xml", "<project><parent><version>4.1.1</version></parent><version>0.2.0</version></project>");
  write("deploy/registry/release.env", "RELEASE_TAG=0.2.0\n");
  git("add", ".");
  git("commit", "-m", "本地来源验证样本");
  const source = readCommunitySource(root, {release: true});
  const record = {formatVersion: 1, status: "complete", version: "0.2.0", communityCommit: source.commit,
    sourceDirty: false, release: true, testsExecuted: true, artifacts: {}};
  for (const [name, suffix] of [["community", ""], ["communityExecutable", "-exec"]]) {
    const file = `agenteam-0.2.0${suffix}.jar`;
    const path = write(`.build/backend/artifacts/${file}`, `只用于摘要校验的${name}文件`);
    record.artifacts[name] = {file, sha256: checksum(readFileSync(path))};
  }
  const save = () => write(".build/backend/build-inputs.json", JSON.stringify(record));
  save();
  return {root, git, write, record, save};
}

test("当前提交、正式测试标记及两个实际产物同时匹配才返回社区后端", () => {
  const value = fixture();
  const result = verifyCommunityBackend(value.root, {release: true});
  assert.equal(result.source.commit, value.record.communityCommit);
  assert.equal(result.backend, resolve(value.root, ".build/backend/artifacts/agenteam-0.2.0-exec.jar"));
  assert.equal(result.recordSha256, checksum(readFileSync(resolve(value.root, ".build/backend/build-inputs.json"))));
});

test("版本号相同但源码已有新提交时不能继续使用旧后端记录", () => {
  const value = fixture();
  value.write("agenteam-server/change.txt", "新的公共功能");
  value.git("add", ".");
  value.git("commit", "-m", "同版本的新源码");
  assert.throws(() => verifyCommunityBackend(value.root, {release: true}), /当前提交或版本不一致/);
});

test("未提交源码、开发记录、跳过测试和未完成构建均不能用于正式镜像", () => {
  const dirty = fixture();
  dirty.write("agenteam-server/new.txt", "尚未提交");
  assert.throws(() => verifyCommunityBackend(dirty.root, {release: true}), /未提交/);
  for (const [key, value] of [["release", false], ["testsExecuted", false], ["sourceDirty", true], ["status", "building"]]) {
    const sample = fixture();
    sample.record[key] = value;
    sample.save();
    assert.throws(() => verifyCommunityBackend(sample.root, {release: true}), /正式后端|构建记录/);
  }
});

test("产物替换、传入另一份后端和伪造相对路径都被拒绝", () => {
  const changed = fixture();
  changed.write(".build/backend/artifacts/agenteam-0.2.0-exec.jar", "其他内容");
  assert.throws(() => verifyCommunityBackend(changed.root, {release: true}), /产物已被替换/);
  const extra = fixture();
  const other = extra.write(".build/other.jar", "同版本的旧后端");
  assert.throws(() => verifyCommunityBackend(extra.root, {release: true, backend: other}), /不是当前社区提交/);
  extra.record.artifacts.communityExecutable.file = "../other.jar";
  extra.save();
  assert.throws(() => verifyCommunityBackend(extra.root, {release: true}), /产物名称/);
});

test("构建输出固定在社区目录内，拒绝越界和目录链接", () => {
  const value = fixture();
  assert.throws(() => communityBuildPath(value.root, "../outside"), /规定的本地输出/);
  const linked = fixture();
  const outside = mkdtempSync(resolve(tmpdir(), "agenteam-community-build-"));
  fixtures.push(outside);
  symlinkSync(outside, resolve(linked.root, ".build/maven-repository"), "junction");
  assert.throws(() => communityBuildPath(linked.root, ".build/maven-repository"), /链接/);
});
