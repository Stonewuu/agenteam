"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {useEffect, useRef, useState} from "react";
import {Tooltip} from "@base-ui/react/tooltip";
import {Button} from "@/components/ui/button";
import {IconCheck, IconClock, IconCopy} from "@/components/ui/icons";
import {toast} from "@/components/ui/toast";
import {ApiError, errorMessage} from "@/lib/http/api-client";
import {readFullToolContent} from "../api/tool-content";
import {formatRawToolPayload} from "../lib/tool-payload-format";
import type {ToolContentSource} from "../types/tool-content";
import styles from "./tool-payload.module.css";

type CopyStatus = "idle" | "copying" | "copied";

export function ToolPayloadCopyButton({title, value, source, raw}: {
  title: string; value: string; source?: ToolContentSource; raw: boolean;
}) {
  const uiText = useT();
  const [feedback, setFeedback] = useState({source, raw, value, status: "idle" as CopyStatus});
  const request = useRef<AbortController | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const status = feedback.source === source && feedback.raw === raw && feedback.value === value ? feedback.status : "idle";
  useEffect(() => () => {
    request.current?.abort();
    request.current = null;
    if (timer.current) {
      clearTimeout(timer.current);
    }
  }, [source, raw, value]);

  const copy = async () => {
    if (request.current) {
      return;
    }
    const controller = new AbortController();
    request.current = controller;
    if (timer.current) {
      clearTimeout(timer.current);
    }
    setFeedback({source, raw, value, status: "copying"});
    try {
      if (!navigator.clipboard) {
        throw new Error(uiText("当前浏览器暂时无法复制。"));
      }
      if (source) {
        const content = readFullToolContent(source, raw, controller.signal);
        if (typeof ClipboardItem !== "undefined" && typeof navigator.clipboard.write === "function") {
          // 在点击时开始写入剪贴板，正文按需读取后交付，保留浏览器要求的用户操作关联。
          const blob = content.then((text) => {
            controller.signal.throwIfAborted();
            return new Blob([text], {type: "text/plain"});
          });
          const write = async () => {
            await navigator.clipboard.write([new ClipboardItem({"text/plain": blob})]);
          };
          await Promise.all([blob, write()]);
        } else {
          const text = await content;
          controller.signal.throwIfAborted();
          await navigator.clipboard.writeText(text);
        }
      } else {
        await navigator.clipboard.writeText(raw ? formatRawToolPayload(value) : value);
      }
      if (controller.signal.aborted) {
        return;
      }
      setFeedback({source, raw, value, status: "copied"});
      timer.current = setTimeout(() => setFeedback({source, raw, value, status: "idle"}), 1800);
    } catch (error) {
      if (controller.signal.aborted) {
        return;
      }
      controller.abort();
      console.error("复制工具内容失败", {
        path: source?.url.split(/[?#]/)[0],
        part: source?.part,
        requestId: error instanceof ApiError ? error.requestId : undefined,
        error
      });
      setFeedback({source, raw, value, status: "idle"});
      toast.error(error instanceof ApiError ? errorMessage(error) : uiText("暂时无法复制，请稍后重试。"));
    } finally {
      if (request.current === controller) {
        request.current = null;
      }
    }
  };
  const label = status === "copying" ? uiText("正在复制…") : status === "copied" ? uiText("已复制") : uiText("复制{0}", [title]);
  return <Tooltip.Root>
    <Tooltip.Trigger delay={350} render={<Button type="button" className={styles.copyButton}
                                                 aria-label={label} aria-busy={status === "copying"}
                                                 disabled={status === "copying"}
                                                 data-copy-state={status} onClick={() => void copy()}>
      {status === "copied" ? <IconCheck size={16}/> : status === "copying" ? <IconClock size={16}/> :
        <IconCopy size={16}/>}
    </Button>}/>
    <Tooltip.Portal><Tooltip.Positioner side="top" align="end" sideOffset={6} className={styles.modeTooltipPositioner}>
      <Tooltip.Popup className={`agenteam-glass ${styles.modeTooltip}`}>{label}</Tooltip.Popup>
    </Tooltip.Positioner></Tooltip.Portal>
  </Tooltip.Root>;
}
