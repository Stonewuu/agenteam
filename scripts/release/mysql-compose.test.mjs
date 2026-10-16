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
  const files = ["compose.yaml", "compose.registry.yaml", "compose.single.yaml", ...extra];
  const args = ["compose", "--project-directory", deploy, "--env-file", resolve(deploy, "registry/release.env"),
    ...files.flatMap((file) => ["-f", resolve(deploy, file)]), "config",
    ...servicesOnly ? ["--services"] : ["--format", "json"]];
  // 明确覆盖所有凭据，不读取维护者真实 .env，也不启动或修改容器。
  const env = {...process.env, COMPOSE_FILE: "", COMPOSE_PROFILES: "", COMPOSE_PATH_SEPARATOR: ",",
    COMPOSE_PROJECT_NAME: `agenteam-compose-${role}`, DB_PASSWORD: `test-only-${role}-database`,
    DB_ROOT_PASSWORD: "test-only-root", REDIS_PASSWORD: `test-only-${role}-redis`,
    SANDBOX_TOKEN: `test-only-${role}-sandbox`, SETUP_CREDENTIAL: `test-only-${role}-setup`,
    SHARED_DATABASE_NETWORK: "agenteam-compose-test-database", SHARED_MYSQL_HOST: "agenteam-shared-mysql",
    SHARED_MYSQL_PORT: "3306", SHARED_MYSQL_DATABASE: "agenteam_community",
    SHARED_MYSQL_USERNAME: "agenteam_community", NETWORK_PREFIX: role === "client" ? "172.29.45" : "172.29.44",
    HTTP_PORT: role === "client" ? "8089" : "8088"};
  const output = execFileSync("docker", args, {env, encoding: "utf8", windowsHide: true,
    timeout: 30000, maxBuffer: 4 * 1024 * 1024});
  const result = servicesOnly ? output.trim().split(/\r?\n/) : JSON.parse(output);
  configurations.set(key, result);
  return result;
}

test("默认部署继续运行自己的 MySQL，保留新驱动和等待数据库的配置", () => {
  const standard = configuration([], "standard");
  assert.ok(standard.services.mysql);
  assert.equal(standard.services.backend.depends_on.mysql.condition, "service_healthy");
  assert.match(standard.services.backend.environment.AGENTEAM_DB_URL, /^jdbc:mariadb:\/\/mysql:3306\/agenteam\?/);
  assert.equal(standard.networks["shared-database"], undefined);
});

test("提供共享数据库只增加专用网络，原数据库账号及持久卷保持不变", () => {
  const before = configuration([], "provider");
  const provider = configuration(["compose.shared-mysql.yaml"], "provider");
  assert.deepEqual(provider.services.mysql.environment, before.services.mysql.environment);
  assert.deepEqual(provider.services.mysql.volumes, before.services.mysql.volumes);
  assert.deepEqual(provider.services.backend.environment, before.services.backend.environment);
  assert.equal(provider.networks["shared-database"].external, true);
  assert.ok(provider.services.mysql.networks["shared-database"].aliases.includes("agenteam-shared-mysql"));
  assert.deepEqual(Object.keys(provider.services.backend.networks), ["application"]);
});

test("连接共享数据库的部署不启动第二个 MySQL，缓存文件及入口各自独立", () => {
  const provider = configuration(["compose.shared-mysql.yaml"], "provider");
  const client = configuration(["compose.external-mysql.yaml"], "client");
  const services = configuration(["compose.external-mysql.yaml"], "client", true);
  assert.ok(!services.includes("mysql"));
  assert.equal(client.services.backend.depends_on.mysql, undefined);
  assert.deepEqual(Object.keys(client.services.backend.depends_on).sort(), ["redis", "storage-init"]);
  const env = client.services.backend.environment;
  assert.match(env.AGENTEAM_DB_URL, /^jdbc:mariadb:\/\/agenteam-shared-mysql:3306\/agenteam_community\?/);
  assert.equal(env.AGENTEAM_DB_USERNAME, "agenteam_community");
  assert.notEqual(env.AGENTEAM_DB_PASSWORD, provider.services.backend.environment.AGENTEAM_DB_PASSWORD);
  assert.equal(client.networks["shared-database"].name, provider.networks["shared-database"].name);
  for (const name of ["redis-data", "app-data", "user-workspaces", "executor-data"]) {
    assert.notEqual(client.volumes[name].name, provider.volumes[name].name);
  }
  assert.notEqual(client.services.redis.environment.REDIS_PASSWORD, provider.services.redis.environment.REDIS_PASSWORD);
  assert.notEqual(client.networks.application.ipam.config[0].subnet, provider.networks.application.ipam.config[0].subnet);
  assert.notEqual(client.services.nginx.ports[0].published, provider.services.nginx.ports[0].published);
});

test("低内存双站点共享执行名额，保留独立用户文件且常驻服务与沙盒总上限可放入四 GB 主机", () => {
  const provider = configuration(["compose.shared-mysql.yaml", "compose.low-memory.yaml"], "provider");
  const client = configuration(["compose.external-mysql.yaml", "compose.low-memory.yaml"], "client");
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
    const executor = site.services["sandbox-executor"];
    assert.equal(executor.environment.AGENTEAM_SANDBOX_CAPACITY_ROOT, "/var/lib/agenteam-capacity");
    assert.equal(executor.environment.AGENTEAM_SANDBOX_CGROUP_PARENT, "agenteam-sandboxes.slice");
    assert.ok(executor.volumes.some((volume) => volume.source === "sandbox-capacity"
      && volume.target === "/var/lib/agenteam-capacity"));
    assert.ok(site.services["storage-init"].command[0].includes("/sandbox-capacity"));
    assert.equal(site.volumes["sandbox-capacity"].external, true);
  }
  assert.equal(combined, 3136 * 1024 * 1024);
  assert.equal(provider.volumes["sandbox-capacity"].name, client.volumes["sandbox-capacity"].name);
  assert.notEqual(provider.volumes["user-workspaces"].name, client.volumes["user-workspaces"].name);
  assert.ok(!configuration(["compose.external-mysql.yaml", "compose.low-memory.yaml"], "client", true).includes("mysql"));
});
