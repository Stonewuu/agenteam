import {en} from "./messages/en";
import type {Translator} from "./translate";

const messages = Object.entries(en).filter(([key]) => /[\u3400-\u9fff]/.test(key));
const originals = new Map(messages.map(([key, value]) => [value, key]));
const templates = messages.flatMap(([key, translated]) => [key, translated].map((source) => {
  const indexes: string[] = [];
  const pieces = source.split(/(\{\d+\})/g).map((piece) => {
    const placeholder = /^\{(\d+)\}$/.exec(piece);
    if (placeholder) {
      indexes.push(placeholder[1]);
      return "([\\s\\S]*?)";
    }
    return piece.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  });
  return {key, source, indexes, pattern: new RegExp(`^${pieces.join("")}$`)};
})).filter((value) => value.indexes.length > 0 && value.source.length < 500)
  .sort((left, right) => right.source.replace(/\{\d+\}/g, "").length - left.source.replace(/\{\d+\}/g, "").length);

/** 仅用于应用生成的提示和校验消息；不用于用户内容、文件正文或模型回复。 */
export function localizeUiMessage(message: string, t: Translator): string {
  if (!message) {
    return message;
  }
  if (Object.hasOwn(en, message)) {
    return t(message);
  }
  const original = originals.get(message);
  if (original) {
    return t(original);
  }
  for (const template of templates) {
    const matched = template.pattern.exec(message);
    if (!matched) {
      continue;
    }
    const values: Record<string, string> = {};
    let consistent = true;
    template.indexes.forEach((index, position) => {
      if (Object.hasOwn(values, index) && values[index] !== matched[position + 1]) {
        consistent = false;
      }
      values[index] = matched[position + 1];
    });
    if (consistent) {
      return t(template.key, values);
    }
  }
  return message;
}
