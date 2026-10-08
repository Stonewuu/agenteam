import type {JSONContent} from "@tiptap/core";

export type RichTextValue = { content: string; contentFormat?: "plain_text" | "rich_text" };

export function richTextDocument(value: RichTextValue): JSONContent {
  if (value.contentFormat === "rich_text") {
    try {
      const document = JSON.parse(value.content) as JSONContent;
      if (document.type === "doc" && Array.isArray(document.content)) {
        return document;
      }
    } catch (failure) {
      console.error("读取富文本正文失败", failure);
    }
    return {type: "doc", content: [{type: "paragraph"}]};
  }
  return {
    type: "doc", content: value.content.split(/\r?\n/).map((text) => ({
      type: "paragraph", content: text ? [{type: "text", text}] : [],
    }))
  };
}

export function richTextPlainText(node: JSONContent, depth = 0): string {
  if (depth > 16) {
    return "";
  }
  if (node.type === "text") {
    return node.text ?? "";
  }
  if (node.type === "hardBreak") {
    return "\n";
  }
  const separator = ["doc", "blockquote", "bulletList", "orderedList", "listItem"].includes(node.type ?? "") ? "\n" : "";
  return (node.content ?? []).map((child) => richTextPlainText(child, depth + 1)).join(separator);
}

export function richTextPreview(value: RichTextValue): string {
  return value.contentFormat === "rich_text" ? richTextPlainText(richTextDocument(value)) : value.content;
}

export function safeRichTextLink(value: string): boolean {
  if (value.length > 2048 || /[\s\u0000-\u001f<>]/.test(value)) {
    return false;
  }
  try {
    const url = new URL(value);
    return (url.protocol === "https:" || url.protocol === "http:") && Boolean(url.hostname)
      || url.protocol === "mailto:" && url.pathname.includes("@");
  } catch (failure) {
    console.warn("校验富文本链接失败", failure);
    return false;
  }
}
