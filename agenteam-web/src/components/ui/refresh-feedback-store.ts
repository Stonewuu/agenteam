export const refreshFeedbackTimings = { delayMs: 250, minimumMs: 400, fadeMs: 180 };
export type RefreshFeedbackSnapshot = { phase: "hidden" | "visible" | "leaving"; busy: boolean };
type Scheduler = {
  now: () => number;
  after: (callback: () => void, delay: number) => number;
  cancel: (id: number) => void;
};
const idle: RefreshFeedbackSnapshot = { phase: "hidden", busy: false };

/** 合并同时刷新的区域，短请求不显示提示；收起过程中有新请求时延续原提示。 */
export function createRefreshFeedbackStore(
  scheduler: Scheduler = {
    now: () => performance.now(),
    after: (callback, delay) => window.setTimeout(callback, delay),
    cancel: (id) => window.clearTimeout(id),
  },
) {
  const sources = new Set<symbol>();
  const listeners = new Set<() => void>();
  let snapshot = idle;
  let timer: number | undefined;
  let shownAt = 0;
  const publish = (phase: RefreshFeedbackSnapshot["phase"], busy: boolean) => {
    if (phase === snapshot.phase && busy === snapshot.busy) {
      return;
    }
    snapshot = { phase, busy };
    listeners.forEach((listener) => listener());
  };
  const clear = () => {
    if (timer !== undefined) {
      scheduler.cancel(timer);
    }
    timer = undefined;
  };
  const hide = () => {
    publish("leaving", false);
    timer = scheduler.after(() => {
      timer = undefined;
      publish("hidden", false);
    }, refreshFeedbackTimings.fadeMs);
  };
  return {
    getSnapshot: () => snapshot,
    getServerSnapshot: () => idle,
    subscribe(listener: () => void) {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
    begin() {
      const source = Symbol();
      const wasBusy = sources.size > 0;
      sources.add(source);
      if (!wasBusy) {
        clear();
        if (snapshot.phase === "hidden") {
          publish("hidden", true);
          timer = scheduler.after(() => {
            timer = undefined;
            shownAt = scheduler.now();
            publish("visible", true);
          }, refreshFeedbackTimings.delayMs);
        } else {
          shownAt = scheduler.now();
          publish("visible", true);
        }
      }
      return () => {
        if (!sources.delete(source) || sources.size > 0) {
          return;
        }
        clear();
        if (snapshot.phase === "hidden") {
          publish("hidden", false);
        } else {
          publish("visible", false);
          const remaining = refreshFeedbackTimings.minimumMs - (scheduler.now() - shownAt);
          if (remaining > 0) {
            timer = scheduler.after(hide, remaining);
          } else {
            hide();
          }
        }
      };
    },
  };
}

export const refreshFeedbackStore = createRefreshFeedbackStore();
