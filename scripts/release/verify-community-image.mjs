import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import {mkdirSync, readFileSync} from "node:fs";
import {resolve} from "node:path";
import {imageKinds} from "./community-publication.mjs";

const kind = process.env.IMAGE_KIND;
const reference = process.env.IMAGE_REFERENCE;
if (!imageKinds.includes(kind) || !/^docker\.io\/stonewuu\/agenteam-[a-z-]+:build-\d+-\d+$/.test(reference ?? "")) {
  throw new Error("只能检查本次社区候选镜像。");
}
const docker = (args) => execFileSync("docker", args, {encoding: "utf8", timeout: 120000, windowsHide: true});
const metadata = JSON.parse(docker(["image", "inspect", reference]))[0];
assert.equal(metadata.Architecture, "amd64");
assert.equal(metadata.Os, "linux");
assert.equal(metadata.Config.Labels["org.opencontainers.image.revision"], process.env.RELEASE_COMMIT);
assert.equal(metadata.Config.Labels["org.opencontainers.image.version"], process.env.RELEASE_VERSION);
assert.equal(metadata.Config.Labels["com.stonewu.agenteam.edition"], "community");
const name = `agenteam-image-${kind}-${process.env.GITHUB_RUN_ID}-${process.env.GITHUB_RUN_ATTEMPT}`;
const directory = resolve(".runtime/release/image-files");
mkdirSync(directory, {recursive: true});
let created = false;
try {
  docker(["create", "--name", name, "--network", "none", reference]);
  created = true;
  const licenses = kind === "deployment" ? "/deployment/licenses/agenteam" : "/usr/share/licenses/agenteam/agenteam";
  for (const file of ["LICENSE", "NOTICE"]) {
    docker(["cp", `${name}:${licenses}/${file}`, resolve(directory, file)]);
    assert.deepEqual(readFileSync(resolve(directory, file)), readFileSync(file));
  }
  if (kind === "deployment") {
    docker(["cp", `${name}:/deployment/release.env`, resolve(directory, "release.env")]);
    assert.match(readFileSync(resolve(directory, "release.env"), "utf8"),
      new RegExp(`^RELEASE_TAG=${process.env.RELEASE_VERSION.replaceAll(".", "\\.")}\\r?$`, "m"));
  } else {
    const commands = {
      backend: ["java", "-version"],
      executor: ["sh", "-c", "java -version && docker --version && command -v nsenter"],
      frontend: ["node", "--version"],
      office: ["python", "-c", "import docx, openpyxl, pptx, pypdf, pandas; print('办公组件已加载')"],
    };
    const [entrypoint, ...args] = commands[kind];
    docker(["run", "--rm", "--network", "none", "--memory", "512m", "--entrypoint", entrypoint, reference, ...args]);
  }
} finally {
  if (created) {
    docker(["rm", name]);
  }
}
console.log(`${kind} 实际镜像的版本、运行环境和原版权文件检查通过。`);
