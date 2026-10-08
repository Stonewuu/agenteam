type Size = { width: number; height: number };

const placeholderSelector = "[data-loading-placeholder], [data-workspace-page-loading], [data-workspace-loading]";
const readSize = (element: HTMLElement): Size => ({width: element.offsetWidth, height: element.offsetHeight});

/** 只处理加载状态切换；普通输入、流式内容和滚动不被整块动画干扰。 */
export function observeLoadingSize(container: HTMLElement, content: HTMLElement, {
  includeNestedLoading = false,
  fadeContent = true
}: {
  includeNestedLoading?: boolean;
  fadeContent?: boolean;
} = {}) {
  const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
  let explicitState: string | undefined;
  let previous: { state: string; size: Size } | undefined;
  let animation: Animation | undefined;
  let fade: Animation | undefined;
  let originalWidth: string | undefined;

  function state() {
    if (explicitState !== undefined) {
      return explicitState;
    }
    // 嵌套查询自行处理，页面容器只负责直接属于自己的骨架屏。
    return Array.from(content.querySelectorAll(placeholderSelector)).some((node) => includeNestedLoading || node.closest("[data-loading-transition]") === container) ? "loading" : "ready";
  }

  function cancel() {
    animation?.cancel();
    fade?.cancel();
    animation = undefined;
    fade = undefined;
    if (originalWidth !== undefined) {
      content.style.width = originalWidth;
      originalWidth = undefined;
    }
    delete container.dataset.sizeAnimating;
  }

  function measure(contentChanged = false) {
    if (!container.getClientRects().length) {
      return;
    }
    const nextState = state();
    if (container.ownerDocument?.documentElement.hasAttribute("data-composer-navigation")) {
      cancel();
      previous = {state: nextState, size: readSize(container)};
      return;
    }
    const size = readSize(container);
    const loadingResized = includeNestedLoading && nextState === "loading" && !animation && previous
      && (Math.abs(previous.size.width - size.width) > 1 || Math.abs(previous.size.height - size.height) > 1);
    const retarget = includeNestedLoading && animation && contentChanged;
    if (retarget && animation && previous) {
      // 弹层库会调整辅助节点；自然尺寸没变时继续原动画，不反复重置时钟。
      const effect = animation.effect;
      animation.effect = null;
      const natural = readSize(container);
      animation.effect = effect;
      if (Math.abs(previous.size.width - natural.width) <= 1 && Math.abs(previous.size.height - natural.height) <= 1) {
        return;
      }
    }
    if (!previous || previous.state === nextState && !loadingResized && !retarget) {
      if (!animation) {
        previous = {state: nextState, size: readSize(container)};
      }
      return;
    }

    // 快速重试或继续输入时，从当前显示的尺寸接续，不跳回上一次动画起点。
    const before = animation ? readSize(container) : previous.size;
    cancel();
    const after = readSize(container);
    previous = {state: nextState, size: after};
    if (reducedMotion.matches || typeof container.animate !== "function") {
      return;
    }
    const widthChanged = Math.abs(before.width - after.width) > 1;
    const heightChanged = Math.abs(before.height - after.height) > 1;
    if (!widthChanged && !heightChanged) {
      return;
    }
    const easing = getComputedStyle(container).getPropertyValue("--motion-ease").trim() || "ease-out";
    const from: Keyframe = {};
    const to: Keyframe = {};
    if (widthChanged) {
      // 内容保持目标宽度，防止容器过渡时反复换行，动画结束后恢复自然布局。
      if (content !== container) {
        originalWidth = content.style.width;
        content.style.width = `${content.offsetWidth}px`;
      }
      from.width = `${before.width}px`;
      to.width = `${after.width}px`;
    }
    if (heightChanged) {
      from.height = `${before.height}px`;
      to.height = `${after.height}px`;
    }
    container.dataset.sizeAnimating = "true";
    const current = container.animate([from, to], {duration: 220, easing});
    animation = current;
    if (fadeContent) {
      fade = content.animate([{opacity: 0, offset: 0}, {opacity: 0, offset: 0.35}, {
        opacity: 1,
        offset: 1
      }], {duration: 220, easing: "ease-out"});
    }
    current.onfinish = () => {
      if (animation !== current) {
        return;
      }
      cancel();
      previous = {state: state(), size: readSize(container)};
    };
  }

  // 既覆盖 React 状态更新，也覆盖路由等待区域替换子树的情况。
  const resize = new ResizeObserver(() => measure());
  const mutations = new MutationObserver(() => {
    if (explicitState === undefined) {
      measure(true);
    }
  });
  resize.observe(content);
  mutations.observe(content, {childList: true, subtree: true});
  const reduce = () => {
    if (reducedMotion.matches) {
      cancel();
      previous = {state: state(), size: readSize(container)};
    }
  };
  reducedMotion.addEventListener("change", reduce);
  return {
    update(value?: string) {
      explicitState = value;
      measure();
    },
    disconnect() {
      resize.disconnect();
      mutations.disconnect();
      reducedMotion.removeEventListener("change", reduce);
      cancel();
    },
  };
}
