import type {ClipboardEvent} from "react";

/** 只接管实际文件，普通文字沿用输入框的粘贴行为。 */
export function pasteUpload(event: ClipboardEvent<HTMLTextAreaElement>, add: (files: File[]) => void, enabled: boolean) {
  if (!enabled) {
    return;
  }
  let files = Array.from(event.clipboardData.files);
  if (!files.length) {
    files = Array.from(event.clipboardData.items).flatMap((item) => {
      const file = item.kind === "file" ? item.getAsFile() : null;
      return file ? [file] : [];
    });
  }
  if (!files.length) {
    return;
  }
  event.preventDefault();
  add(files);
}
