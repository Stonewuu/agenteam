import assert from "node:assert/strict";
import test from "node:test";
import {completedManifest, hasNewerCompletedVersion, imageKinds, isNewerVersion, publicationPlan, publishCommunityImages} from "./community-publication.mjs";

function fixture() {
  const parameters = {source: {version: "1.2.3", commit: "a".repeat(40), dirty: false},
    repository: {full_name: "Stonewuu/agenteam", id: 1, private: false},
    release: {id: 2, tag_name: "v1.2.3", draft: false, prerelease: false}, publish: true, runId: "123"};
  const plan = publicationPlan(parameters);
  const records = imageKinds.map((kind, index) => ({kind, version: plan.version, commit: plan.commit,
    image: plan.targets[kind], digest: `sha256:${String(index + 1).repeat(64)}`, extra: "不得公开的附加资料"}));
  const images = new Map(records.map((record) => [
    `${record.image.replace(/:[^:]+$/, "")}@${record.digest}`, record.digest]));
  const writes = [];
  let saved;
  const adapter = {
    async verifySource() {},
    async inspect(reference) {
      return images.get(reference) ?? null;
    },
    async copy(entry, target) {
      writes.push(target);
      images.set(target, entry.digest);
    },
    async readManifest() {
      return saved;
    },
    async saveManifest(document) {
      saved = structuredClone(document);
    },
    async isNewest() {
      return true;
    },
  };
  return {parameters, plan, records, images, writes, adapter};
}

test("正式发布必须匹配仓库、版本和提交，验证模式不能发布正式标签", async () => {
  const value = fixture();
  for (const change of [
    {release: {...value.parameters.release, prerelease: true}},
    {release: {...value.parameters.release, draft: true}},
    {release: {...value.parameters.release, tag_name: "v1.2.4"}},
    {repository: {...value.parameters.repository, private: true}},
    {source: {...value.parameters.source, dirty: true}},
    {source: {...value.parameters.source, version: "1.2.3-rc.1"}},
  ]) {
    assert.throws(() => publicationPlan({...value.parameters, ...change}));
  }
  const validation = publicationPlan({...value.parameters, release: null, publish: false});
  await assert.rejects(publishCommunityImages(validation, value.records, value.adapter), /构建验证/);
  assert.deepEqual(value.writes, []);
});

test("五类镜像全部确认后发布清单和 latest，公开内容只包含允许字段", async () => {
  const value = fixture();
  const result = await publishCommunityImages(value.plan, value.records, value.adapter);
  assert.equal(result.latest, "complete");
  assert.deepEqual(value.writes.slice(0, 5), imageKinds.map((kind) => value.plan.targets[kind]));
  assert.ok(value.writes.slice(5).every((target) => target.endsWith(":latest")));
  assert.deepEqual(Object.keys(result.document).sort(), ["formatVersion", "status", "version", "batchId", "community"].sort());
  assert.equal(JSON.stringify(result.document).includes("不得公开"), false);
  await publishCommunityImages(value.plan, value.records, value.adapter);
  assert.equal(value.writes.filter((target) => !target.endsWith(":latest")).length, 5);
});

test("任一结果缺失、混入其他提交、候选不可读或固定版本冲突时不写任何标签", async () => {
  for (const change of [
    (value) => value.records.pop(),
    (value) => {
      value.records[4].commit = "b".repeat(40);
    },
    (value) => value.images.clear(),
    (value) => value.images.set(value.records[4].image, `sha256:${"f".repeat(64)}`),
    (value) => {
      value.adapter.verifySource = async () => {
        throw new Error("远端标签已变化");
      };
    },
  ]) {
    const value = fixture();
    change(value);
    await assert.rejects(publishCommunityImages(value.plan, value.records, value.adapter));
    assert.deepEqual(value.writes, []);
  }
});

test("部分版本标签成功后重试只补齐缺失项，已有清单发生变化时拒绝覆盖", async () => {
  const value = fixture();
  const copy = value.adapter.copy;
  value.adapter.copy = async (entry, target) => {
    if (value.writes.length === 2) {
      throw new Error("模拟网络中断");
    }
    await copy(entry, target);
  };
  await assert.rejects(publishCommunityImages(value.plan, value.records, value.adapter), /网络中断/);
  assert.equal(await value.adapter.readManifest(), undefined);
  value.adapter.copy = copy;
  await publishCommunityImages(value.plan, value.records, value.adapter);
  assert.equal(value.writes.filter((target) => !target.endsWith(":latest")).length, 5);
  value.adapter.readManifest = async () => ({...completedManifest(value.plan, value.records), batchId: "another"});
  const count = value.writes.length;
  await assert.rejects(publishCommunityImages(value.plan, value.records, value.adapter), /已有不同/);
  assert.equal(value.writes.length, count);
});

test("较旧版本仍发布固定版本，但不能回退 latest；版本按数字比较", async () => {
  const value = fixture();
  value.adapter.isNewest = async () => false;
  assert.equal((await publishCommunityImages(value.plan, value.records, value.adapter)).latest, "kept-newer-version");
  assert.equal(value.writes.length, 5);
  assert.equal(isNewerVersion("0.10.0", "0.9.9"), true);
  assert.equal(isNewerVersion("1.2.3", "1.2.3"), false);
});

test("更高版本尚未构建完成时不阻断当前版本，完整发布后阻止旧版本回退", async () => {
  const releases = [{tag_name: "v1.2.4", draft: false, prerelease: false, assets: []}];
  assert.equal(await hasNewerCompletedVersion("1.2.3", releases,
    async () => assert.fail("没有发布清单时不应读取")), false);
  releases[0].assets.push({name: "release-batch.json"});
  const value = fixture();
  const document = completedManifest(value.plan, value.records);
  document.version = "1.2.4";
  for (const image of Object.values(document.community.images)) {
    image.image = image.image.replace(":1.2.3", ":1.2.4");
  }
  assert.equal(await hasNewerCompletedVersion("1.2.3", releases, async () => document), true);
  document.status = "incomplete";
  await assert.rejects(hasNewerCompletedVersion("1.2.3", releases, async () => document), /无法确认/);
});
