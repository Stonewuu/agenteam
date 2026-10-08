"use client";

import {useLayoutEffect, useRef} from "react";

export function usePageHeaderMotion() {
  const frame = useRef<HTMLElement>(null);
  const surface = useRef<HTMLDivElement>(null);

  useLayoutEffect(() => {
    const heading = frame.current;
    const content = surface.current;
    const scroll = heading?.closest<HTMLElement>("[data-page-scroll]");
    if (!heading || !content || !scroll || heading.closest('[role="dialog"]')) {
      return;
    }

    const motion = window.matchMedia("(prefers-reduced-motion: reduce)");
    let progress = 0;
    let target = 0;
    let distance = 1;
    let animationFrame = 0;
    let measurementFrame = 0;
    let lastTime = 0;
    let width = 0;
    let active = true;

    const apply = () => {
      heading.style.setProperty("--header-progress", String(progress));
      heading.dataset.compact = String(progress > .995);
    };
    const animate = (time: number) => {
      const elapsed = lastTime ? Math.min(time - lastTime, 48) : 16;
      lastTime = time;
      progress += (target - progress) * (1 - Math.exp(-elapsed / 42));
      if (Math.abs(target - progress) < .001) {
        progress = target;
      }
      apply();
      animationFrame = progress === target ? 0 : requestAnimationFrame(animate);
    };
    const update = (immediate = false) => {
      target = Math.max(0, Math.min(1, scroll.scrollTop / distance));
      if (immediate || motion.matches) {
        cancelAnimationFrame(animationFrame);
        animationFrame = 0;
        progress = target;
        apply();
      } else if (!animationFrame && progress !== target) {
        lastTime = 0;
        animationFrame = requestAnimationFrame(animate);
      }
    };
    const measure = () => {
      measurementFrame = 0;
      if (!active) {
        return;
      }
      const previousTop = scroll.scrollTop;
      heading.dataset.measuring = "true";
      heading.dataset.ready = "true";
      heading.style.setProperty("--header-progress", "0");
      const actions = content.querySelector<HTMLElement>("[data-header-actions]");
      if (actions) {
        delete actions.dataset.empty;
        actions.dataset.empty = String(actions.getBoundingClientRect().height < 1);
      }
      const secondaries = [...heading.querySelectorAll<HTMLElement>("[data-header-secondary]")];
      for (const element of secondaries) {
        element.style.removeProperty("--secondary-height");
      }
      const expandedHeight = Math.ceil(content.getBoundingClientRect().height);
      const expandedTitle = titlePlacement(content);
      const origin = heading.getBoundingClientRect().top - scroll.getBoundingClientRect().top + scroll.scrollTop;
      for (const element of secondaries) {
        element.style.setProperty("--secondary-height", `${Math.ceil(element.getBoundingClientRect().height)}px`);
      }
      heading.style.setProperty("--header-progress", "1");
      heading.dataset.measuringCompact = "true";
      const compactHeight = Math.ceil(content.getBoundingClientRect().height);
      const compactTitle = titlePlacement(content);
      if (expandedTitle && compactTitle) {
        for (const [name, value] of Object.entries(expandedTitle)) {
          heading.style.setProperty(`--header-${name}-expanded`, `${value}px`);
        }
        for (const [name, value] of Object.entries(compactTitle)) {
          heading.style.setProperty(`--header-${name}-compact`, `${value}px`);
        }
      }
      const expanded = Math.max(expandedHeight, compactHeight);
      const difference = expanded - compactHeight;
      heading.style.setProperty("--header-expanded-height", `${expanded}px`);
      heading.style.setProperty("--header-collapse-height", `${difference}px`);
      scroll.style.setProperty("--page-header-height", `${compactHeight}px`);
      distance = Math.max(24, origin + difference);
      delete heading.dataset.measuring;
      delete heading.dataset.measuringCompact;
      scroll.scrollTop = previousTop;
      width = heading.getBoundingClientRect().width;
      update(true);
    };
    const requestMeasurement = () => {
      if (!measurementFrame) {
        measurementFrame = requestAnimationFrame(measure);
      }
    };
    const onScroll = () => update();
    const onMotionChange = () => update(true);
    const onRestored = () => update(true);
    const resize = new ResizeObserver(() => {
      if (Math.abs(heading.getBoundingClientRect().width - width) > .5) {
        requestMeasurement();
      }
    });
    // 仅观察文字和节点，滚动写入的样式不触发重新测量。
    const changes = new MutationObserver(requestMeasurement);
    changes.observe(content, {childList: true, characterData: true, subtree: true});
    resize.observe(heading);
    scroll.addEventListener("scroll", onScroll, {passive: true});
    scroll.addEventListener("page-scroll-restored", onRestored);
    motion.addEventListener("change", onMotionChange);
    window.addEventListener("resize", requestMeasurement);
    void document.fonts.ready.then(() => {
      if (active) {
        requestMeasurement();
      }
    });
    measure();

    return () => {
      active = false;
      cancelAnimationFrame(animationFrame);
      cancelAnimationFrame(measurementFrame);
      scroll.removeEventListener("scroll", onScroll);
      scroll.removeEventListener("page-scroll-restored", onRestored);
      motion.removeEventListener("change", onMotionChange);
      window.removeEventListener("resize", requestMeasurement);
      resize.disconnect();
      changes.disconnect();
      scroll.style.removeProperty("--page-header-height");
    };
  }, []);

  return {frame, surface};
}

function titlePlacement(content: HTMLElement) {
  const line = content.querySelector<HTMLElement>("[data-header-title-line]");
  const title = line?.querySelector("h1");
  const metadata = line?.querySelector<HTMLElement>("[data-header-metadata]");
  if (!line || !title || !metadata) {
    return null;
  }
  const row = line.getBoundingClientRect();
  const heading = title.getBoundingClientRect();
  const labels = metadata.getBoundingClientRect();
  return {
    "title-line-height": row.height, "title-width": heading.width, "title-y": heading.top - row.top,
    "metadata-x": labels.left - row.left, "metadata-y": labels.top - row.top, "metadata-width": labels.width
  };
}
