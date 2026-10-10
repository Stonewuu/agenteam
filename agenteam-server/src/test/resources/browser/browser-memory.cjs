"use strict";

const assert = require("node:assert/strict");
const {createServer} = require("node:http");
const {readFileSync, readdirSync, readlinkSync, mkdtempSync, writeFileSync} = require("node:fs");
const {join, resolve} = require("node:path");
const {tmpdir} = require("node:os");
const {randomUUID} = require("node:crypto");
const {execFile} = require("node:child_process");
const {promisify} = require("node:util");
const {setTimeout: delay} = require("node:timers/promises");
const {readPage, options} = require("/opt/agenteam/browser/browser-cli.cjs");
const run = promisify(execFile);

function counters(name) {
  return Object.fromEntries(readFileSync(`/sys/fs/cgroup/${name}`, "utf8").trim().split("\n")
    .map((line) => {
      const [key, value] = line.split(/\s+/);
      return [key, Number(value)];
    }));
}

function browserProcesses() {
  return readdirSync("/proc").filter((name) => /^\d+$/.test(name)).filter((pid) => {
    try {
      return /\/(?:chrome-headless-shell|headless_shell|chrome|chromium)$/.test(readlinkSync(`/proc/${pid}/exe`));
    } catch (error) {
      if (["ENOENT", "EACCES", "ESRCH"].includes(error.code)) {
        return false;
      }
      throw error;
    }
  });
}

async function until(check) {
  const deadline = Date.now() + 5000;
  while (!check()) {
    assert.ok(Date.now() < deadline, "浏览器进程状态未在预期时间内改变");
    await delay(25);
  }
}

async function main() {
  const directory = mkdtempSync(join(tmpdir(), "browser-memory-"));
  const runner = resolve("command_runner.py");
  const limit = Number(readFileSync("/sys/fs/cgroup/memory.max", "utf8").trim());
  assert.equal(limit, 512 * 1024 * 1024, "本用例必须在 512 MiB 上限内执行");
  let peak = 0;
  let peakAnonymous = 0;
  const observe = () => {
    peak = Math.max(peak, Number(readFileSync("/sys/fs/cgroup/memory.current", "utf8")));
    peakAnonymous = Math.max(peakAnonymous, counters("memory.stat").anon);
  };
  observe();
  const sample = setInterval(observe, 20);
  let pageRequests = 0;
  let unwantedRequests = 0;
  const server = createServer((request, response) => {
    const path = new URL(request.url, "http://localhost").pathname;
    if (["/popup", "/frame", "/image"].includes(path)) {
      if (path !== "/frame") {
        unwantedRequests += 1;
      }
      response.end("不需要加载的内容");
      return;
    }
    pageRequests += 1;
    response.writeHead(200, {"Content-Type": "text/html; charset=utf-8"});
    if (path === "/small") {
      response.end('<title>简短网页</title><main data-ready="yes">这是用于测量基本网页读取内存的正文。</main>');
      return;
    }
    if (path === "/memory-limit") {
      response.end(`<main>测试内存上限</main><script>
        const retained = [];
        setInterval(() => {
          const data = new Uint8Array(32 * 1024 * 1024);
          data.fill(1);
          retained.push(data);
        }, 10);
      </script>`);
      return;
    }
    const rows = "<p>" + "实际正文中文🙂 ".repeat(20) + "</p>";
    response.end(`<title>内存检查</title><main>${rows.repeat(5000)}</main><img src="/image">
      <iframe src="/frame"></iframe><script>
        for (let i = 0; i < 10; i += 1) {
          window.open('/popup', 'extra-' + i);
        }
        setTimeout(() => document.querySelector('main').setAttribute('data-ready','yes'), 50);
      </script>`);
  });
  await new Promise((done) => server.listen(0, "127.0.0.1", done));
  const origin = `http://127.0.0.1:${server.address().port}`;
  const settings = options(["read", origin, "--max-chars", "1000", "--wait-for", "[data-ready]", "--wait-ms", "0"]);
  try {
    const small = await readPage({...settings, url: origin + "/small"});
    assert.equal(small.isError, false);
    await until(() => browserProcesses().length === 0);
    const smallPagePeak = peak;
    const smallPageAnonymousPeak = peakAnonymous;
    peak = 0;
    peakAnonymous = 0;
    for (let iteration = 0; iteration < 3; iteration += 1) {
      const result = await readPage({...settings, start: iteration * 1000});
      assert.equal(result.isError, false);
      assert.equal(Array.from(result.content).length, 1000);
      assert.equal(result.nextStart, (iteration + 1) * 1000);
      await until(() => browserProcesses().length === 0);
    }
    assert.equal(unwantedRequests, 0, "正文读取不应加载图片或额外弹窗");
    for (const cancel of [false, true]) {
      const token = randomUUID();
      const script = join(directory, token + ".sh");
      writeFileSync(script, `agenteam-browser read '${origin}' --wait-ms 10000 --timeout-ms 15000\n`);
      const previousRequests = pageRequests;
      const executing = run("python3", [runner, "run", token, script, directory,
        join(directory, token + ".out"), join(directory, token + ".err"), cancel ? "20" : "3"]);
      // 同时等待实际页面请求，防止只取消了尚未启动浏览器的命令。
      await until(() => browserProcesses().length > 0 && pageRequests > previousRequests);
      if (cancel) {
        await run("python3", [runner, "cancel", token]);
      }
      await executing;
      const status = JSON.parse((await run("python3", [runner, "status", token])).stdout);
      assert.equal(status.state, "completed");
      assert.equal(status.timedOut, !cancel);
      assert.equal(status.exitCode, cancel ? 143 : 124);
      await until(() => browserProcesses().length === 0);
    }
    const regularPeak = peak;
    const regularAnonymousPeak = peakAnonymous;
    assert.ok(regularAnonymousPeak < 420 * 1024 * 1024, "常规正文读取应给 512 MiB 沙盒保留内存余量");
    const before = counters("memory.events").oom_kill;
    await assert.rejects(run("agenteam-browser", ["read", origin + "/memory-limit", "--wait-for", "#never",
      "--timeout-ms", "10000"]), (error) => {
      assert.equal(JSON.parse(error.stdout).isError, true);
      return true;
    });
    assert.ok(counters("memory.events").oom_kill > before, "必须实际触发容器的内存上限");
    await until(() => browserProcesses().length === 0);
    const recovered = await readPage(settings);
    assert.equal(recovered.isError, false, "超大网页失败后仍能执行下一次浏览器读取");
    await until(() => browserProcesses().length === 0);
    observe();
    process.stdout.write(JSON.stringify({limitBytes: limit, smallPagePeakBytes: smallPagePeak,
      smallPageAnonymousPeakBytes: smallPageAnonymousPeak, regularPeakBytes: regularPeak,
      regularAnonymousPeakBytes: regularAnonymousPeak, observedPeakBytes: peak,
      finalAnonymousBytes: counters("memory.stat").anon, remainingBrowserProcesses: browserProcesses().length}) + "\n");
    process.stdout.write("浏览器内存、连续读取、超时、取消与超大网页恢复检查通过。\n");
  } finally {
    clearInterval(sample);
    server.closeAllConnections();
    server.close();
  }
}

main().catch((error) => {
  process.stderr.write(error.stack + "\n");
  process.exitCode = 1;
});
