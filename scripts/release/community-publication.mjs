import {isDeepStrictEqual} from "node:util";
import {communityTargets} from "./image-inputs.mjs";

export const imageKinds = ["backend", "frontend", "executor", "office", "deployment"];
const versionPattern = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/;
const digestPattern = /^sha256:[a-f0-9]{64}$/;

export function publicationPlan({source, repository, release, publish, runId}) {
  if (repository.full_name !== "Stonewuu/agenteam" || repository.private !== false
    || !Number.isSafeInteger(repository.id) || !versionPattern.test(source.version)
    || !/^[a-f0-9]{40}$/.test(source.commit) || source.dirty || !/^\d+$/.test(runId)) {
    throw new Error("发布需要准确的社区仓库、干净源码和正式版本。");
  }
  if (publish && (!release || release.draft || release.prerelease
    || release.tag_name !== `v${source.version}` || !Number.isSafeInteger(release.id))) {
    throw new Error("只允许发布与当前源码版本一致的正式 Release。");
  }
  return {version: source.version, commit: source.commit, repository: repository.full_name,
    repositoryId: repository.id, releaseId: publish ? release.id : null, publish,
    batchId: `${source.version}-github-${runId}`, targets: communityTargets(source.version)};
}

export function completedManifest(plan, records) {
  if (records.length !== imageKinds.length || new Set(records.map((record) => record.kind)).size !== imageKinds.length) {
    throw new Error("五类镜像结果不完整或重复，不能发布。");
  }
  const images = {};
  for (const kind of imageKinds) {
    const record = records.find((item) => item.kind === kind);
    if (!record || record.version !== plan.version || record.commit !== plan.commit
      || record.image !== plan.targets[kind] || !digestPattern.test(record.digest ?? "")) {
      throw new Error("镜像结果的版本、提交、目标或内容摘要不一致。");
    }
    images[kind] = {image: record.image, digest: record.digest};
  }
  return {formatVersion: 1, status: "complete", version: plan.version, batchId: plan.batchId,
    community: {repository: plan.repository, repositoryId: plan.repositoryId, commit: plan.commit, images}};
}

export function isNewerVersion(left, right) {
  if (!versionPattern.test(left) || !versionPattern.test(right)) {
    throw new Error("比较版本时必须使用正式版本号。");
  }
  const first = left.split(".").map(BigInt);
  const second = right.split(".").map(BigInt);
  for (let index = 0; index < first.length; index++) {
    if (first[index] !== second[index]) {
      return first[index] > second[index];
    }
  }
  return false;
}

/** 固定版本全部确认后才保存清单，最后更新最新标签；已有不同内容绝不覆盖。 */
export async function publishCommunityImages(plan, records, adapter) {
  if (!plan.publish) {
    throw new Error("构建验证不能推送正式标签。");
  }
  const document = completedManifest(plan, records);
  await adapter.verifySource(plan);
  const existing = await adapter.readManifest();
  if (existing && !isDeepStrictEqual(existing, document)) {
    throw new Error("Release 已有不同的版本清单，不能覆盖；请重跑原工作流中失败的任务。");
  }
  const entries = Object.values(document.community.images);
  // 检查全部目标再开始写入，避免最后一个冲突时前几个已经被发布。
  for (const entry of entries) {
    const repository = entry.image.replace(/:[^:]+$/, "");
    if (await adapter.inspect(`${repository}@${entry.digest}`) !== entry.digest) {
      throw new Error("候选镜像尚不能按内容摘要公开读取。");
    }
    const current = await adapter.inspect(entry.image);
    if (current && current !== entry.digest) {
      throw new Error(`固定版本已经存在不同内容，停止发布：${entry.image}`);
    }
  }
  for (const entry of entries) {
    await adapter.verifySource(plan);
    const current = await adapter.inspect(entry.image);
    if (current && current !== entry.digest) {
      throw new Error("固定版本在发布期间发生变化，停止覆盖。");
    }
    if (!current) {
      await adapter.copy(entry, entry.image);
    }
    if (await adapter.inspect(entry.image) !== entry.digest) {
      throw new Error("固定版本镜像写入未确认。");
    }
  }
  await adapter.saveManifest(document);
  if (!await adapter.isNewest(plan.version)) {
    return {status: "complete", latest: "kept-newer-version", document};
  }
  for (const entry of entries) {
    await adapter.verifySource(plan);
    if (!await adapter.isNewest(plan.version)) {
      throw new Error("已有更高正式版本，停止更新 latest。");
    }
    await adapter.copy(entry, entry.image.replace(/:[^:]+$/, ":latest"));
  }
  for (const entry of entries) {
    if (await adapter.inspect(entry.image.replace(/:[^:]+$/, ":latest")) !== entry.digest) {
      throw new Error("latest 最终内容与正式镜像不同。");
    }
  }
  return {status: "complete", latest: "complete", document};
}
