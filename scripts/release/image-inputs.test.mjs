import assert from "node:assert/strict";
import {afterEach, test} from "node:test";
import {mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, symlinkSync, unlinkSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {dirname, resolve} from "node:path";
import {communityTargets, createImageInputs, readReleaseConfiguration, sha256, verifyImageInputs} from "./image-inputs.mjs";

const fixtures = [];
afterEach(() => {
  for (const root of fixtures.splice(0)) {
    if (dirname(realpathSync(root)) !== realpathSync(tmpdir()) || !root.includes("agenteam-release-inputs-")) {
      throw new Error("测试目录不在预期临时路径内，停止清理。");
    }
    rmSync(root, {recursive: true});
  }
});

function fixture() {
  const root = mkdtempSync(resolve(tmpdir(), "agenteam-release-inputs-"));
  fixtures.push(root);
  const community = resolve(root, "agenteam");
  const frontend = resolve(community, "agenteam-web");
  const write = (name, value) => {
    const file = resolve(root, name);
    mkdirSync(dirname(file), {recursive: true});
    writeFileSync(file, value);
    return file;
  };
  for (const name of ["Dockerfile", ".dockerignore", "pnpm-lock.yaml", "pnpm-workspace.yaml", "next.config.ts",
    "tsconfig.json", "postcss.config.mjs", "src/index.ts", "public/example.txt", "src/component.LICENSE"]) {
    write(`agenteam/agenteam-web/${name}`, name.endsWith(".LICENSE") ? "必须保留的第三方版权声明" : "验证用内容");
  }
  write("agenteam/agenteam-web/package.json", JSON.stringify({name: "agenteam-web", version: "0.2.0"}));
  for (const name of ["compose.yaml", "compose.registry.yaml", "compose.single.yaml", "registry/configure-server.py",
    "compose.shared-mysql.yaml", "compose.external-mysql.yaml",
    "registry/nginx-host.conf.template", "registry/Dockerfile", "registry/README.md", "nginx/15-agenteam-config.sh", "nginx/default.conf.template",
    "nginx/api.conf", "Dockerfile.artifact", "container/sandbox-entrypoint.sh", "sandbox/Dockerfile", "sandbox/package.json",
    "sandbox/package-lock.json", "sandbox/requirements.txt", "sandbox/requirements.lock", "sandbox/office/main.py", "sandbox/bin/office"]) {
    write(`agenteam/deploy/${name}`, "验证用内容");
  }
  write("agenteam/LICENSE", "验证用的公共许可原文");
  write("agenteam/NOTICE", "验证用的公共版权声明");
  write("agenteam/docs/deployment/images.md", "社区镜像安装说明");
  write("agenteam/docs/deployment/enterprise-messaging.md", "共有的企业消息安装说明");
  // 外层文档、私有仓库与本地部署凭据均真实存在，复制结果不能包含它们。
  write("agenteam-pro/private.txt", "不可进入公共镜像");
  write("dev-docs/private.md", "不可进入镜像的维护资料");
  write("agenteam/deploy/.env", "DB_PASSWORD=不复制的验证密码");
  const targets = communityTargets("0.2.0");
  const configuration = {RELEASE_TAG: "0.2.0", EDITION: "community", REGISTRY_NAMESPACE: "docker.io/stonewuu",
    APP_IMAGE: targets.backend, FRONTEND_IMAGE: targets.frontend, EXECUTOR_IMAGE: targets.executor,
    SANDBOX_IMAGE: targets.office, DEPLOYMENT_IMAGE: targets.deployment,
    GATEWAY_IMAGE: "docker.io/library/nginx:1.30.5-alpine", MYSQL_IMAGE: "docker.io/library/mysql:8.4.11",
    REDIS_IMAGE: "docker.io/library/redis:7.4.9-alpine"};
  const releaseFile = write("agenteam/deploy/registry/release.env",
    Object.entries(configuration).map(([key, value]) => `${key}=${value}`).join("\n"));
  const options = {community, frontend, backend: write("application.jar", "仅用于输入复制的测试文件"),
    output: resolve(root, "output"), releaseFile, edition: "community", version: "0.2.0", targets,
    sourceCommit: "a".repeat(40), communityCommit: "a".repeat(40)};
  return {root, write, options};
}

function documents(value) {
  const directory = resolve(value.root, "third-party");
  const contents = "验证用的第三方原始声明";
  const digest = sha256(contents);
  const notice = `notices/${digest}.txt`;
  value.write(`third-party/${notice}`, contents);
  value.write("third-party/README.md", "逐组件查看原声明");
  const binding = (root, names) => Object.fromEntries(names.map((name) =>
    [name, sha256(readFileSync(resolve(root, name), "utf8").replaceAll("\r\n", "\n"))]));
  const index = {formatVersion: 1, releaseApproved: false,
    applicationArtifact: {sha256: sha256(readFileSync(value.options.backend))},
    components: [{family: "java", component: "验证组件", notices: [{file: notice, sha256: digest}]}],
    withoutAttributions: [], metadataOnly: [], uniqueNoticeFiles: 1,
    inputBindings: {
      frontend: {platform: "linux", architecture: "x64", files: binding(value.options.frontend,
        ["pnpm-lock.yaml", "pnpm-workspace.yaml"])},
      "office-node": {platform: "linux", architecture: "x64", files: binding(resolve(value.options.community, "deploy/sandbox"),
        ["package-lock.json"])},
      "office-python": {files: binding(resolve(value.options.community, "deploy/sandbox"), ["requirements.txt", "requirements.lock"])}
    }};
  const persist = () => value.write("third-party/index.json", JSON.stringify(index));
  persist();
  return {directory, notice, index, persist};
}

test("只复制声明的输入，保留版权文件，五类镜像同版本且没有外层或私有内容", () => {
  const {options} = fixture();
  const result = createImageInputs(options);
  verifyImageInputs(options.output, result);
  assert.equal(Object.keys(result.targets).length, 5);
  assert.deepEqual(Object.keys(result.contexts.deployment.files).sort(), ["compose.yaml", "compose.registry.yaml", "compose.single.yaml",
    "compose.shared-mysql.yaml", "compose.external-mysql.yaml",
    "registry/configure-server.py", "registry/nginx-host.conf.template", "registry/Dockerfile", "registry/README.md", "registry/release.env",
    "nginx/15-agenteam-config.sh", "nginx/default.conf.template", "nginx/api.conf", "docs/deployment/images.md",
    "docs/deployment/enterprise-messaging.md", "licenses/agenteam/LICENSE", "licenses/agenteam/NOTICE"].sort());
  assert.equal(readFileSync(resolve(options.output, "frontend/src/component.LICENSE"), "utf8"), "必须保留的第三方版权声明");
  assert.ok(!JSON.stringify(result).includes(options.community));
  assert.equal(readFileSync(resolve(options.output, "deployment/docs/deployment/images.md"), "utf8"), "社区镜像安装说明");
  assert.throws(() => createImageInputs(options), /停止覆盖/);
});

test("社区不能从商业目录取得安装文档", () => {
  const value = fixture();
  assert.throws(() => createImageInputs({...value.options, productRoot: resolve(value.root, "agenteam-pro")}), /所属的源码仓库/);
});

test("错误镜像仓库、旧版本标签及混入的密码字段均拒绝", () => {
  const {options} = fixture();
  const original = readFileSync(options.releaseFile, "utf8");
  for (const changed of [original.replace("APP_IMAGE=docker.io/stonewuu/agenteam-backend:0.2.0", "APP_IMAGE=private.example/backend:0.2.0"),
    original.replace("FRONTEND_IMAGE=docker.io/stonewuu/agenteam-frontend:0.2.0", "FRONTEND_IMAGE=docker.io/stonewuu/agenteam-frontend:latest"),
    original + "\nDB_PASSWORD=unexpected"]) {
    writeFileSync(options.releaseFile, changed);
    assert.throws(() => readReleaseConfiguration(options.releaseFile, options));
  }
});

test("输入被篡改、遗漏或加入临时文件后不能继续使用原清单", () => {
  const {options} = fixture();
  const result = createImageInputs(options);
  const file = resolve(options.output, "backend/app.jar");
  const original = readFileSync(file);
  writeFileSync(file, "改过的可执行包");
  assert.throws(() => verifyImageInputs(options.output, result), /已变化/);
  writeFileSync(file, original);
  const extra = resolve(options.output, "backend/private.txt");
  writeFileSync(extra, "未登记内容");
  assert.throws(() => verifyImageInputs(options.output, result), /未登记/);
  unlinkSync(extra);
  unlinkSync(file);
  assert.throws(() => verifyImageInputs(options.output, result), /缺少/);
});

test("源目录中的私钥和客户授权文件不能随前端资源进入镜像", () => {
  for (const name of ["private.pem", "customer.license", ".env.local"]) {
    const {write, options} = fixture();
    write(`agenteam/agenteam-web/public/${name}`, "不可分发的验证内容");
    assert.throws(() => createImageInputs(options), /不允许/);
  }
});

test("源文件和输出的目录链接不能把构建输入指向私有目录", () => {
  const first = fixture();
  symlinkSync(resolve(first.root, "agenteam-pro"), resolve(first.options.frontend, "public/linked"), "junction");
  assert.throws(() => createImageInputs(first.options), /不允许/);
  const second = fixture();
  symlinkSync(resolve(second.root, "agenteam-pro"), resolve(second.root, "linked-output"), "junction");
  assert.throws(() => createImageInputs({...second.options, output: resolve(second.root, "linked-output/output")}), /父目录/);
});

test("正式镜像要求资料绑定当前产物，四类构建输入都保留原文且不修改源码模板", () => {
  const value = fixture();
  assert.throws(() => createImageInputs({...value.options, release: true}), /必须附上/);
  const kit = documents(value);
  const result = createImageInputs({...value.options, release: true, thirdPartyDocuments: kit.directory});
  verifyImageInputs(value.options.output, result);
  for (const kind of ["backend", "frontend", "office", "deployment"]) {
    assert.equal(readFileSync(resolve(value.options.output, kind, `licenses/third-party/${kit.notice}`), "utf8"), "验证用的第三方原始声明");
  }
  assert.match(readFileSync(resolve(value.options.output, "frontend/Dockerfile"), "utf8"), /COPY licenses\//);
  assert.match(readFileSync(resolve(value.options.output, "frontend/.dockerignore"), "utf8"), /\n!licenses\/\n!licenses\/\*\*\n$/);
  assert.equal(readFileSync(resolve(value.options.frontend, "Dockerfile"), "utf8"), "验证用内容");
  assert.equal(readFileSync(resolve(value.options.frontend, ".dockerignore"), "utf8"), "验证用内容");
  assert.equal(result.thirdPartyDocuments.productIndexSha256, result.thirdPartyDocuments.sharedIndexSha256);
});

test("后端变化、依赖锁文件不符或声明内容变化时拒绝旧资料", () => {
  for (const kind of ["backend", "frontend", "office", "notice"]) {
    const value = fixture();
    const kit = documents(value);
    if (kind === "backend") {
      writeFileSync(value.options.backend, "另一份后端程序");
    } else if (kind === "frontend") {
      value.write("agenteam/agenteam-web/pnpm-lock.yaml", "变更后的依赖");
    } else if (kind === "office") {
      value.write("agenteam/deploy/sandbox/requirements.lock", "变更后的办公依赖");
    } else {
      value.write(`third-party/${kit.notice}`, "被替换的原文");
    }
    assert.throws(() => createImageInputs({...value.options, thirdPartyDocuments: kit.directory}), /不一致|另一个后端/);
  }
});

test("资料中的额外文件、越界原文路径和目录链接都拒绝", () => {
  const extra = fixture();
  const extraKit = documents(extra);
  extra.write("third-party/private.pem", "不应复制的内容");
  assert.throws(() => createImageInputs({...extra.options, thirdPartyDocuments: extraKit.directory}), /未登记/);
  const escaped = fixture();
  const escapedKit = documents(escaped);
  escapedKit.index.components[0].notices[0].file = "../agenteam-pro/private.txt";
  escapedKit.persist();
  assert.throws(() => createImageInputs({...escaped.options, thirdPartyDocuments: escapedKit.directory}), /路径或摘要/);
  const linked = fixture();
  const linkedKit = documents(linked);
  symlinkSync(linkedKit.directory, resolve(linked.root, "linked-documents"), "junction");
  assert.throws(() => createImageInputs({...linked.options, thirdPartyDocuments: resolve(linked.root, "linked-documents")}), /链接/);
});
