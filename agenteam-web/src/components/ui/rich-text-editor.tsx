"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {useEffect, useState} from "react";
import {EditorContent, useEditor, useEditorState} from "@tiptap/react";
import type {JSONContent} from "@tiptap/core";
import StarterKit from "@tiptap/starter-kit";
import {Button} from "./button";
import {Input} from "./input";
import {Select} from "./select";
import {FieldErrorFeedback} from "./error-feedback";
import {AnimatedHeight} from "./animated-height";
import {safeRichTextLink} from "./rich-text-document";
import styles from "./rich-text.module.css";

export function RichTextEditor({initial, onChange, id, disabled = false, errorId, invalid = false}: {
  initial: JSONContent; onChange: (document: JSONContent) => void; id: string;
  disabled?: boolean; errorId?: string; invalid?: boolean;
}) {
  const uiText = useT();
  const [linkOpen, setLinkOpen] = useState(false);
  const [link, setLink] = useState("");
  const [linkError, setLinkError] = useState("");
  const editor = useEditor({
    immediatelyRender: false,
    extensions: [StarterKit.configure({
      heading: {levels: [1, 2, 3]}, codeBlock: false,
      link: {openOnClick: false, defaultProtocol: "https", isAllowedUri: safeRichTextLink},
    })],
    content: initial,
    editable: !disabled,
    editorProps: {
      attributes: {
        id,
        name: "content",
        role: "textbox",
        "aria-label": uiText("正文"),
        "aria-multiline": "true",
        tabindex: "0",
        "aria-invalid": String(invalid),
        "aria-describedby": errorId ?? "",
        class: `${styles.document} ${styles.editable}`,
      }
    },
    onUpdate: ({editor: current}) => onChange(current.getJSON()),
  });
  const state = useEditorState({
    editor, selector: ({editor: current}) => current ? {
      bold: current.isActive("bold"),
      italic: current.isActive("italic"),
      underline: current.isActive("underline"),
      strike: current.isActive("strike"),
      bullet: current.isActive("bulletList"),
      ordered: current.isActive("orderedList"),
      quote: current.isActive("blockquote"),
      link: current.isActive("link"),
      heading: current.isActive("heading") ? String(current.getAttributes("heading").level) : "0",
      undo: current.can().undo(),
      redo: current.can().redo(),
    } : null
  });
  useEffect(() => {
    // 保存时切换编辑状态，不触发正文变化或清除服务端校验提示。
    editor?.setEditable(!disabled, false);
  }, [editor, disabled]);
  const applyLink = () => {
    const href = link.trim();
    if (href && !safeRichTextLink(href)) {
      setLinkError(uiText("请填写完整的网页或邮件链接。"));
      document.getElementById(`${id}-link`)?.focus();
      return;
    }
    if (href) {
      editor?.chain().focus().extendMarkRange("link").setLink({href}).run();
    } else {
      editor?.chain().focus().extendMarkRange("link").unsetLink().run();
    }
    setLinkOpen(false);
    setLinkError("");
  };
  const tools = [
    {
      label: uiText("加粗"),
      text: <strong>B</strong>,
      active: state?.bold,
      run: () => editor?.chain().focus().toggleBold().run()
    },
    {
      label: uiText("斜体"),
      text: <em>I</em>,
      active: state?.italic,
      run: () => editor?.chain().focus().toggleItalic().run()
    },
    {
      label: uiText("下划线"),
      text: <u>U</u>,
      active: state?.underline,
      run: () => editor?.chain().focus().toggleUnderline().run()
    },
    {
      label: uiText("删除线"),
      text: <s>S</s>,
      active: state?.strike,
      run: () => editor?.chain().focus().toggleStrike().run()
    },
    {
      label: uiText("无序列表"),
      text: uiText("• 列表"),
      active: state?.bullet,
      run: () => editor?.chain().focus().toggleBulletList().run()
    },
    {
      label: uiText("有序列表"),
      text: uiText("1. 列表"),
      active: state?.ordered,
      run: () => editor?.chain().focus().toggleOrderedList().run()
    },
    {
      label: uiText("引用"),
      text: uiText("引用"),
      active: state?.quote,
      run: () => editor?.chain().focus().toggleBlockquote().run()
    },
    {
      label: uiText("链接"), text: uiText("链接"), active: state?.link, run: () => {
        setLink(editor?.getAttributes("link").href ?? "");
        setLinkError("");
        setLinkOpen((open) => !open);
      }
    },
  ];
  return <div className={styles.editor} data-invalid={invalid || undefined} data-disabled={disabled || undefined}>
    <div className={styles.toolbar} role="group" aria-label={uiText("正文排版")}>
      <Select aria-label={uiText("段落格式")} value={state?.heading ?? "0"} disabled={!editor || disabled}
              onChange={(event) => {
                const level = Number(event.target.value);
                if (level === 0) {
                  editor?.chain().focus().setParagraph().run();
                } else {
                  editor?.chain().focus().toggleHeading({level: level as 1 | 2 | 3}).run();
                }
              }}>
        <option value="0">{uiText("正文")}</option>
        <option value="1">{uiText("一级标题")}</option>
        <option value="2">{uiText("二级标题")}</option>
        <option value="3">{uiText("三级标题")}</option>
      </Select>
      {tools.map((tool) => <Button key={tool.label} type="button" className={styles.tool} aria-label={tool.label}
                                   title={tool.label}
                                   aria-pressed={Boolean(tool.active)} disabled={!editor || disabled}
                                   onMouseDown={(event) => event.preventDefault()}
                                   onClick={tool.run}>{tool.text}</Button>)}
      <Button type="button" className={styles.tool} aria-label={uiText("撤销")} title={uiText("撤销")}
              disabled={!state?.undo || disabled}
              onMouseDown={(event) => event.preventDefault()}
              onClick={() => editor?.chain().focus().undo().run()}>↶</Button>
      <Button type="button" className={styles.tool} aria-label={uiText("重做")} title={uiText("重做")}
              disabled={!state?.redo || disabled}
              onMouseDown={(event) => event.preventDefault()}
              onClick={() => editor?.chain().focus().redo().run()}>↷</Button>
    </div>
    <AnimatedHeight preserveControlShadows>{linkOpen && <div className={styles.linkPanel}>
      <div className={styles.linkRow}><Input id={`${id}-link`} aria-label={uiText("链接地址")}
                                             placeholder="https://example.com" value={link} disabled={disabled}
                                             autoFocus maxLength={2048} aria-invalid={Boolean(linkError)}
                                             aria-describedby={linkError ? `${id}-link-error` : undefined}
                                             onChange={(event) => {
                                               setLink(event.target.value);
                                               setLinkError("");
                                             }} onKeyDown={(event) => {
        if (event.key === "Enter") {
          event.preventDefault();
          applyLink();
        }
      }}/>
        <Button type="button" className={styles.tool} disabled={disabled} onClick={applyLink}>{uiText("确定")}</Button>
        <Button type="button" className={styles.tool} disabled={disabled} onClick={() => {
          editor?.chain().focus().extendMarkRange("link").unsetLink().run();
          setLinkOpen(false);
        }}>{uiText("移除链接")}</Button></div>
      <FieldErrorFeedback id={`${id}-link-error`} messages={linkError ? [linkError] : []}/>
    </div>}</AnimatedHeight>
    {editor ? <EditorContent editor={editor}/> : <div className={styles.editable}>{uiText("正在加载编辑器…")}</div>}
  </div>;
}
