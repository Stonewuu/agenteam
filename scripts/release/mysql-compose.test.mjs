import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import {dirname, resolve} from "node:path";
import {fileURLToPath} from "node:url";
import {test} from "node:test";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const deploy = resolve(root, "deploy");

const configurations = new Map();
function configuration(extra, role, servicesOnly = false) {
  const key = JSON.stringify([extra, role, servicesOnly]);
  if (configurations.has(key)) {
    return configurations.get(key);
  }
  const files = extra.length ? [resolve(root, "docker-compose.yaml"), ...extra.map((file) => resolve(deploy, "compose", file))] : [];
  const args = ["compose", ...(role === "example" ? ["--env-file", resolve(root, ".env.example")] : []),
    ...files.flatMap((file) => ["-f", file]), "config",
    ...(servicesOnly ? ["--services"] : ["--format", "json"])];
  // 只继承运行 Docker 命令所需的环境，避免个人部署参数改变渲染结果。
  const env = {...process.env, COMPOSE_DISABLE_ENV_FILE: "true", COMPOSE_PROFILES: "", COMPOSE_PATH_SEPARATOR: ",",
    COMPOSE_PROJECT_NAME: `agenteam-compose-${role}`, DB_PASSWORD: `test-only-${role}-database`,
    DB_ROOT_PASSWORD: "test-only-root", REDIS_PASSWORD: `test-only-${role}-redis`,
    SANDBOX_TOKEN: `test-only-${role}-sandbox`, SETUP_CREDENTIAL: `test-only-${role}-setup`,
    SHARED_DATABASE_NETWORK: "agenteam-compose-test-database", SHARED_MYSQL_HOST: "agenteam-shared-mysql",
    SHARED_MYSQL_PORT: "3306", SHARED_MYSQL_DATABASE: "agenteam_community",
    SHARED_MYSQL_USERNAME: "agenteam_community", NETWORK_PREFIX: role === "client" ? "172.29.45" : "172.29.44",
    HTTP_PORT: role === "client" ? "8089" : "8088"};
  for (const key of ["COMPOSE_FILE", "COMPOSE_ENV_FILES", "APP_IMAGE", "FRONTEND_IMAGE", "EXECUTOR_IMAGE", "SANDBOX_IMAGE", "MYSQL_IMAGE", "REDIS_IMAGE",
    "GATEWAY_IMAGE", "MYSQL_MEMORY", "BACKEND_MEMORY", "FRONTEND_MEMORY", "EXECUTOR_MEMORY"]) {
    delete env[key];
  }
  if (role === "standard" || role === "example") {
    for (const key of ["DB_PASSWORD", "DB_ROOT_PASSWORD", "REDIS_PASSWORD", "SANDBOX_TOKEN", "SETUP_CREDENTIAL"]) {
      delete env[key];
    }
  }
  const output = execFileSync("docker", args, {cwd: root, env, encoding: "utf8", windowsHide: true,
    timeout: 30000, maxBuffer: 4 * 1024 * 1024});
  const result = servicesOnly ? output.trim().split(/\r?\n/) : JSON.parse(output);
  configurations.set(key, result);
  return result;
}

test("根目录直接使用发布镜像，自动初始化凭据，常驻服务交由主机管理内存", () => {
  const standard = configuration([], "standard");
  assert.ok(standard.services.mysql);
  assert.equal(standard.services.backend.depends_on.mysql.condition, "service_healthy");
  assert.match(standard.services.backend.environment.AGENTEAM_DB_URL, /^jdbc:mariadb:\/\/mysql:3306\/agenteam\?/);
  assert.equal(standard.networks["shared-database"], undefined);
  for (const service of Object.values(standard.services)) {
    assert.equal(service.build, undefined);
    assert.equal(Number(service.mem_limit ?? 0), 0);
    assert.equal(service.memswap_limit, undefined);
  }
  for (const [name, image] of Object.entries({backend: "backend", "storage-init": "backend", frontend: "frontend",
    "sandbox-executor": "sandbox-server", "sandbox-image": "sandbox-office"})) {
    assert.equal(standard.services[name].image, `docker.io/stonewuu/agenteam-${image}:latest`);
  }
  assert.equal(standard.services["storage-init"].environment.DB_PASSWORD, "");
  assert.equal(standard.services.mysql.depends_on["storage-init"].condition, "service_completed_successfully");
  assert.equal(standard.services.redis.depends_on["storage-init"].condition, "service_completed_successfully");
  assert.equal(standard.services.mysql.environment.MYSQL_PASSWORD, undefined);
  assert.equal(standard.services.backend.environment.AGENTEAM_SETUP_CREDENTIAL, undefined);
  assert.ok(!standard.services.backend.volumes.some(volume => volume.source === "mysql-root-credential"));
  assert.ok(!standard.services.mysql.command.some(arg => /buffer-pool|max-connections/.test(arg)));
  assert.ok(!standard.services.redis.command.includes("--maxmemory"));
  assert.equal(standard.services.frontend.environment.NODE_OPTIONS, undefined);
  assert.equal(standard.services.backend.restart, "unless-stopped");
  const example = configuration([], "example");
  for (const [name, service] of Object.entries(standard.services)) {
    assert.equal(example.services[name].image, service.image);
    assert.equal(example.services[name].build, undefined);
    assert.equal(Number(example.services[name].mem_limit ?? 0), 0);
  }
});

test("提供共享数据库只增加专用网络，原数据库账号及持久卷保持不变", () => {
  const before = configuration([], "provider");
  const provider = configuration(["shared-mysql.yaml"], "provider");
  assert.deepEqual(provider.services.mysql.environment, before.services.mysql.environment);
  assert.deepEqual(provider.services.mysql.volumes, before.services.mysql.volumes);
  assert.deepEqual(provider.services.backend.environment, before.services.backend.environment);
  assert.equal(provider.networks["shared-database"].external, true);
  assert.ok(provider.services.mysql.networks["shared-database"].aliases.includes("agenteam-shared-mysql"));
  assert.deepEqual(Object.keys(provider.services.backend.networks), ["application"]);
});

test("连接共享数据库的部署不启动第二个 MySQL，缓存文件及入口各自独立", () => {
  const provider = configuration(["shared-mysql.yaml"], "provider");
  const client = configuration(["external-mysql.yaml"], "client");
  const services = configuration(["external-mysql.yaml"], "client", true);
  assert.ok(!services.includes("mysql"));
  assert.equal(client.services.backend.depends_on.mysql, undefined);
  assert.deepEqual(Object.keys(client.services.backend.depends_on).sort(), ["redis", "storage-init"]);
  const env = client.services.backend.environment;
  assert.match(env.AGENTEAM_DB_URL, /^jdbc:mariadb:\/\/agenteam-shared-mysql:3306\/agenteam_community\?/);
  assert.equal(env.AGENTEAM_DB_USERNAME, "agenteam_community");
  assert.notEqual(client.services["storage-init"].environment.DB_PASSWORD, provider.services["storage-init"].environment.DB_PASSWORD);
  assert.equal(client.networks["shared-database"].name, provider.networks["shared-database"].name);
  for (const name of ["redis-data", "app-data", "user-workspaces", "executor-data"]) {
    assert.notEqual(client.volumes[name].name, provider.volumes[name].name);
  }
  assert.notEqual(client.volumes["cache-credential"].name, provider.volumes["cache-credential"].name);
  assert.notEqual(client.networks.application.ipam.config[0].subnet, provider.networks.application.ipam.config[0].subnet);
  assert.notEqual(client.services.nginx.ports[0].published, provider.services.nginx.ports[0].published);
});

test("低内存双站点共享执行名额，保留独立用户文件且常驻服务与沙盒总上限可放入四 GB 主机", () => {
  const provider = configuration(["shared-mysql.yaml", "low-memory.yaml"], "provider");
  const client = configuration(["external-mysql.yaml", "low-memory.yaml"], "client");
  let combined = 640 * 1024 * 1024 + Number(provider.services.mysql.mem_limit);
  for (const site of [provider, client]) {
    for (const name of ["backend", "frontend", "sandbox-executor", "redis", "nginx"]) {
      assert.ok(site.services[name].mem_limit > 0);
      combined += Number(site.services[name].mem_limit);
    }
    for (const name of ["backend", "sandbox-executor"]) {
      const environment = site.services[name].environment;
      assert.equal(environment.AGENTEAM_SANDBOX_MEMORY_MB, "512");
      assert.equal(environment.AGENTEAM_SANDBOX_MAXIMUM_CONCURRENT, "1");
    }
    assert.equal(site.services.backend.environment.EXECUTION_WORKER_MAXIMUM_CONCURRENT, "1");
    assert.match(site.services.backend.environment.JAVA_TOOL_OPTIONS, /-Xmx256m/);
    assert.match(site.services.backend.environment.JAVA_TOOL_OPTIONS, /\+ExitOnOutOfMemoryError/);
    assert.equal(Number(site.services.backend.mem_limit), 640 * 1024 * 1024);
    const executor = site.services["sandbox-executor"];
    assert.equal(executor.environment.AGENTEAM_SANDBOX_CAPACITY_ROOT, "/var/lib/agenteam-capacity");
    assert.equal(executor.environment.AGENTEAM_SANDBOX_CGROUP_PARENT, "agenteam-sandboxes.slice");
    assert.ok(executor.volumes.some((volume) => volume.source === "sandbox-capacity"
      && volume.target === "/var/lib/agenteam-capacity"));
    assert.ok(site.services["storage-init"].command[0].includes("/sandbox-capacity"));
    assert.equal(site.volumes["sandbox-capacity"].external, true);
  }
  assert.equal(combined, 3392 * 1024 * 1024);
  assert.equal(provider.volumes["sandbox-capacity"].name, client.volumes["sandbox-capacity"].name);
  assert.notEqual(provider.volumes["user-workspaces"].name, client.volumes["user-workspaces"].name);
  assert.ok(!configuration(["external-mysql.yaml", "low-memory.yaml"], "client", true).includes("mysql"));
});

test("源码构建和双端口配置复用根目录服务，构建目录和网关端口可用", () => {
  const source = configuration(["source.yaml", "split.yaml"], "source");
  assert.equal(resolve(source.services.backend.build.context), root);
  assert.equal(resolve(source.services.frontend.build.context), resolve(root, "agenteam-web"));
  assert.equal(source.services.backend.image, "agenteam/backend:local");
  assert.deepEqual(source.services.nginx.ports.map(port => port.target).sort(), [80, 81]);
});
