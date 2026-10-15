import {spawn} from "node:child_process";
import {readdirSync} from "node:fs";
import {createRequire} from "node:module";
import {fileURLToPath} from "node:url";

const root = fileURLToPath(new URL("../", import.meta.url));
const require = createRequire(new URL("../package.json", import.meta.url));
const compiler = require.resolve("typescript/bin/tsc");
const available = readdirSync(new URL("./", import.meta.url)).filter((file) => file.endsWith(".test.mjs")).sort();
const selected = process.argv.length > 2 ? process.argv.slice(2) : available;
if (!selected.length || selected.some((file) => !available.includes(file)) || new Set(selected).size !== selected.length) {
  throw new Error("请选择 tests 目录内存在且不重复的测试文件。");
}

function run(args) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, args, {cwd: root, stdio: "inherit", windowsHide: true});
    child.once("error", reject);
    child.once("exit", (code, signal) => {
      if (code === 0) {
        resolve();
      } else {
        reject(new Error(`前端测试命令失败：${code ?? signal}`));
      }
    });
  });
}

// 每种编译输出只准备一次；商业组合只选择扩展相关用例时，无须重复准备共有用例。
if (selected.includes("conversation-protocol.test.mjs")) {
  await run([compiler, "-p", "tests/tsconfig.conversation.json", "--pretty", "false"]);
}
if (selected.some((file) => ["tool-payload.test.mjs", "tool-call-details.test.mjs"].includes(file))) {
  await run([compiler, "src/features/plugin/lib/tool-payload-format.ts", "src/features/plugin/lib/tool-call-details.ts",
    "--target", "ES2022", "--module", "commonjs", "--skipLibCheck", "--outDir", ".test-build/tool-payload", "--pretty", "false"]);
}
if (selected.includes("markdown-animation.test.mjs")) {
  await run([compiler, "src/components/ui/markdown-animation-nodes.ts", "--target", "ES2022", "--module", "commonjs",
    "--skipLibCheck", "--outDir", ".test-build/markdown-animation", "--pretty", "false"]);
}
await run(["--test", "--test-reporter=tap", "--test-concurrency=4", ...selected.map((file) => `tests/${file}`)]);
