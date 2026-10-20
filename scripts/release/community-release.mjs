import {execFileSync} from "node:child_process";
import {appendFileSync, mkdirSync, readFileSync, readdirSync, writeFileSync} from "node:fs";
import {resolve} from "node:path";
import {isDeepStrictEqual} from "node:util";
import {readCommunitySource} from "./community-backend.mjs";
import {hasNewerCompletedVersion, imageKinds, publicationPlan, publishCommunityImages} from "./community-publication.mjs";
import {publicImageDigest} from "./community-registry.mjs";

const output = resolve(".runtime/release");
mkdirSync(output, {recursive: true});
const execute = (command, args) => execFileSync(command, args,
  {encoding: "utf8", windowsHide: true, timeout: 120000, maxBuffer: 8 * 1024 * 1024}).trim();
const github = (path) => JSON.parse(execute("gh", ["api", path]));
const save = (name, value) => writeFileSync(resolve(output, name), JSON.stringify(value, null, 2) + "\n");
const releasePath = (version) => `repos/Stonewuu/agenteam/releases/tags/v${version}`;
const kind = process.env.IMAGE_KIND;
const command = process.argv[2];

if (command === "plan") {
  const source = readCommunitySource(undefined, {release: true});
  const event = JSON.parse(readFileSync(process.env.GITHUB_EVENT_PATH, "utf8"));
  const publish = process.env.GITHUB_EVENT_NAME === "release" || process.env.REQUEST_PUBLISH === "true";
  const tag = process.env.GITHUB_EVENT_NAME === "release" ? event.release?.tag_name : process.env.RELEASE_TAG;
  if (publish && tag !== `v${source.version}`) {
    throw new Error("Release 标签必须与当前应用版本完全一致。");
  }
  if (publish && source.commit !== process.env.GITHUB_SHA) {
    throw new Error("请从正式标签运行发布工作流，不能用其他分支替代对应的发布来源。");
  }
  const release = publish ? github(releasePath(source.version)) : null;
  if (publish && execute("git", ["rev-parse", `refs/tags/${tag}^{commit}`]) !== source.commit) {
    throw new Error("当前源码没有位于正式发布标签。");
  }
  const plan = publicationPlan({source, repository: github("repos/Stonewuu/agenteam"),
    release, publish, runId: process.env.GITHUB_RUN_ID});
  save("plan.json", plan);
  const matrix = imageKinds.map((name) => ({
    kind: name, directory: name === "executor" ? "backend" : name,
    file: name === "deployment" ? "registry/Dockerfile" : "Dockerfile",
    target: name === "executor" ? "sandbox-server" : ["frontend", "backend"].includes(name) ? "application" : "",
    image: plan.targets[name].replace(/:[^:]+$/, ""),
  }));
  for (const [name, value] of Object.entries({version: plan.version, commit: plan.commit,
    publish: String(publish), matrix: JSON.stringify({include: matrix})})) {
    appendFileSync(process.env.GITHUB_OUTPUT, `${name}=${value}\n`);
  }
  console.log(`社区 ${plan.version}：${publish ? "正式发布" : "候选构建验证"}，来源 ${plan.commit}。`);
} else if (command === "record") {
  const source = readCommunitySource(undefined, {release: true});
  if (!imageKinds.includes(kind) || source.version !== process.env.RELEASE_VERSION
    || source.commit !== process.env.RELEASE_COMMIT) {
    throw new Error("镜像记录与当前源码或版本不一致。");
  }
  const names = {backend: "backend", frontend: "frontend", executor: "sandbox-server",
    office: "sandbox-office", deployment: "deployment"};
  const repository = `docker.io/stonewuu/agenteam-${names[kind]}`;
  const candidate = `${repository}:build-${process.env.GITHUB_RUN_ID}-${process.env.GITHUB_RUN_ATTEMPT}`;
  const digest = await publicImageDigest(candidate);
  if (!digest) {
    throw new Error("候选镜像没有推送成功。");
  }
  save(`${kind}.json`, {kind, version: source.version, commit: source.commit,
    image: `${repository}:${source.version}`, digest});
  console.log(`${kind} 候选镜像已推送并可公开读取。`);
} else if (command === "publish") {
  const plan = JSON.parse(readFileSync(resolve(output, "plan.json"), "utf8"));
  const records = readdirSync(resolve(output, "images"))
    .filter((name) => name.endsWith(".json"))
    .map((name) => JSON.parse(readFileSync(resolve(output, "images", name), "utf8")));
  const getRelease = () => github(releasePath(plan.version));
  const readReleaseManifest = async (release) => {
    const matches = release.assets.filter((asset) => asset.name === "release-batch.json");
    if (matches.length > 1) {
      throw new Error("正式发布存在重复清单。");
    }
    if (!matches.length) {
      return null;
    }
    const response = await fetch(matches[0].browser_download_url, {signal: AbortSignal.timeout(30000)});
    if (!response.ok) {
      throw new Error(`公开清单读取失败，HTTP 状态 ${response.status}。`);
    }
    return await response.json();
  };
  const readManifest = () => readReleaseManifest(getRelease());
  const adapter = {
    inspect: publicImageDigest,
    async verifySource() {
      const release = getRelease();
      if (release.id !== plan.releaseId || release.draft || release.prerelease
        || release.tag_name !== `v${plan.version}`
        || execute("git", ["rev-parse", "HEAD"]) !== plan.commit) {
        throw new Error("正式发布或当前源码发生变化，停止发布。");
      }
      // 从服务器读取标签并解析，拒绝构建期间被移动的标签。
      execute("git", ["fetch", "--no-tags", "origin", `refs/tags/v${plan.version}`]);
      if (execute("git", ["rev-parse", "FETCH_HEAD^{commit}"]) !== plan.commit) {
        throw new Error("远端版本标签发生变化，停止发布。");
      }
    },
    async copy(entry, target) {
      const repository = entry.image.replace(/:[^:]+$/, "");
      execute("docker", ["buildx", "imagetools", "create", "--prefer-index=false",
        "--tag", target, `${repository}@${entry.digest}`]);
    },
    readManifest,
    async saveManifest(document) {
      const existing = await readManifest();
      if (existing && !isDeepStrictEqual(existing, document)) {
        throw new Error("正式清单发生变化，不能覆盖。");
      }
      if (!existing) {
        save("release-batch.json", document);
        execute("gh", ["release", "upload", `v${plan.version}`, resolve(output, "release-batch.json"),
          "--repo", "Stonewuu/agenteam"]);
      }
      if (!isDeepStrictEqual(await readManifest(), document)) {
        throw new Error("公开发布清单尚未确认。");
      }
    },
    async isNewest(version) {
      const pages = JSON.parse(execute("gh", ["api", "--paginate", "--slurp",
        "repos/Stonewuu/agenteam/releases?per_page=100"]));
      return !await hasNewerCompletedVersion(version, pages.flat(), readReleaseManifest);
    },
  };
  const result = await publishCommunityImages(plan, records, adapter);
  save("publication-result.json", result);
  console.log(result.latest === "complete" ? "五类正式镜像和 latest 均已确认。"
    : "五类正式镜像已确认；已有更高正式版本，latest 保持原值。");
} else {
  throw new Error("请指定 plan、record 或 publish。");
}
