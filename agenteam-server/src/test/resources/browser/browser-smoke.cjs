"use strict";

const assert = require("node:assert/strict");
const {createServer} = require("node:http");
const {mkdtemp, readFile, stat} = require("node:fs/promises");
const {tmpdir} = require("node:os");
const {join} = require("node:path");
const {execFile} = require("node:child_process");
const {promisify} = require("node:util");
const {readPage, options} = require("/opt/agenteam/browser/browser-cli.cjs");
const run = promisify(execFile);

async function timed(name, action) {
  const started = Date.now();
  process.stderr.write(`${name}开始\n`);
  try {
    return await action();
  } finally {
    process.stderr.write(`${name}结束，耗时 ${Date.now() - started} 毫秒\n`);
  }
}

async function main() {
  const directory = await mkdtemp(join(tmpdir(), "browser-check-"));
  await run("git", ["init", "--bare", join(directory, "git.git")]);
  await run("git", ["--git-dir", join(directory, "git.git"), "update-server-info"]);
  const server = createServer(async (request, response) => {
    const path = new URL(request.url, "http://localhost").pathname;
    if (path === "/redirect") {
      response.writeHead(302, {Location: "/page"}).end();
    } else if (path === "/denied") {
      response.writeHead(403).end("禁止读取");
    } else if (path.startsWith("/git.git/")) {
      try {
        const content = await readFile(join(directory, path));
        response.writeHead(200, {"Content-Type": "text/plain"}).end(content);
      } catch {
        response.writeHead(404).end();
      }
    } else {
      response.writeHead(200, {"Content-Type": "text/html; charset=utf-8"}).end(`<!doctype html><title>动态网页</title>
        <nav>无关导航</nav><main id="content"></main><script>setTimeout(() => {
          document.querySelector('main').innerHTML = '<h1>浏览器正文</h1><p>${"中文🙂".repeat(90)}</p><a href="/next">下一页</a>';
        }, 100)</script>`);
    }
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const origin = `http://127.0.0.1:${server.address().port}`;
  try {
    const settings = options(["read", `${origin}/redirect`, "--wait-for", "h1", "--wait-ms", "0", "--max-chars", "100"]);
    const first = await timed("首段读取", () => readPage(settings));
    assert.equal(first.title, "动态网页");
    assert.equal(first.url, `${origin}/page`);
    assert.equal(first.status, 200);
    assert.equal(first.isError, false);
    assert.ok(first.content.startsWith("浏览器正文"));
    assert.ok(!first.content.includes("无关导航"));
    assert.equal(first.truncated, true);
    assert.equal(first.nextStart, 100);
    assert.equal(first.links[0].url, `${origin}/next`);
    const remainder = await timed("续读", () => readPage({...settings, start: first.nextStart, maxChars: 10000}));
    const command = await timed("命令行全文读取", () => run("agenteam-browser", ["read", `${origin}/page`, "--wait-for", "h1", "--wait-ms", "0", "--max-chars", "10000"], {timeout: 30000}));
    const full = JSON.parse(command.stdout);
    assert.equal(full.isError, false);
    assert.equal(first.content + remainder.content, full.content);
    const denied = await timed("拒绝访问", () => readPage({...settings, url: `${origin}/denied`}));
    assert.equal(denied.isError, true);
    assert.equal(denied.status, 403);
    await assert.rejects(timed("选择器超时", () => readPage({...settings, selector: "#missing", timeout: 1000})));
    assert.throws(() => options(["read", "file:///etc/passwd"]));
    assert.throws(() => options(["read", origin, "--max-chars", "-1"]));
    await timed("Git 拉取", () => run("git", ["clone", `${origin}/git.git`, join(directory, "clone")], {timeout: 10000}));
    assert.ok((await stat(join(directory, "clone/.git"))).isDirectory());
    process.stdout.write("浏览器动态正文、跳转、续读、拒绝、超时及 Git HTTP 克隆检查通过。\n");
  } finally {
    server.closeAllConnections();
    server.close();
  }
}

main().catch((error) => {
  process.stderr.write(error.stack + "\n");
  process.exitCode = 1;
});
