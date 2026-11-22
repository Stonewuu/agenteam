export type ToolGlowRect = {left: number; top: number; width: number; height: number};

/** 完整可见的边允许光晕向外绘制，被滚动区域遮住的边不再向外发光。 */
export function toolGlowLayout(card: ToolGlowRect, layer: ToolGlowRect, viewports: ToolGlowRect[], padding: number) {
  if (card.width <= 0 || card.height <= 0) {
    return null;
  }
  let left = card.left;
  let top = card.top;
  let right = card.left + card.width;
  let bottom = card.top + card.height;
  for (const viewport of viewports) {
    left = Math.max(left, viewport.left);
    top = Math.max(top, viewport.top);
    right = Math.min(right, viewport.left + viewport.width);
    bottom = Math.min(bottom, viewport.top + viewport.height);
  }
  if (right <= left || bottom <= top) {
    return null;
  }
  const insets = [top - card.top, card.left + card.width - right, card.top + card.height - bottom, left - card.left]
    .map((inset) => inset <= 0.5 ? -padding : inset);
  return {
    left: card.left - layer.left,
    top: card.top - layer.top,
    width: card.width,
    height: card.height,
    clipPath: `inset(${insets.map((value) => `${value}px`).join(" ")})`,
  };
}
