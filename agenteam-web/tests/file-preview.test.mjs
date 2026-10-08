import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import ts from "typescript";

async function loadModule(path) {
  const source = await readFile(new URL(path, import.meta.url), "utf8");
  const compiled = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 } }).outputText;
  return import("data:text/javascript;base64," + Buffer.from(compiled).toString("base64"));
}

const { findFilePreviewFormat } = await loadModule("../src/features/file/lib/file-preview-formats.ts");
const { readFilePreviewDocument, maximumPreviewBytes } = await loadModule("../src/features/file/lib/file-preview-document.ts");
const { emptySidebarPanels, openSidebarPanel, closeSidebarPanel } = await loadModule("../src/features/agent/lib/conversation-sidebar-panels.ts");
const { buildHtmlPreviewDocument, htmlPreviewSandbox } = await loadModule("../src/features/file/lib/html-preview-document.ts");

const file = (name, previewKind = "text", overrides = {}) => ({ name, previewKind, mediaType: "text/plain", directory: false, ...overrides });
const page = (content, overrides = {}) => ({ content, revision: "正文版本一", sizeBytes: Buffer.byteLength(content), startOffset: 0, endOffset: Buffer.byteLength(content), eof: true, ...overrides });
const read = (load, limit) => readFilePreviewDocument(load, new AbortController().signal, limit);
const tab = (id, overrides = {}) => ({ id, label: id, closable: true, initialState: { mode: "preview" }, ...overrides });

test("后端将网页归为文本时仍识别网页，并忽略扩展名大小写", () => {
  for (const name of ["index.html", "示例.HTM", "index.HTML"]) {
    assert.equal(findFilePreviewFormat(file(name)).id, "html");
  }
  assert.equal(findFilePreviewFormat(file("index", "text", { mediaType: "Text/HTML; charset=utf-8" })).id, "html");
  assert.equal(findFilePreviewFormat(file("index.html.txt")).id, "text");
});

test("所有已有可预览格式有明确组件类型，目录和未知文件不显示预览入口", () => {
  for (const kind of ["markdown", "image", "video", "audio", "pdf", "text"]) {
    assert.equal(findFilePreviewFormat(file("文件", kind)).id, kind);
  }
  for (const kind of ["office", "unsupported"]) {
    assert.equal(findFilePreviewFormat(file("文件.html", kind)), undefined);
  }
  assert.equal(findFilePreviewFormat(file("目录.html", "text", { directory: true })), undefined);
});

test("现代办公文件提供预览，旧二进制格式和伪装文件保留下载", () => {
  for (const name of ["报告.docx", "数据.XLSX", "演示.pptx"]) {
    assert.equal(findFilePreviewFormat(file(name, "office")).id, "office");
  }
  for (const name of ["报告.doc", "数据.xls", "演示.ppt", "数据.xlsx.exe"]) {
    assert.equal(findFilePreviewFormat(file(name, "office")), undefined);
  }
});

test("完整读取跨片中文文档，后续请求必须携带第一次读取的正文版本", async () => {
  const parts = ["# 中文标题\n", "**正文**\n"];
  const total = Buffer.byteLength(parts.join(""));
  const calls = [];
  const result = await read(async (offset, revision) => {
    calls.push({ offset, revision });
    const content = parts[calls.length - 1];
    return page(content, { startOffset: offset, endOffset: offset + Buffer.byteLength(content), sizeBytes: total, eof: calls.length === 2 });
  });
  assert.equal(result, parts.join(""));
  assert.deepEqual(calls, [{ offset: 0, revision: undefined }, { offset: Buffer.byteLength(parts[0]), revision: "正文版本一" }]);
});

test("空文件是完整文档，不会反复发起读取", async () => {
  let count = 0;
  assert.equal(await read(async () => {
    count += 1;
    return page("");
  }), "");
  assert.equal(count, 1);
});

test("超过大小上限时拒绝渲染，包括读取期间文件增长的情况", async () => {
  await assert.rejects(read(async () => page("a", { sizeBytes: maximumPreviewBytes + 1, eof: false })), /文件较大/);
  assert.equal(await read(async () => page("abcd"), 4), "abcd");
  await assert.rejects(read(async () => page("abcde"), 4), /文件较大/);
});

test("片段不连续、内容缺字节、提前结束或没有向前读取时拒绝部分预览", async () => {
  for (const invalid of [
    page("x", { startOffset: 1 }),
    page("中文", { endOffset: 2 }),
    page("x", { sizeBytes: 10 }),
    page("", { sizeBytes: 10, eof: false }),
    page("x", { eof: false }),
    page("x", { sizeBytes: -1 }),
  ]) {
    await assert.rejects(read(async () => invalid), /重新加载/);
  }
});

test("文件在两次读取之间变化时，不能拼接两个版本", async () => {
  for (const changed of [{ revision: "正文版本二" }, { sizeBytes: 3 }]) {
    await assert.rejects(read(async (offset) => offset === 0
      ? page("a", { sizeBytes: 2, eof: false })
      : page("b", { sizeBytes: 2, startOffset: 1, endOffset: 2, ...changed })), /文件内容已经变化/);
  }
});

test("接口失败保留原始异常，不把已有片段当作完整文件返回", async () => {
  const failure = new Error("文件访问权限已失效");
  await assert.rejects(read(async (offset) => {
    if (offset > 0) {
      throw failure;
    }
    return page("a", { sizeBytes: 2, eof: false });
  }), (error) => error === failure);
});

test("关闭预览后取消正在读取的请求，不接受取消后返回的正文", async () => {
  const controller = new AbortController();
  await assert.rejects(readFilePreviewDocument(async () => {
    controller.abort();
    return page("已取消");
  }, controller.signal), { name: "AbortError" });
  await assert.rejects(readFilePreviewDocument(async () => {
    assert.fail("取消后不应发起请求");
  }, controller.signal), { name: "AbortError" });
});

test("重复打开同一标签保留用户当前模式，不创建重复标签", () => {
  const opened = openSidebarPanel(emptySidebarPanels(), tab("file:a"), "files");
  opened.states["file:a"] = { mode: "source" };
  const reopened = openSidebarPanel(opened, tab("file:a"), "context");
  assert.equal(reopened, opened);
  assert.equal(reopened.tabs.length, 1);
  assert.equal(reopened.states["file:a"].mode, "source");
});

test("关闭当前预览返回来源面板，同时释放标签和正文状态", () => {
  const opened = openSidebarPanel(emptySidebarPanels(), tab("file:a"), "context");
  const closed = closeSidebarPanel(opened, "file:a", "file:a", ["files", "context"]);
  assert.equal(closed.selected, "context");
  assert.deepEqual(closed.panels, emptySidebarPanels());
  assert.equal(opened.tabs.length, 1);
});

test("关闭其他标签不改变当前标签，来源标签已经关闭时仍能回到存在的面板", () => {
  let opened = openSidebarPanel(emptySidebarPanels(), tab("file:a"), "files");
  opened = openSidebarPanel(opened, tab("file:b"), "file:a");
  const closed = closeSidebarPanel(opened, "file:a", "file:b", ["files", "context"]);
  assert.equal(closed.selected, "file:b");
  assert.equal(closed.panels.tabs.length, 1);
  assert.equal(closeSidebarPanel(closed.panels, "file:b", "file:b", ["files", "context"]).selected, "context");
});

test("固定标签不能被关闭，新会话的状态不带入旧文件", () => {
  const opened = openSidebarPanel(emptySidebarPanels(), tab("fixed", { closable: false }), "files");
  assert.equal(closeSidebarPanel(opened, "fixed", "fixed", ["files"]).panels, opened);
  assert.deepEqual(emptySidebarPanels(), { tabs: [], states: {}, origins: {} });
});

test("网页策略先于不可信内容生效，文件脚本不会获得主页面或弹窗权限", () => {
  const content = '<!doctype html><html><head><meta http-equiv="Content-Security-Policy" content="default-src *"></head><body><script>parent.document.body.innerHTML="替换主页面";</script></body></html>';
  const document = buildHtmlPreviewDocument(content);
  assert.ok(document.indexOf("default-src 'none'") < document.indexOf(content));
  assert.ok(document.includes("connect-src 'none'"));
  assert.ok(document.includes("form-action 'none'"));
  assert.ok(document.includes("base-uri 'none'"));
  assert.ok(document.endsWith(content));
  assert.ok(htmlPreviewSandbox.split(" ").includes("allow-scripts"));
  for (const permission of ["allow-same-origin", "allow-popups", "allow-top-navigation", "allow-forms", "allow-downloads"]) {
    assert.ok(!htmlPreviewSandbox.split(" ").includes(permission));
  }
});

test("网页目录链接使用当前内嵌文档的地址，文件正文不能抢占基础地址", () => {
  const content = '<base href="https://example.com"><a href="#end">跳至结尾</a><h2 id="end">文档末尾</h2>';
  const document = buildHtmlPreviewDocument(content);
  assert.ok(document.indexOf('<base href="about:srcdoc">') < document.indexOf("base-uri 'none'"));
  assert.ok(document.indexOf("base-uri 'none'") < document.indexOf(content));
});
