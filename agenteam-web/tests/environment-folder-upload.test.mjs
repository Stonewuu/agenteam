import assert from "node:assert/strict";
import {test} from "node:test";
import {loadSource} from "./i18n-test-runtime.mjs";

const {droppedSources, selectedFiles, selectedFolder} = loadSource("features/environment/lib/environment-upload-selection");
const {uploadContents} = loadSource("features/environment/lib/environment-upload");
const api = loadSource("lib/http/api-client");

function folder(name, batches) {
  return {name, isDirectory: true, createReader() {
    let index = 0;
    return {readEntries(resolve) {
      resolve(batches[index++] ?? []);
    }};
  }};
}

test("拖入文件夹读取全部批次，确认后按相对路径传输，失败时只重试剩余文件", async context => {
  const calls = [];
  const opened = [];
  const localFile = (name, content) => ({name, isFile: true, file(resolve) {
    opened.push(content);
    resolve(new File([content], name));
  }});
  const firstBatch = Array.from({length: 100}, (_, i) => folder("目录" + i, []));
  const root = folder("项目资料", [firstBatch, [folder("甲", [[localFile("说明.txt", "A")]]), folder("乙", [[localFile("说明.txt", "B")]])]]);
  const [source] = droppedSources({files: [], items: [{kind: "file", webkitGetAsEntry: () => root}]});
  const signal = new AbortController().signal;
  const contents = await source.scan(signal);
  assert.equal(contents.directories.length, 103);
  assert.ok(contents.directories.includes("项目资料/目录99"));
  assert.deepEqual(contents.files.map(file => file.path).sort(), ["项目资料/乙/说明.txt", "项目资料/甲/说明.txt"].sort());
  assert.deepEqual(opened, []);
  let fail = true;
  context.mock.method(api, "apiRequest", async (url, options) => {
    calls.push({url, options});
    if (options.method === "POST") {
      return {created: fail ? 103 : 0};
    }
    if (fail && (await options.rawBody.text()) === "B") {
      throw new Error("同名文件");
    }
    return {};
  });
  const send = entries => uploadContents("/api/v1/test", "/workspace/当前目录", entries, signal, () => {}, "无法读取文件");
  const first = await send(contents);
  assert.equal(calls[0].options.body.path, "/workspace/当前目录");
  assert.deepEqual(calls[0].options.body.directories, contents.directories);
  assert.equal(first.uploaded, 1);
  assert.deepEqual(calls.slice(1).map(call => {
    const url = new URL(call.url, "http://test");
    return url.searchParams.get("path") + "/" + url.searchParams.get("name");
  }).sort(), contents.files.map(file => "/workspace/当前目录/" + file.path).sort());
  assert.deepEqual(first.remaining.files.map(file => file.path), ["项目资料/乙/说明.txt"]);
  assert.deepEqual(first.remaining.directories, ["项目资料", "项目资料/乙"]);
  fail = false;
  const second = await send(first.remaining);
  assert.equal(second.remaining, null);
  assert.equal(opened.filter(content => content === "A").length, 1);
});

test("选择空文件夹与拖入单个文件均保留原名，目录读取可取消", async () => {
  const root = {name: "空文件夹", kind: "directory", async *values() {}};
  const source = selectedFolder(root);
  assert.deepEqual(await source.scan(new AbortController().signal), {directories: ["空文件夹"], files: []});
  const controller = new AbortController();
  controller.abort();
  await assert.rejects(source.scan(controller.signal), {name: "AbortError"});
  const file = new File(["test"], "说明.txt");
  assert.equal(selectedFiles([file])[0].contents.files[0].path, "说明.txt");
  Object.defineProperty(file, "webkitRelativePath", {value: "资料/内部/说明.txt"});
  const [selected] = droppedSources({items: [], files: [file]});
  const contents = await selected.scan(new AbortController().signal);
  assert.deepEqual(contents.directories, ["资料", "资料/内部"]);
  assert.equal(await contents.files[0].read(), file);
});

test("只有空目录的文件夹按批次建目录，无需文件上传请求", async context => {
  const directories = ["交付", ...Array.from({length: 300}, (_, i) => "交付/空目录" + i)];
  const batches = [];
  context.mock.method(api, "apiRequest", async (url, options) => {
    assert.equal(options.method, "POST");
    assert.ok(url.endsWith("/files/upload-directories"));
    batches.push(options.body.directories);
    return {created: options.body.directories.length};
  });
  const result = await uploadContents("/api/v1/test", "/workspace", {directories, files: []},
    new AbortController().signal, () => {}, "");
  assert.deepEqual(batches.map(batch => batch.length), [256, 45]);
  assert.deepEqual(batches.flat(), directories);
  assert.equal(result.changed, true);
  assert.equal(result.remaining, null);
  assert.equal(result.uploaded, 0);
});
