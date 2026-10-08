import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import { fileURLToPath } from "node:url";
import { setTimeout as delay } from "node:timers/promises";

const suffix = randomUUID().slice(0, 8);
const network = `agenteam-proxy-check-${suffix}`;
const upstream = `${network}-upstream`;
const proxy = `${network}-nginx`;
const created = [];
let networkCreated = false;

function docker(...args) {
  return execFileSync("docker", args, { encoding: "utf8", timeout: 60_000 }).trim();
}

async function waitForProxy() {
  for (let attempt = 0; attempt < 40; attempt += 1) {
    if (docker("inspect", "--format", "{{.State.Health.Status}}", proxy) === "healthy") {
      return;
    }
    await delay(250);
  }
  assert.fail("测试代理没有按时启动");
}

try {
  docker("network", "create", network);
  networkCreated = true;
  const fixture = fileURLToPath(new URL("./proxy-upstream.mjs", import.meta.url));
  docker("run", "--detach", "--name", upstream, "--network", network,
    "--network-alias", "backend", "--network-alias", "frontend",
    "--mount", `type=bind,src=${fixture},dst=/proxy-upstream.mjs,readonly`,
    "node:24-bookworm-slim", "node", "/proxy-upstream.mjs");
  created.push(upstream);
  const configuration = fileURLToPath(new URL("../../deploy/nginx/", import.meta.url));
  docker("run", "--detach", "--name", proxy, "--network", network,
    "--publish", "127.0.0.1::80", "--publish", "127.0.0.1::81",
    "--env", "PUBLIC_URL=https://agenteam.example.test",
    "--mount", `type=bind,src=${configuration}15-agenteam-config.sh,dst=/etc/agenteam/start-nginx.sh,readonly`,
    "--mount", `type=bind,src=${configuration}default.conf.template,dst=/etc/nginx/agenteam.conf.template,readonly`,
    "--mount", `type=bind,src=${configuration}api.conf,dst=/etc/nginx/agenteam-api.conf,readonly`,
    "--entrypoint", "sh",
    "--health-cmd", "wget -q --spider http://127.0.0.1/nginx-health",
    "--health-interval", "1s", "--health-timeout", "2s", "--health-retries", "10",
    process.env.GATEWAY_TEST_IMAGE || "docker.io/library/nginx:1.30.5-alpine",
    "/etc/agenteam/start-nginx.sh", "nginx", "-g", "daemon off;");
  created.push(proxy);
  await waitForProxy();
  const web = `http://${docker("port", proxy, "80/tcp")}`;
  const api = `http://${docker("port", proxy, "81/tcp")}`;
  const response = await fetch(`${web}/api/v1/headers?key=a%2Fb`, {
    headers: { "X-Forwarded-For": "203.0.113.99", "X-Forwarded-Proto": "http", "X-Forwarded-Port": "9999", "X-Forwarded-Host": "untrusted.example", "Last-Event-ID": "event-42" },
    signal: AbortSignal.timeout(10_000),
  });
  assert.equal(response.status, 200);
  const echoed = await response.json();
  assert.equal(echoed.path, "/api/v1/headers?key=a%2Fb", "接口路径与查询参数应保持完整");
  assert.notEqual(echoed.headers["x-forwarded-for"], "203.0.113.99", "不能信任客户端伪造的来源地址");
  assert.equal(echoed.headers["x-forwarded-proto"], "https", "外层 HTTPS 的协议应由部署配置确定");
  assert.equal(echoed.headers["x-forwarded-host"], new URL(web).host);
  assert.equal(echoed.headers["x-forwarded-port"], undefined);
  assert.equal(echoed.headers["last-event-id"], "event-42");
  assert.equal(response.headers.getSetCookie().length, 2, "多个会话 Cookie 不能合并丢失");

  const range = await fetch(`${api}/api/v1/file`, { headers: { Range: "bytes=2-5" }, signal: AbortSignal.timeout(10_000) });
  assert.equal(range.status, 206);
  assert.equal(range.headers.get("content-range"), "bytes 2-5/10");
  assert.equal(range.headers.get("content-length"), "4");
  assert.equal(await range.text(), "2345");

  const stream = await fetch(`${web}/api/v1/stream`, { signal: AbortSignal.timeout(10_000) });
  assert.equal(stream.headers.get("x-accel-buffering"), "no");
  const reader = stream.body.getReader();
  const first = await reader.read();
  const firstText = new TextDecoder().decode(first.value);
  assert.ok(firstText.includes("first") && !firstText.includes("second"), "实时事件不应等到响应结束才一起到达");
  let remaining = "";
  for (;;) {
    const next = await reader.read();
    if (next.done) {
      break;
    }
    remaining += new TextDecoder().decode(next.value);
  }
  assert.ok(remaining.includes("second"));
  docker("restart", "--time", "5", proxy);
  await waitForProxy();
  // 自动分配的宿主机端口可能在容器重启后变化，重新读取实际入口。
  const restartedWeb = `http://${docker("port", proxy, "80/tcp")}`;
  const restarted = await fetch(`${restartedWeb}/nginx-health`, { signal: AbortSignal.timeout(10_000) });
  assert.equal(restarted.status, 204, "重启后应重新生成配置并恢复入口");
  console.log("官方镜像代理验证通过：双入口、完整路径、来源头、多个 Cookie、文件分段、实时事件与重启。");
} catch (failure) {
  console.error("代理验证失败：", failure);
  throw failure;
} finally {
  for (const name of created.reverse()) {
    docker("rm", "--force", name);
  }
  if (networkCreated) {
    docker("network", "rm", network);
  }
}
