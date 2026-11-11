type PageTransition = { finished: Promise<void>; ready: Promise<void>; skipTransition: () => void };
type TransitionDocument = Document & { startViewTransition?: (update: () => Promise<void>) => PageTransition };

/** 等目标输入框完成布局后再拍摄新页面，让首次打开、需要加载的对话也能接上动画。 */
export function createComposerNavigationTransition() {
  let arrive: (() => void) | undefined;
  let active: PageTransition | undefined;
  let completed: Promise<void> = Promise.resolve();
  let timer: ReturnType<typeof setTimeout> | undefined;

  function release() {
    if (timer) {
      clearTimeout(timer);
      timer = undefined;
    }
    arrive?.();
    arrive = undefined;
  }

  return {
    async navigate(action: () => void) {
      const page = document as TransitionDocument;
      if (!page.startViewTransition || window.matchMedia("(prefers-reduced-motion: reduce)").matches
        || !page.querySelector('[data-composer-location="home"]')) {
        action();
        return;
      }
      active?.skipTransition();
      release();
      const destination = new Promise<void>((resolve) => {
        arrive = resolve;
      });
      page.documentElement.dataset.composerNavigation = "true";
      let navigated = false;
      const navigate = () => {
        if (!navigated) {
          navigated = true;
          action();
        }
      };
      try {
        active = page.startViewTransition(async () => {
          navigate();
          // 失败页或用户离开时仍放行导航，不能一直保留旧页面的截图。
          timer = setTimeout(release, 3000);
          await destination;
        });
        // 浏览器可能因标签页切走而跳过动画，导航结果仍然有效。
        completed = active.finished.catch((error: unknown) => {
          console.warn("Composer view transition did not finish; displaying the destination page", error);
        });
        void active.ready.catch((error: unknown) => {
          console.warn("Failed to play the composer view transition", error);
        });
        await completed;
      } catch (error) {
        console.warn("Failed to start the composer view transition; opening the conversation", error);
        navigate();
      } finally {
        release();
        active = undefined;
        delete page.documentElement.dataset.composerNavigation;
      }
    },
    arrived() {
      if (document.querySelector('[data-composer-location="conversation"]')) {
        release();
      }
    },
    finished() {
      return completed;
    },
    dispose() {
      release();
      active?.skipTransition();
      active = undefined;
      document.documentElement.removeAttribute("data-composer-navigation");
    },
  };
}
