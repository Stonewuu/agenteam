import type {SelectionAnchor} from "@/components/ui/selection-surface";

export type ComposerSelectionContext = {
  anchor: SelectionAnchor;
  command?: { start: number; end: number; text: string };
  query?: string
};

/** 复制文本排版计算光标位置，不改变正在输入的内容。 */
function caretRectangle(input: HTMLTextAreaElement, position: number) {
  const style = getComputedStyle(input);
  const mirror = document.createElement("div");
  const caret = document.createElement("span");
  mirror.setAttribute("aria-hidden", "true");
  Object.assign(mirror.style, {
    position: "fixed",
    visibility: "hidden",
    left: "0",
    top: "0",
    whiteSpace: "pre-wrap",
    overflowWrap: "break-word",
    width: `${input.clientWidth}px`,
    boxSizing: "border-box"
  });
  for (const property of ["font-family", "font-size", "font-weight", "font-style", "line-height", "letter-spacing", "tab-size", "padding-top", "padding-left", "padding-right", "padding-bottom"]) {
    mirror.style.setProperty(property, style.getPropertyValue(property));
  }
  mirror.textContent = input.value.slice(0, position);
  caret.textContent = input.value.slice(position) || "\u200b";
  mirror.append(caret);
  document.body.append(mirror);
  try {
    const rect = input.getBoundingClientRect();
    const lineHeight = Number.parseFloat(style.lineHeight) || Number.parseFloat(style.fontSize) * 1.8;
    return new DOMRect(rect.left + input.clientLeft + caret.offsetLeft - input.scrollLeft, rect.top + input.clientTop + caret.offsetTop - input.scrollTop, 1, lineHeight);
  } finally {
    mirror.remove();
  }
}

export function readComposerCommand(value: string, end: number) {
  const prefix = value.slice(0, end);
  const match = /(?:^|\s)([/@])([^\s/@]*)$/.exec(prefix);
  if (!match) {
    return null;
  }
  const marker = match[1] as "/" | "@";
  const start = end - match[2].length - 1;
  return {marker, start, end, text: value.slice(start, end), query: match[2]};
}

export function composerCommand(input: HTMLTextAreaElement): {
  marker: "/" | "@";
  context: ComposerSelectionContext
} | null {
  const command = readComposerCommand(input.value, input.selectionStart);
  if (!command || input.selectionStart !== input.selectionEnd) {
    return null;
  }
  const {marker, start, end, text, query} = command;
  return {
    marker,
    context: {
      anchor: {contextElement: input, getBoundingClientRect: () => caretRectangle(input, start)},
      command: {start, end, text},
      query
    }
  };
}
