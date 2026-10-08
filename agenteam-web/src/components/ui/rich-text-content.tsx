import {Fragment, type ReactNode} from "react";
import type {JSONContent} from "@tiptap/core";
import {richTextDocument, type RichTextValue, safeRichTextLink} from "./rich-text-document";
import styles from "./rich-text.module.css";

/** 按允许的节点生成界面，不将公告内容作为网页代码执行。 */
export function RichTextContent({value}: { value: RichTextValue }) {
  if (value.contentFormat !== "rich_text") {
    return <div className={`${styles.document} ${styles.plain}`}>{value.content}</div>;
  }
  return <div className={styles.document}>{renderNode(richTextDocument(value))}</div>;
}

function renderNode(node: JSONContent, depth = 0): ReactNode {
  if (depth > 16) {
    return null;
  }
  if (node.type === "text") {
    return (node.marks ?? []).reduce<ReactNode>((text, mark) => {
      switch (mark.type) {
        case "bold":
          return <strong>{text}</strong>;
        case "italic":
          return <em>{text}</em>;
        case "underline":
          return <u>{text}</u>;
        case "strike":
          return <s>{text}</s>;
        case "code":
          return <code>{text}</code>;
        case "link": {
          const href = typeof mark.attrs?.href === "string" ? mark.attrs.href : "";
          return safeRichTextLink(href) ? <a href={href} target="_blank" rel="noopener noreferrer">{text}</a> : text;
        }
        default:
          return text;
      }
    }, node.text ?? "");
  }
  const children = (node.content ?? []).map((child, index) => <Fragment
    key={index}>{renderNode(child, depth + 1)}</Fragment>);
  switch (node.type) {
    case "doc":
      return children;
    case "paragraph":
      return <p>{children.length ? children : <br/>}</p>;
    case "heading":
      return node.attrs?.level === 1 ? <h1>{children}</h1> : node.attrs?.level === 3 ? <h3>{children}</h3> :
        <h2>{children}</h2>;
    case "bulletList":
      return <ul>{children}</ul>;
    case "orderedList":
      return <ol start={Number.isInteger(node.attrs?.start) ? node.attrs?.start : 1}>{children}</ol>;
    case "listItem":
      return <li>{children}</li>;
    case "blockquote":
      return <blockquote>{children}</blockquote>;
    case "horizontalRule":
      return <hr/>;
    case "hardBreak":
      return <br/>;
    default:
      return null;
  }
}
