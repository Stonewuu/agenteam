import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import vm from "node:vm";
import ts from "typescript";

const source = readFileSync(new URL("../src/features/agent/hooks/use-execution-disclosure.ts", import.meta.url), "utf8");
const compiled = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText;

// 执行实际折叠函数，使用可推进的时钟检查边界和计时取消，不等待真实两秒。
function disclosure(initialStatus = "running", defaultOpen = true) {
  const timers = new Map();
  let now = 0, nextId = 0, state, changed = false, dependencies, cleanup, effect;
  let status = initialStatus, pendingIds = [];
  const react = {
    useState(initial) {
      state ??= initial;
      return [state, (value) => {
        const next = typeof value === "function" ? value(state) : value;
        changed ||= next !== state;
        state = next;
      }];
    },
    useEffect(callback, values) {
      effect = { callback, values };
    },
  };
  const exports = {};
  vm.runInNewContext(compiled, {
    exports, require: () => react,
    window: {
      setTimeout(callback, delay) {
        timers.set(++nextId, { callback, at: now + delay });
        return nextId;
      },
      clearTimeout(id) {
        timers.delete(id);
      },
    },
  });
  function render(nextStatus = status, nextPendingIds = pendingIds) {
    status = nextStatus;
    pendingIds = nextPendingIds;
    let result;
    do {
      changed = false;
      result = exports.useExecutionDisclosure(status, pendingIds, defaultOpen);
    } while (changed);
    if (!dependencies || effect.values.some((value, index) => !Object.is(value, dependencies[index]))) {
      cleanup?.();
      dependencies = effect.values;
      cleanup = effect.callback();
    }
    return result;
  }
  return {
    render,
    advance(milliseconds) {
      const until = now + milliseconds;
      while (true) {
        const next = [...timers.entries()].sort((left, right) => left[1].at - right[1].at)[0];
        if (!next || next[1].at > until) {
          break;
        }
        now = next[1].at;
        timers.delete(next[0]);
        next[1].callback();
        render();
      }
      now = until;
      return render();
    },
    get timerCount() {
      return timers.size;
    },
    unmount() {
      cleanup?.();
    },
  };
}

test("所有结束状态均保留到两秒，再自动折叠一次", () => {
  for (const status of ["completed", "failed", "cancelled", "skipped"]) {
    const block = disclosure();
    assert.equal(block.render().open, true);
    assert.equal(block.render(status).open, true);
    assert.equal(block.advance(1999).open, true);
    assert.equal(block.advance(1).open, false);
    assert.equal(block.timerCount, 0);
  }
});

test("历史结束内容默认折叠，手动展开后不会重新计时", () => {
  const block = disclosure("completed");
  assert.equal(block.render().open, false);
  assert.equal(block.timerCount, 0);
  block.render().setOpen(true);
  assert.equal(block.advance(10000).open, true);
});

test("普通更新和等价确认列表不会重新开始两秒计时", () => {
  const block = disclosure();
  block.render();
  block.render("completed");
  block.advance(1000);
  block.render("completed", []);
  block.render("completed", []);
  assert.equal(block.timerCount, 1);
  assert.equal(block.advance(1000).open, false);
});

test("两秒内的手动操作取消自动折叠，也支持查看原始内容时保持展开", () => {
  for (const open of [false, true]) {
    const block = disclosure();
    block.render();
    block.render("completed");
    block.advance(1000);
    block.render().setOpen(open);
    assert.equal(block.render().open, open);
    assert.equal(block.timerCount, 0);
    assert.equal(block.advance(5000).open, open);
  }
});

test("重新执行会取消旧计时，下一次结束重新等待完整两秒", () => {
  const block = disclosure();
  block.render();
  block.render("completed");
  block.advance(1000);
  assert.equal(block.render("running").open, true);
  assert.equal(block.advance(1500).open, true);
  block.render("completed");
  assert.equal(block.advance(1999).open, true);
  assert.equal(block.advance(1).open, false);
});

test("新增确认展开内容并取消旧计时，确认结束后再计时", () => {
  const block = disclosure();
  block.render();
  block.render("completed");
  block.advance(1000);
  assert.equal(block.render("completed", ["approval-1"]).open, true);
  assert.equal(block.advance(5000).open, true);
  block.render().setOpen(false);
  block.render();
  assert.equal(block.render("completed", ["approval-1", "approval-2"]).open, true);
  block.render("completed", []);
  assert.equal(block.advance(1999).open, true);
  assert.equal(block.advance(1).open, false);
});

test("执行期间手动收起后，结束不会强行重新展开", () => {
  const block = disclosure();
  block.render().setOpen(false);
  block.render();
  assert.equal(block.render("completed").open, false);
  assert.equal(block.timerCount, 0);
});

test("卸载清理计时，初始待确认内容始终展开", () => {
  const block = disclosure();
  block.render();
  block.render("completed");
  assert.equal(block.timerCount, 1);
  block.unmount();
  assert.equal(block.timerCount, 0);
  const confirmation = disclosure("waiting_approval", false);
  assert.equal(confirmation.render().open, true);
  assert.equal(confirmation.timerCount, 0);
});
