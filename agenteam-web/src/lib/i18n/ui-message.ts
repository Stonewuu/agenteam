import {en} from "./messages/en";
import type {Translator} from "./translate";

const templates = Object.keys(en).filter((key) => /[\u3400-\u9fff]/.test(key) && /\{\d+\}/.test(key) && key.length < 500).map((key) => {
  const indexes: string[] = [];
  const pieces = key.split(/(\{\d+\})/g).map((piece) => {
    const placeholder = /^\{(\d+)\}$/.exec(piece);
    if (placeholder) {
      indexes.push(placeholder[1]);
      return "([\\s\\S]*?)";
    }
    return piece.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  });
  return {key, indexes, pattern: new RegExp(`^${pieces.join("")}$`)};
});

/** 仅用于应用生成的提示和校验消息；不用于用户内容、文件正文或模型回复。 */
export function localizeUiMessage(message: string, t: Translator): string {
  if (t.locale === "zh-CN" || !message) {
    return message;
  }
  if (Object.hasOwn(en, message)) {
    return t(message);
  }
  for (const template of templates) {
    const matched = template.pattern.exec(message);
    if (matched) {
      const values = Object.fromEntries(template.indexes.map((index, position) => [index, matched[position + 1]]));
      return t(template.key, values);
    }
  }
  return message;
}
