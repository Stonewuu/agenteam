import {imageKinds} from "./community-publication.mjs";

export const imagePlatforms = [
  {architecture: "amd64", platform: "linux/amd64", runner: "ubuntu-24.04"},
  {architecture: "arm64", platform: "linux/arm64", runner: "ubuntu-24.04-arm"},
];
const digestPattern = /^sha256:[a-f0-9]{64}$/;

export function platformImageDigest(descriptor, architecture, digest) {
  if (!imagePlatforms.some((platform) => platform.architecture === architecture) || !digestPattern.test(digest)) {
    throw new Error("候选镜像架构或摘要无效。");
  }
  if (!descriptor.manifests) {
    return digest;
  }
  const entry = descriptor.manifests[0];
  if (descriptor.manifests.length !== 1 || entry.platform?.os !== "linux"
    || entry.platform.architecture !== architecture || !digestPattern.test(entry.digest)) {
    throw new Error("单架构候选包含其他架构或未知内容。");
  }
  return entry.digest;
}

export function imageMatrix(plan) {
  return imageKinds.flatMap((kind) => imagePlatforms.map((platform) => ({
    ...platform, kind, directory: kind === "executor" ? "backend" : kind,
    file: kind === "deployment" ? "registry/Dockerfile" : "Dockerfile",
    target: kind === "executor" ? "sandbox-server" : ["frontend", "backend"].includes(kind) ? "application" : "",
    image: plan.targets[kind].replace(/:[^:]+$/, ""),
  })));
}

/** 每种镜像的两个架构都成功后才能合并，拒绝重复或其他来源的结果。 */
export function groupPlatformImages(plan, records) {
  if (records.length !== imageKinds.length * imagePlatforms.length) {
    throw new Error("两种处理器架构的镜像结果不完整。");
  }
  return imageKinds.map((kind) => {
    const platforms = {};
    for (const {architecture, platform} of imagePlatforms) {
      const matches = records.filter((record) => record.kind === kind && record.architecture === architecture);
      const record = matches[0];
      if (matches.length !== 1 || record.version !== plan.version || record.commit !== plan.commit
        || record.image !== plan.targets[kind] || !digestPattern.test(record.digest ?? "")) {
        throw new Error("镜像架构、版本、提交或摘要不一致。");
      }
      platforms[platform] = record.digest;
    }
    return {kind, version: plan.version, commit: plan.commit, image: plan.targets[kind], platforms};
  });
}

export function verifyPlatformIndex(descriptor, platforms) {
  if (!Array.isArray(descriptor.manifests) || descriptor.manifests.length !== imagePlatforms.length) {
    throw new Error("镜像索引必须准确包含 AMD64 和 ARM64。");
  }
  for (const {architecture, platform} of imagePlatforms) {
    const matches = descriptor.manifests.filter((entry) => entry.platform?.os === "linux"
      && entry.platform.architecture === architecture);
    if (matches.length !== 1 || matches[0].digest !== platforms[platform]) {
      throw new Error("镜像索引没有保留已验证的架构内容。");
    }
  }
}
