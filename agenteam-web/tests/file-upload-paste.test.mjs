import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import vm from "node:vm";
import ts from "typescript";
import { chineseText, createTranslator, loadSource } from "./i18n-test-runtime.mjs";

const { pasteUpload } = loadSource("features/file/lib/paste-upload");
const { fileStatusText, fileFailureText } = loadSource("features/file/lib/file-status");

function clipboard(files = [], items = []) {
  let prevented = false;
  return { clipboardData: { files, items }, preventDefault() {
 prevented = true; 
}, get prevented() {
 return prevented; 
} };
}

test("多文件粘贴只添加一次，保留文件名、内容和顺序", () => {
  const files = [new File(["%PDF-1.7"], "说明.pdf", { type: "application/pdf" }), new File(["正文"], "记录.txt")];
  const event = clipboard(files, files.map(file => ({ kind: "file", getAsFile: () => file })));
  const calls = [];
  pasteUpload(event, value => calls.push(value), true);
  assert.equal(event.prevented, true);
  assert.equal(calls.length, 1);
  assert.deepEqual(calls[0], files);
  assert.equal(calls[0][0], files[0]);
});

test("兼容通过剪贴板条目提供文件的浏览器，忽略空条目和文字条目", () => {
  const file = new File(["正文"], "记录.txt");
  const event = clipboard([], [{ kind: "string" }, { kind: "file", getAsFile: () => null }, { kind: "file", getAsFile: () => file }]);
  const calls = [];
  pasteUpload(event, value => calls.push(value), true);
  assert.deepEqual(calls, [[file]]);
  assert.equal(event.prevented, true);
});

test("纯文字粘贴不被接管，禁用附件时不触发文件上传", () => {
  const calls = [];
  for (const [event, enabled] of [[clipboard([], [{ kind: "string" }]), true], [clipboard([new File(["正文"], "记录.txt")]), false]]) {
    pasteUpload(event, value => calls.push(value), enabled);
    assert.equal(event.prevented, false);
  }
  assert.deepEqual(calls, []);
});

function composer(overrides = {}) {
  const calls = [];
  const source = readFileSync(new URL("../src/features/agent/components/conversation-composer.tsx", import.meta.url), "utf8");
  const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.ReactJSX } }).outputText;
  const modules = {
    react: { useImperativeHandle() {} },
    "react/jsx-runtime": { jsx: (type, props) => ({ type, props }), jsxs: (type, props) => ({ type, props }) },
    "@/lib/i18n/locale-provider": { useT: () => chineseText },
    "@/features/file/lib/paste-upload": { pasteUpload },
    "../hooks/use-composer-input-size": { useComposerInputSize: () => ({ current: null }) },
    "../hooks/use-composer-layout": { useComposerLayout: () => ({ current: null }) },
  };
  const exports = {};
  vm.runInNewContext(compiled, { exports, require: name => modules[name] ?? new Proxy({ default: {} }, { get: (target, key) => key in target ? target[key] : String(key) }) });
  const tree = exports.ConversationComposer({ text: "保留已有草稿", skills: [], documents: [], model: {}, approvalPolicy: {},
    files: { add: value => calls.push(value), items: [], fileIds: [] }, employeeReady: true, attachmentsEnabled: true, submitting: false, ...overrides });
  function visit(node) {
    if (!node || typeof node !== "object") {
      return null;
    }
    if (node.type === "Textarea") {
      return node;
    }
    for (const child of [node.props?.children].flat(Infinity)) {
      const result = visit(child);
      if (result) {
        return result;
      }
    }
    return null;
  }
  return { input: visit(tree).props, calls };
}

test("实际对话输入框接入粘贴上传，并保留已有文字", () => {
  const state = composer();
  const file = new File(["内容"], "附件.txt");
  state.input.onPaste(clipboard([file]));
  assert.deepEqual(state.calls, [[file]]);
  assert.equal(state.input.value, "保留已有草稿");
});

test("对话提交中、员工未就绪或不接收附件时，粘贴不能绕过原有禁用条件", () => {
  for (const options of [{ submitting: true }, { employeeReady: false }, { attachmentsEnabled: false }]) {
    const state = composer(options);
    state.input.onPaste(clipboard([new File(["内容"], "附件.txt")]));
    assert.deepEqual(state.calls, []);
  }
});

test("已上传的无文字 PDF 如实说明，损坏与加密文件显示各自原因，英文同步翻译", () => {
  const en = createTranslator("en");
  assert.equal(fileStatusText({ status: "ready", errorCode: "FILE_NO_TEXT" }), "已上传，未提取到文字");
  assert.equal(fileStatusText({ status: "scanning", errorCode: null }), "正在处理文件…");
  for (const code of ["FILE_NO_TEXT", "FILE_ENCRYPTED", "FILE_TYPE_INVALID", "FILE_PROCESSING_TIMEOUT", "FILE_SCAN_UNAVAILABLE"]) {
    const message = fileFailureText({ status: "rejected", errorCode: code });
    assert.notEqual(message, "文件未通过检查，请重新选择文件。");
    assert.doesNotMatch(en(message), /[\u4e00-\u9fff]/);
  }
  assert.equal(en(fileStatusText({ status: "ready", errorCode: "FILE_NO_TEXT" })), "Uploaded; no text extracted");
});
