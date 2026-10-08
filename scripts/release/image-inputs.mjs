import {createHash} from "node:crypto";
import {existsSync, lstatSync, mkdirSync, readFileSync, readdirSync, writeFileSync} from "node:fs";
import {dirname, isAbsolute, relative, resolve, sep} from "node:path";
import {readThirdPartyInputs, verifyThirdPartyInputs} from "./third-party-inputs.mjs";

const productImages = {backend: "APP_IMAGE", frontend: "FRONTEND_IMAGE", executor: "EXECUTOR_IMAGE",
  office: "SANDBOX_IMAGE", deployment: "DEPLOYMENT_IMAGE"};
const deploymentFiles = ["compose.yaml", "compose.registry.yaml", "compose.single.yaml",
  "compose.shared-mysql.yaml", "compose.external-mysql.yaml",
  "registry/configure-server.py", "registry/nginx-host.conf.template", "registry/Dockerfile", "registry/README.md",
  "nginx/15-agenteam-config.sh", "nginx/default.conf.template", "nginx/api.conf"];
const frontendFiles = ["Dockerfile", ".dockerignore", "package.json", "pnpm-lock.yaml", "pnpm-workspace.yaml",
  "next.config.ts", "tsconfig.json", "postcss.config.mjs"];
const officeFiles = ["Dockerfile", "package.json", "package-lock.json", "requirements.txt", "requirements.lock"];

export function sha256(content) {
  return createHash("sha256").update(content).digest("hex");
}

export function communityTargets(version) {
  const names = {backend: "backend", frontend: "frontend", executor: "sandbox-server", office: "sandbox-office",
    deployment: "deployment"};
  return Object.fromEntries(Object.entries(names).map(([kind, name]) => [kind, `docker.io/stonewuu/agenteam-${name}:${version}`]));
}

/** 发布清单不能混入密码、占位变量或未分配给当前版本的镜像。 */
export function readReleaseConfiguration(file, {edition, version, targets}) {
  const allowed = new Set(["RELEASE_TAG", "EDITION", "REGISTRY_NAMESPACE", "GATEWAY_IMAGE", "MYSQL_IMAGE", "REDIS_IMAGE",
    ...Object.values(productImages)]);
  const values = {};
  for (const line of readFileSync(file, "utf8").replace(/^\uFEFF/, "").split(/\r?\n/)) {
    if (!line.trim() || line.startsWith("#")) {
      continue;
    }
    const match = line.match(/^([A-Z_]+)=([A-Za-z0-9./:@_-]+)$/);
    if (!match || !allowed.has(match[1]) || Object.hasOwn(values, match[1])) {
      throw new Error("发布配置包含未知、重复或格式不正确的字段。");
    }
    values[match[1]] = match[2];
  }
  if (values.RELEASE_TAG !== version || values.EDITION !== edition) {
    throw new Error("发布配置的版本或版本类型与当前构建不一致。");
  }
  if (Object.keys(targets).sort().join() !== Object.keys(productImages).sort().join()) {
    throw new Error("必须明确指定后端、前端、执行器、办公沙箱和部署包五个目标。");
  }
  for (const [kind, variable] of Object.entries(productImages)) {
    if (values[variable] !== targets[kind] || !values[variable].endsWith(`:${version}`)) {
      throw new Error(`镜像目标与本批次不一致：${variable}`);
    }
  }
  for (const variable of ["GATEWAY_IMAGE", "MYSQL_IMAGE", "REDIS_IMAGE"]) {
    if (!/^docker\.io\/library\/[a-z-]+:[A-Za-z0-9._-]+$/.test(values[variable] ?? "")
      || values[variable].endsWith(":latest")) {
      throw new Error(`第三方镜像必须有明确版本：${variable}`);
    }
  }
  const expectedNamespace = targets.backend.slice(0, targets.backend.lastIndexOf("/"));
  if (values.REGISTRY_NAMESPACE !== expectedNamespace) {
    throw new Error("镜像命名空间与实际目标不一致。");
  }
  return values;
}

function regularFile(file) {
  let current = resolve(file);
  while (true) {
    if (lstatSync(current).isSymbolicLink()) {
      throw new Error(`镜像输入不能通过符号链接读取其他目录：${file}`);
    }
    const parent = dirname(current);
    if (parent === current) {
      break;
    }
    current = parent;
  }
  if (!lstatSync(file).isFile()) {
    throw new Error(`镜像输入不是普通文件：${file}`);
  }
  return readFileSync(file);
}

function sourceTree(root, prefix) {
  const directory = resolve(root, prefix);
  if (!existsSync(directory)) {
    throw new Error(`缺少预期输入目录：${directory}`);
  }
  return readdirSync(directory, {withFileTypes: true}).flatMap((entry) => {
    const name = `${prefix}/${entry.name}`;
    if (entry.name === "__pycache__" || entry.name.endsWith(".pyc")) {
      return [];
    }
    if (entry.isSymbolicLink() || [".git", "node_modules", ".next"].includes(entry.name)
      || entry.name.startsWith(".env") || /\.(pem|key|p12|pfx)$/i.test(entry.name) || entry.name.endsWith(".license")) {
      throw new Error(`镜像输入含有不允许的文件：${name}`);
    }
    return entry.isDirectory() ? sourceTree(root, name) : [name];
  });
}

function copyInput(source, destination, name, records) {
  const file = resolve(destination, name);
  const path = relative(destination, file);
  if (!path || path.startsWith(`..${sep}`) || path === ".." || isAbsolute(path)) {
    throw new Error("镜像输入路径必须位于本次生成目录中。");
  }
  const content = regularFile(source);
  mkdirSync(dirname(file), {recursive: true});
  writeFileSync(file, content, {flag: "wx"});
  records[name.replaceAll("\\", "/")] = sha256(content);
}

/** 调用方提供全新的目录；公共、商业都从同一份部署模板准备明确输入，不读取外层 package。 */
export function createImageInputs({community, productRoot = community, frontend, backend, output, releaseFile, edition, version, targets,
  sourceCommit, communityCommit, release = false, thirdPartyDocuments, sharedThirdPartyDocuments = thirdPartyDocuments, backendBuildSha256}) {
  if (!/^\d+\.\d+\.\d+$/.test(version) || !["community", "pro"].includes(edition)) {
    throw new Error("镜像输入缺少有效的应用版本或版本类型。");
  }
  const configuration = readReleaseConfiguration(releaseFile, {edition, version, targets});
  if (edition === "community" && resolve(productRoot) !== resolve(community)
    || edition === "pro" && resolve(productRoot) === resolve(community)) {
    throw new Error("安装文档必须来自当前版本所属的源码仓库。");
  }
  const web = JSON.parse(regularFile(resolve(frontend, "package.json")));
  if (web.version !== version) {
    throw new Error("前端源文件与镜像版本不一致。");
  }
  if (release && !thirdPartyDocuments) {
    throw new Error("正式镜像必须附上与当前构建对应的第三方声明资料。");
  }
  const productDocuments = thirdPartyDocuments ? readThirdPartyInputs(thirdPartyDocuments) : null;
  const sharedDocuments = sharedThirdPartyDocuments === thirdPartyDocuments ? productDocuments
    : sharedThirdPartyDocuments ? readThirdPartyInputs(sharedThirdPartyDocuments) : null;
  if (productDocuments) {
    verifyThirdPartyInputs({product: productDocuments, shared: sharedDocuments,
      backend, frontend, office: resolve(community, "deploy/sandbox")});
  }
  if (backendBuildSha256 !== undefined && !/^[a-f0-9]{64}$/.test(backendBuildSha256)) {
    throw new Error("后端构建记录的摘要格式不正确。");
  }
  if (existsSync(output)) {
    throw new Error("镜像输入目录已经存在，停止覆盖；请为本批次创建新目录。");
  }
  let parent = dirname(resolve(output));
  while (parent !== dirname(parent)) {
    if (existsSync(parent) && lstatSync(parent).isSymbolicLink()) {
      throw new Error("镜像输出的父目录不能含符号链接。");
    }
    parent = dirname(parent);
  }
  mkdirSync(output, {recursive: true});
  if (lstatSync(output).isSymbolicLink()) {
    throw new Error("镜像输出不能是符号链接。");
  }
  const contexts = {};
  const licenseEntries = (bundle) => [["licenses/agenteam/LICENSE", resolve(community, "LICENSE")],
    ["licenses/agenteam/NOTICE", resolve(community, "NOTICE")],
    ...bundle ? [...bundle.files.keys()].map((name) => [`licenses/third-party/${name}`, resolve(bundle.root, name)]) : []];
  const save = (kind, entries) => {
    const folder = resolve(output, kind);
    mkdirSync(folder);
    const records = {};
    for (const [name, source] of entries) {
      copyInput(source, folder, name, records);
    }
    const bundle = kind === "office" ? sharedDocuments : productDocuments;
    if (bundle) {
      for (const [name, checksum] of bundle.files) {
        if (records[`licenses/third-party/${name}`] !== checksum) {
          throw new Error("复制期间第三方原文已经变化，请重新准备资料。");
        }
      }
      if (kind === "backend" && records["app.jar"] !== bundle.index.applicationArtifact.sha256) {
        throw new Error("复制期间后端文件已经变化，请重新准备产物和资料。");
      }
    }
    if (["frontend", "office"].includes(kind)) {
      // 只调整本次明确生成的输入，保留直接从源码构建的 Dockerfile 用法。
      const dockerfile = resolve(folder, "Dockerfile");
      const content = readFileSync(dockerfile, "utf8") + "\nCOPY licenses/ /usr/share/licenses/agenteam/\n";
      writeFileSync(dockerfile, content);
      records.Dockerfile = sha256(content);
    }
    if (kind === "frontend") {
      const ignoreFile = resolve(folder, ".dockerignore");
      const content = readFileSync(ignoreFile, "utf8") + "\n!licenses/\n!licenses/**\n";
      writeFileSync(ignoreFile, content);
      records[".dockerignore"] = sha256(content);
    }
    contexts[kind] = {directory: kind, files: records};
  };
  save("backend", [["Dockerfile", resolve(community, "deploy/Dockerfile.artifact")],
    ["app.jar", backend], ["sandbox-entrypoint.sh", resolve(community, "deploy/container/sandbox-entrypoint.sh")],
    ...licenseEntries(productDocuments)]);
  const webNames = [...frontendFiles, ...sourceTree(frontend, "src"), ...sourceTree(frontend, "public")];
  if (existsSync(resolve(frontend, "next-env.d.ts"))) {
    webNames.push("next-env.d.ts");
  }
  save("frontend", [...webNames.map((name) => [name, resolve(frontend, name)]), ...licenseEntries(productDocuments)]);
  const office = resolve(community, "deploy/sandbox");
  save("office", [...[...officeFiles, ...sourceTree(office, "office"), ...sourceTree(office, "bin")]
    .map((name) => [name, resolve(office, name)]), ...licenseEntries(sharedDocuments)]);
  save("deployment", [...deploymentFiles.map((name) => [name, resolve(community, "deploy", name)]),
    ["registry/release.env", releaseFile],
    ["docs/deployment/images.md", resolve(productRoot, "docs/deployment/images.md")],
    ["docs/deployment/enterprise-messaging.md", resolve(community, "docs/deployment/enterprise-messaging.md")],
    ...licenseEntries(productDocuments),
    ...edition === "pro" ? ["maintenance.md", "license-file.md"].map((name) =>
      [`docs/licensing/${name}`, resolve(productRoot, "docs/licensing", name)]) : []]);
  const result = {formatVersion: 1, version, edition, sourceCommit, communityCommit, release,
    configuration, targets, contexts, backendBuildSha256, thirdPartyDocuments: productDocuments ? {
      productIndexSha256: productDocuments.indexSha256, sharedIndexSha256: sharedDocuments.indexSha256,
      metadataOnly: productDocuments.index.metadataOnly ?? []} : null};
  writeFileSync(resolve(output, "image-inputs.json"), JSON.stringify(result, null, 2) + "\n", {flag: "wx"});
  return result;
}

/** 每次构建及补发前核对整个输入，拒绝被修改、缺失或临时塞入的文件。 */
export function verifyImageInputs(output, manifest) {
  for (const context of Object.values(manifest.contexts)) {
    const directory = resolve(output, context.directory);
    const actual = [];
    const walk = (folder, prefix = "") => {
      for (const entry of readdirSync(folder, {withFileTypes: true})) {
        const name = prefix + entry.name;
        if (entry.isSymbolicLink()) {
          throw new Error("镜像输入中出现符号链接。");
        }
        if (entry.isDirectory()) {
          walk(resolve(folder, entry.name), name + "/");
        } else {
          actual.push(name);
          if (sha256(regularFile(resolve(directory, name))) !== context.files[name]) {
            throw new Error(`镜像输入已变化或包含未登记文件：${name}`);
          }
        }
      }
    };
    walk(directory);
    if (actual.sort().join("\n") !== Object.keys(context.files).sort().join("\n")) {
      throw new Error("镜像输入缺少清单中登记的文件。");
    }
  }
}
