import {sourceText, type TextFormatter} from "../../../lib/i18n/format";

export function formatFileSize(bytes: number, t: TextFormatter = sourceText) {
  if (bytes < 1024) {
    return t("{0} 字节", [bytes]);
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`;
  }
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}
