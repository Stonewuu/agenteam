/** 保留可见字段的位置；错误字段不在可视区域时才滚动到它。 */
export function focusFormError(target: HTMLElement) {
  target.focus({preventScroll: true});
  const bounds = target.getBoundingClientRect();
  let top = 0;
  let bottom = window.innerHeight;
  for (let parent = target.parentElement; parent; parent = parent.parentElement) {
    const style = window.getComputedStyle(parent);
    if (!/(auto|scroll|hidden)/.test(style.overflowY)) {
      continue;
    }
    const viewport = parent.getBoundingClientRect();
    const header = parent.hasAttribute("data-page-scroll")
      ? Number.parseFloat(style.getPropertyValue("--page-header-height")) || 0 : 0;
    top = Math.max(top, viewport.top + header);
    bottom = Math.min(bottom, viewport.bottom);
  }
  if (bounds.top < top || bounds.bottom > bottom) {
    target.scrollIntoView({
      block: "nearest",
      behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth",
    });
  }
}
