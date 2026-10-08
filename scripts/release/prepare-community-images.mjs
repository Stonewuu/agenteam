import {execFileSync} from "node:child_process";
import {mkdirSync, mkdtempSync, readFileSync} from "node:fs";
import {resolve} from "node:path";
import {fileURLToPath} from "node:url";
import {parseArgs} from "node:util";
import {communityTargets, createImageInputs, verifyImageInputs} from "./image-inputs.mjs";
import {verifyCommunityBackend} from "./community-backend.mjs";

const {values} = parseArgs({options: {release: {type: "boolean", default: false}, backend: {type: "string"},
  "third-party-documents": {type: "string"}}});
const community = fileURLToPath(new URL("../../", import.meta.url));
const git = (...args) => execFileSync("git", ["-C", community, ...args], {encoding: "utf8"}).trim();
const version = JSON.parse(readFileSync(resolve(community, "agenteam-web/package.json"), "utf8")).version;
if (values.release && git("status", "--porcelain")) {
  throw new Error("正式社区镜像要求源码工作区无未提交修改。");
}
const commit = git("rev-parse", "HEAD");
const verified = values.release || !values.backend
  ? verifyCommunityBackend(community, {release: values.release, backend: values.backend}) : null;
const backend = verified?.backend ?? resolve(values.backend);
const root = resolve(community, ".build/releases");
mkdirSync(root, {recursive: true});
const batch = mkdtempSync(resolve(root, `${version}-`));
const output = resolve(batch, "community");
const manifest = createImageInputs({community, frontend: resolve(community, "agenteam-web"), backend, output,
  releaseFile: resolve(community, "deploy/registry/release.env"), edition: "community", version,
  targets: communityTargets(version), sourceCommit: commit, communityCommit: commit, release: values.release,
  thirdPartyDocuments: values["third-party-documents"], backendBuildSha256: verified?.recordSha256});
verifyImageInputs(output, manifest);
console.log(`社区五类镜像的构建输入已准备：${output}`);
