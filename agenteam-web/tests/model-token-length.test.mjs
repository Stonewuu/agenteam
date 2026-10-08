import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import ts from "typescript";

const source = await readFile(new URL("../src/features/modelprofile/lib/model-token-length.ts", import.meta.url), "utf8");
const compiled = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 } }).outputText;
const { parseTokenLength, formatTokenLength, tokenLengthError } = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString("base64")}`);

test("长度支持准确整数、千位分隔符以及 K/M 简写", () => {
  for (const [input, expected] of [["128K", 128000], [" 1m ", 1000000], ["1.024M", 1024000], ["131,072", 131072], ["131.072k", 131072], ["258000", 258000]]) {
    assert.equal(parseTokenLength(input), expected);
  }
});

test("清空、负数、错误分隔符和非整数不会转换成可保存的长度", () => {
  for (const input of ["", " ", "1,00", "-128", "1e6", "abc", "128.5", "0.0001K", "Infinity"]) {
assert.equal(parseTokenLength(input), null);
}
  assert.ok(tokenLengthError("", "上下文长度", 10000000));
  assert.ok(tokenLengthError("127", "上下文长度", 10000000));
  assert.ok(tokenLengthError("2M", "最大输出长度", 1000000));
  assert.equal(tokenLengthError("2M", "上下文长度", 10000000), undefined);
});

test("已有配置格式化后再读取保持原值，不按快捷选项取整", () => {
  for (const value of [8192, 32768, 65536, 128000, 131072, 258000, 1024000, 1048576, 10000000]) {
    assert.equal(parseTokenLength(formatTokenLength(value)), value);
  }
  assert.equal(formatTokenLength(null), "");
});
