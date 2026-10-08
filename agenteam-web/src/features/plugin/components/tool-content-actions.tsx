"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {useEffect, useRef, useState} from "react";
import {Button} from "@/components/ui/button";
import {Input} from "@/components/ui/input";
import {errorMessage} from "@/lib/http/api-client";
import {readFullToolContent, searchToolContent, toolContentDownload} from "../api/tool-content";
import type {ToolContentSource, ToolContentWindow} from "../types/tool-content";
import styles from "./tool-payload.module.css";

export function ToolContentActions({source, raw, document, locate, label: providedLabel}: {
  source: ToolContentSource;
  raw: boolean;
  document: ToolContentWindow;
  locate: (offset: number) => Promise<void>;
  label?: string;
}) {
  const uiText = useT();
  const label = providedLabel ?? uiText("工具内容");
  const [query, setQuery] = useState("");
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const [copied, setCopied] = useState(false);
  const position = useRef({query: "", nextLine: 1});
  const request = useRef<AbortController | null>(null);
  useEffect(() => () => request.current?.abort(), []);
  const start = () => {
    request.current?.abort();
    const controller = new AbortController();
    request.current = controller;
    setBusy(true);
    setNotice("");
    return controller;
  };
  const search = async () => {
    if (!query.trim()) {
      return;
    }
    const controller = start();
    try {
      const firstLine = position.current.query === query ? position.current.nextLine || 1 : 1;
      const found = await searchToolContent(source, raw, query, firstLine, document.revision, controller.signal);
      if (controller.signal.aborted) {
        return;
      }
      position.current = {query, nextLine: found.nextLine};
      if (found.matches.length) {
        await locate(found.matches[0].excerpt.startOffset);
        setNotice(uiText("第 {0} 行", [found.matches[0].line]));
      } else {
        setNotice(firstLine === 1 ? uiText("未找到匹配内容。") : uiText("已搜索到末尾，再次查找将从头开始。"));
      }
    } catch (error) {
      if (!controller.signal.aborted) {
        setNotice(errorMessage(error));
      }
    } finally {
      if (!controller.signal.aborted) {
        setBusy(false);
      }
    }
  };
  const copy = async () => {
    const controller = start();
    try {
      const content = await readFullToolContent(source, raw, controller.signal, document.revision);
      if (controller.signal.aborted) {
        return;
      }
      await navigator.clipboard.writeText(content);
      if (!controller.signal.aborted) {
        setCopied(true);
      }
    } catch (error) {
      if (!controller.signal.aborted) {
        console.error("复制文件内容失败", {path: source.url.split(/[?#]/)[0], part: source.part, error});
        setNotice(errorMessage(error));
      }
    } finally {
      if (!controller.signal.aborted) {
        setBusy(false);
      }
    }
  };
  return <div className={styles.readerActions}>
    <form onSubmit={(event) => {
      event.preventDefault();
      void search();
    }}>
      <Input aria-label={uiText("搜索") + label} value={query} maxLength={512} placeholder={uiText("搜索全文")}
             onChange={(event) => setQuery(event.target.value)}/>
      <Button type="submit" disabled={busy || !query.trim()}>{uiText("查找")}</Button>
    </form>
    <Button type="button" disabled={busy}
            onClick={() => void copy()}>{copied ? uiText("已复制") : uiText("复制全文")}</Button>
    <a href={toolContentDownload(source, raw)} download>{uiText("下载全文")}</a>
    {(notice || busy) && <span role="status">{busy ? uiText("正在读取…") : notice}</span>}
  </div>;
}
