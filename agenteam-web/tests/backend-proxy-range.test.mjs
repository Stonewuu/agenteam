import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import ts from "typescript";

const source = (await readFile(new URL("../src/lib/server/backend-proxy.ts", import.meta.url), "utf8"))
  .replace('import "server-only";', "")
  .replace('import { NextResponse } from "next/server";', "const NextResponse = Response;")
  .replace('import { agentBackendUrl } from "./agent-backend";', 'const agentBackendUrl = (path: string) => "http://backend.test" + path;');
const compiled = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 } }).outputText;
const { proxyBackendRequest } = await import("data:text/javascript;base64," + Buffer.from(compiled).toString("base64"));

test("文件分段请求保留范围与版本，响应保留长度和正文", async () => {
  const original = globalThis.fetch;
  globalThis.fetch = async (url, options) => {
    assert.equal(options.headers.get("range"), "bytes=2-5");
    assert.equal(options.headers.get("if-range"), '"version"');
    return new Response("2345", { status: 206, headers: { "content-range": "bytes 2-5/10", "content-length": "4", "content-type": "video/mp4" } });
  };
  try {
    const response = await proxyBackendRequest(new Request("http://frontend.test/files", { headers: { Range: "bytes=2-5", "If-Range": '"version"' } }), "/files");
    assert.equal(response.status, 206);
    assert.equal(response.headers.get("content-length"), "4");
    assert.equal(response.headers.get("content-range"), "bytes 2-5/10");
    assert.equal(await response.text(), "2345");
  } finally {
    globalThis.fetch = original;
  }
});

test("解压后的正文不沿用压缩前长度", async () => {
  const original = globalThis.fetch;
  globalThis.fetch = async () => new Response("已解压的内容", { headers: { "content-encoding": "gzip", "content-length": "42" } });
  try {
    const response = await proxyBackendRequest(new Request("http://frontend.test/files"), "/files");
    assert.equal(response.headers.has("content-length"), false);
    assert.equal(response.headers.has("content-encoding"), false);
  } finally {
    globalThis.fetch = original;
  }
});
