import assert from "node:assert/strict";
import {createHash} from "node:crypto";
import {mkdtempSync, readFileSync, realpathSync, rmSync, symlinkSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {basename, dirname, resolve} from "node:path";
import test, {after} from "node:test";
import {readSourceArchiveInputs, sourceFileHash} from "./source-archive-inputs.mjs";

const roots = [];
const digest = (value) => createHash("sha256").update(value).digest("hex");

function fixture() {
  const directory = mkdtempSync(resolve(tmpdir(), "agenteam-source-input-"));
  roots.push(directory);
  const runtimeImages = ["community", "pro"].flatMap((edition, i) => ["backend", "frontend", "executor", "office"]
    .map((kind, j) => ({edition, kind, imageId: `sha256:${String(i * 4 + j + 1).repeat(64)}`})));
  const data = Buffer.alloc(2 * 1024 * 1024 + 17, 37);
  const name = "agenteam-third-party-sources-fixture-0.2.0.tar";
  writeFileSync(resolve(directory, name), data);
  const manifest = {formatVersion: 1, version: "0.2.0", complete: true, releaseApproved: false,
    internalPath: directory, runtimeImages,
    archives: [{file: name, sha256: digest(data), bytes: data.length, sourceFilesVerified: 1, indexSha256: "a".repeat(64)}]};
  const save = () => writeFileSync(resolve(directory, "source-archives.json"), JSON.stringify(manifest));
  save();
  return {directory, runtimeImages, manifest, save, name, data,
    read: () => readSourceArchiveInputs(directory, {version: "0.2.0", runtimeImages})};
}

after(() => {
  const parent = realpathSync(tmpdir());
  for (const directory of roots) {
    const absolute = realpathSync(directory);
    assert.equal(dirname(absolute), parent);
    assert.ok(basename(absolute).startsWith("agenteam-source-input-"));
    rmSync(absolute, {recursive: true});
  }
});

test("大型附件分块计算摘要，对外索引不带入本机路径和内部审核状态", () => {
  const value = fixture();
  const result = value.read();
  assert.equal(sourceFileHash(resolve(value.directory, value.name)), digest(value.data));
  assert.equal(result.catalog.archives[0].sha256, digest(value.data));
  assert.equal(JSON.stringify(result.catalog).includes(value.directory), false);
  assert.equal("releaseApproved" in result.catalog, false);
  assert.equal("internalPath" in result.catalog, false);
  assert.equal(result.catalog.archives[0].url,
    "https://github.com/Stonewuu/agenteam/releases/download/v0.2.0/agenteam-third-party-sources-fixture-0.2.0.tar");
});

test("同版本但来自其他成品的源码不能用于本批，也不能接受重复的镜像记录", () => {
  const value = fixture();
  const expected = structuredClone(value.runtimeImages);
  value.manifest.runtimeImages[0].imageId = `sha256:${"f".repeat(64)}`;
  value.save();
  assert.throws(() => readSourceArchiveInputs(value.directory, {version: "0.2.0", runtimeImages: expected}), /不对应本批/);
  value.manifest.runtimeImages.push(value.manifest.runtimeImages[0]);
  value.save();
  assert.throws(value.read, /重复的镜像/);
});

test("篡改、截断和额外文件都拒绝，不以附件文件名相同作为内容相同", () => {
  const value = fixture();
  writeFileSync(resolve(value.directory, value.name), Buffer.alloc(value.data.length, 12));
  assert.throws(value.read, /原文件与.*不同/);
  writeFileSync(resolve(value.directory, value.name), value.data.subarray(0, 100));
  assert.throws(value.read, /原文件与.*不同/);
  writeFileSync(resolve(value.directory, value.name), value.data);
  writeFileSync(resolve(value.directory, "unlisted.txt"), "未审核文件");
  assert.throws(value.read, /未登记/);
});

test("越界路径、未完成清单、错误版本和重复附件都拒绝", () => {
  for (const mutate of [
    (manifest) => {
      manifest.archives[0].file = "../outside.tar";
    },
    (manifest) => {
      manifest.complete = "false";
    },
    (manifest) => {
      manifest.version = "0.1.1";
    },
    (manifest) => {
      manifest.archives.push(manifest.archives[0]);
    }
  ]) {
    const value = fixture();
    mutate(value.manifest);
    value.save();
    assert.throws(value.read);
  }
});

test("经由目录链接读取另一套材料会拒绝，原文件保持不变", () => {
  const value = fixture();
  const alias = fixture();
  const link = resolve(alias.directory, "linked-input");
  symlinkSync(value.directory, link, process.platform === "win32" ? "junction" : "dir");
  const before = readFileSync(resolve(value.directory, value.name));
  assert.throws(() => readSourceArchiveInputs(link, {version: "0.2.0", runtimeImages: value.runtimeImages}), /不能使用链接/);
  assert.deepEqual(readFileSync(resolve(value.directory, value.name)), before);
});
