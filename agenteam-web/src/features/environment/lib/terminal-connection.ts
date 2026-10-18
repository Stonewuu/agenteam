import {ApiError, apiRequest} from "@/lib/http/api-client";
import type {TerminalInput, TerminalView} from "../types/environment";

/** 有序发送输入，网络重试沿用原编号；输出由独立读取循环消费。 */
export class TerminalConnection {
  private request: TerminalInput;
  private controller = new AbortController();
  private sending = false;
  private pendingInput = "";
  private mutations: Promise<unknown> = Promise.resolve();
  private remoteSize = {cols: 0, rows: 0};
  private stopped = false;
  private opened = false;
  private openedPromise: Promise<void>;
  private resolveOpened!: () => void;
  private resizeTimer?: ReturnType<typeof setTimeout>;

  constructor(private base: string, cols: number, rows: number,
    private output: (bytes: Uint8Array, reset: boolean) => Promise<void>,
    private state: (view: TerminalView) => void, private failure: (error: unknown) => void) {
    this.request = {action: "open", id: crypto.randomUUID(), sequence: 0, cursor: 0, cols, rows};
    this.openedPromise = new Promise(resolve => {
      this.resolveOpened = resolve;
    });
  }

  private send(input: TerminalInput) {
    return apiRequest<TerminalView>(this.base + "/terminal", {
      method: "POST", body: input, signal: this.controller.signal, timeoutMs: 45000,
    });
  }

  async open() {
    try {
      const view = await this.send(this.request);
      this.request = {...this.request, id: view.id, sequence: view.sequence};
      this.remoteSize = {cols: view.cols, rows: view.rows};
      this.opened = true;
      this.resolveOpened();
      if (!this.stopped) {
        await this.consume(view);
        void this.poll();
      }
    } catch (failure) {
      this.resolveOpened();
      this.fail(failure);
    }
  }

  private async consume(view: TerminalView) {
    if (this.stopped) {
      return;
    }
    const bytes = Uint8Array.from(atob(view.output), char => char.charCodeAt(0));
    await this.output(bytes, view.start > this.request.cursor);
    this.request.cursor = view.end;
    this.state(view);
    if (view.status === "closed" || view.status === "error") {
      this.stopped = true;
    }
  }

  private async poll() {
    try {
      while (!this.stopped) {
        await this.consume(await this.send({...this.request, action: "poll"}));
        if (!this.stopped) {
          await new Promise(resolve => setTimeout(resolve, 40));
        }
      }
    } catch (failure) {
      this.fail(failure);
    }
  }

  input(data: string) {
    if (this.stopped) {
      return;
    }
    if (this.pendingInput.length + data.length > 262144) {
      this.fail(new Error("粘贴内容过长，请分段输入。"));
      return;
    }
    this.pendingInput += data;
    if (!this.sending) {
      this.sending = true;
      void this.flushInput();
    }
  }

  private async flushInput() {
    try {
      await this.openedPromise;
      // 合并同一批键盘事件以及等待响应期间的新输入，避免逐字符请求积压。
      await new Promise(resolve => setTimeout(resolve, 20));
      while (this.pendingInput.length && !this.stopped) {
        let end = Math.min(8192, this.pendingInput.length);
        if (end < this.pendingInput.length && /[\uD800-\uDBFF]/.test(this.pendingInput[end - 1])) {
          end--;
        }
        const result = await this.mutate(async () => {
          await this.fitRemote();
          const request = {...this.request, action: "input" as const, sequence: this.request.sequence + 1, data: this.pendingInput.slice(0, end)};
          try {
            return await this.send(request);
          } catch (failure) {
            if (!(failure instanceof ApiError) || failure.code !== "NETWORK_ERROR") {
              throw failure;
            }
            return this.send(request);
          }
        });
        this.request.sequence = result.sequence;
        if (result.status === "closed" || result.status === "error" || result.status === "closing") {
          this.state(result);
          this.stopped = true;
          break;
        }
        this.pendingInput = this.pendingInput.slice(end);
      }
    } catch (failure) {
      this.fail(failure);
    } finally {
      this.sending = false;
    }
  }

  resize(cols: number, rows: number) {
    if (this.stopped) {
      return;
    }
    this.request.cols = Math.min(400, Math.max(20, cols));
    this.request.rows = Math.min(160, Math.max(5, rows));
    if (!this.opened) {
      return;
    }
    clearTimeout(this.resizeTimer);
    this.resizeTimer = setTimeout(() => {
      void this.mutate(() => this.fitRemote()).catch(error => this.fail(error));
    }, 180);
  }

  private mutate<T>(action: () => Promise<T>): Promise<T> {
    const result = this.mutations.then(action);
    this.mutations = result.catch(() => undefined);
    return result;
  }

  private async fitRemote() {
    const {cols, rows} = this.request;
    if (this.remoteSize.cols !== cols || this.remoteSize.rows !== rows) {
      await this.send({...this.request, action: "resize", cols, rows});
      this.remoteSize = {cols, rows};
    }
  }

  async close() {
    clearTimeout(this.resizeTimer);
    if (this.stopped) {
      return;
    }
    try {
      await this.openedPromise;
      if (!this.opened) {
        return;
      }
      let view = await this.send({...this.request, action: "close"});
      const deadline = Date.now() + 10000;
      while (view.status !== "closed" && view.status !== "error" && Date.now() < deadline) {
        await new Promise(resolve => setTimeout(resolve, 150));
        view = await this.send({...this.request, action: "poll"});
      }
      if (view.status !== "closed" && view.status !== "error") {
        throw new Error("终端正在关闭，请稍后重试。");
      }
      this.state(view);
    } finally {
      this.dispose();
    }
  }

  dispose() {
    this.stopped = true;
    clearTimeout(this.resizeTimer);
    this.controller.abort();
    this.resolveOpened();
  }

  private fail(failure: unknown) {
    if (!this.stopped && !this.controller.signal.aborted) {
      this.failure(failure);
      this.dispose();
    }
  }
}
