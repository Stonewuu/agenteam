export const minimumSidebarWidth = 280;

/** 拖动期间只限制边界，不自动撑满；松开后再计算最终布局。 */
export function sidebarDragWidth(width: number, available: number) {
  return Math.max(minimumSidebarWidth, Math.min(Math.max(minimumSidebarWidth, available || 1200), width));
}

/** 松开时按对话最小宽度的一半决定收起或展开；展开至少恢复到对话最小宽度。 */
export function sidebarReleaseLayout(width: number, available: number) {
  const total = Math.max(minimumSidebarWidth, available || 1200);
  const current = sidebarDragWidth(width, total);
  const conversationMinimum = Math.max(400, total * 0.2);
  const conversationWidth = total - current;
  if (conversationWidth < conversationMinimum / 2) {
    return {width: total, full: true};
  }
  return {width: total - Math.max(conversationWidth, conversationMinimum), full: false};
}

/** 对话过窄时将文件侧栏展开到整个区域，恢复时仍采用此前的并排宽度。 */
export function sidebarLayout(width: number, available: number, full = false) {
  const total = Math.max(minimumSidebarWidth, available || 1200);
  const conversationMinimum = Math.max(400, total * 0.2);
  const maximumSplitWidth = Math.max(minimumSidebarWidth, total - conversationMinimum);
  const fullscreen = full || total - width < conversationMinimum;
  return {
    width: fullscreen ? total : Math.max(minimumSidebarWidth, Math.min(maximumSplitWidth, width)),
    full: fullscreen,
    maximumSplitWidth
  };
}
