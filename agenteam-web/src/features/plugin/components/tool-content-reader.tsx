"use client";

import {localizeUiMessage} from "@/lib/i18n/ui-message";

import {useT} from "@/lib/i18n/locale-provider";

import {type MutableRefObject, type ReactNode, useCallback, useEffect, useLayoutEffect, useRef, useState} from "react";
import {Button} from "@/components/ui/button";
import {ApiError, errorMessage} from "@/lib/http/api-client";
import {readToolContent} from "../api/tool-content";
import type {ToolContentSource, ToolContentWindow, ToolReadingPosition} from "../types/tool-content";
import styles from "./tool-payload.module.css";
import {ToolContentActions} from "./tool-content-actions";

type PositionRef = MutableRefObject<ToolReadingPosition>;
type Props = {
  source: ToolContentSource;
  raw: boolean;
  positionRef: PositionRef;
  render: (value: string) => ReactNode;
  onReady: () => void;
  label?: string;
  showActions?: boolean;
  renderPreview?: (document: ToolContentWindow, fullContent: ReactNode) => ReactNode;
  initialDocument?: ToolContentWindow;
};

/** 只在详情已经展开时挂载；切换模式、收起或离开页面会取消请求并释放正文。 */
export function ToolContentReader(props: Props) {
  const uiText = useT();
  const {source, raw, positionRef, onReady, label = uiText("工具内容"), showActions = true} = props;
  const [first, setFirst] = useState<ToolContentWindow | null>(props.initialDocument ?? null);
  const [error, setError] = useState("");
  const [retry, setRetry] = useState(0);
  const [location, setLocation] = useState(0);
  const locating = useRef<AbortController | null>(null);
  useEffect(() => () => locating.current?.abort(), []);
  useEffect(() => {
    if (props.initialDocument && retry === 0) {
      return;
    }
    const controller = new AbortController();
    readToolContent(source, raw, positionRef.current.offset, false, controller.signal).then((page) => {
      if (!controller.signal.aborted) {
        setFirst(page);
      }
    }).catch((failure) => {
      if (!controller.signal.aborted) {
        setError(errorMessage(failure));
      }
    });
    return () => controller.abort();
  }, [source, raw, positionRef, retry, props.initialDocument]);
  useEffect(() => {
    if (first || error) {
      onReady();
    }
  }, [first, error, onReady]);
  if (error) {
    return <div className={styles.contentNotice} role="alert">{localizeUiMessage(error ?? "", uiText)}<Button
      onClick={() => {
        setError("");
        setRetry((value) => value + 1);
      }}>{uiText("重新加载")}</Button></div>;
  }
  if (!first) {
    return <div className={styles.contentNotice} role="status">{uiText("正在读取内容…")}</div>;
  }
  const locate = async (offset: number) => {
    locating.current?.abort();
    const controller = new AbortController();
    locating.current = controller;
    const page = await readToolContent(source, raw, offset, false, controller.signal, first.revision);
    if (controller.signal.aborted) {
      return;
    }
    positionRef.current = {offset, top: 0, left: 0};
    setFirst(page);
    setLocation((value) => value + 1);
  };
  const content = location === 0 && first.startOffset === 0 && first.eof ? props.render(first.content)
    : <ContinuousToolText key={location} source={source} raw={raw} first={first} positionRef={positionRef}
                          label={label}/>;
  return <>{showActions &&
    <ToolContentActions source={source} raw={raw} document={first} locate={locate} label={label}/>}
    {props.renderPreview ? props.renderPreview(first, content) : content}</>;
}

type Anchor = { offset: number; top: number; left: number; scrollTop: number; scrollLeft: number };
const maximumWindows = 5;

/** 原文连续滚动，内部只保留附近的少量分片，用户无需手动分页。 */
function ContinuousToolText({
                              source,
                              raw,
                              first,
                              positionRef,
                              label: providedLabel
                            }: Omit<Props, "render" | "onReady"> & { first: ToolContentWindow }) {
  const uiText = useT();
  const label = providedLabel ?? uiText("工具内容");
  const viewport = useRef<HTMLDivElement>(null);
  const nodes = useRef(new Map<number, HTMLSpanElement>());
  const windows = useRef([first]);
  const [pages, setPages] = useState([first]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const active = useRef<AbortController | null>(null);
  const [unavailable, setUnavailable] = useState(false);
  const anchor = useRef<Anchor | null>(null);
  const restored = useRef(false);
  const lastDirection = useRef<"before" | "after">("after");

  const point = useCallback((offset: number) => {
    const element = nodes.current.get(offset), box = viewport.current;
    if (!element?.firstChild || !box) {
      return null;
    }
    const range = document.createRange();
    range.setStart(element.firstChild, 0);
    range.collapse(true);
    const caret = range.getBoundingClientRect(), outer = box.getBoundingClientRect();
    return {
      offset, top: caret.top - outer.top + box.scrollTop, left: caret.left - outer.left + box.scrollLeft,
      scrollTop: box.scrollTop, scrollLeft: box.scrollLeft
    };
  }, []);

  const remember = useCallback(() => {
    const box = viewport.current;
    if (!box || windows.current.length === 0) {
      return;
    }
    let visible = point(windows.current[0].startOffset);
    for (const page of windows.current) {
      const candidate = point(page.startOffset);
      if (candidate && candidate.top <= box.scrollTop + 1 && (candidate.top < box.scrollTop - 1 || candidate.left <= box.scrollLeft + 1)) {
        visible = candidate;
      }
    }
    if (visible) {
      positionRef.current = {
        offset: visible.offset, top: Math.max(0, box.scrollTop - visible.top),
        left: Math.max(0, box.scrollLeft - (Math.abs(visible.top - box.scrollTop) < 2 ? visible.left : 0))
      };
    }
  }, [point, positionRef]);

  const load = useCallback(async (backwards: boolean) => {
    if (active.current) {
      return;
    }
    const current = windows.current, edge = backwards ? current[0].startOffset : current.at(-1)!.endOffset;
    if (backwards ? edge === 0 : edge >= first.sizeBytes) {
      return;
    }
    const controller = new AbortController();
    active.current = controller;
    lastDirection.current = backwards ? "before" : "after";
    setLoading(true);
    setError("");
    try {
      const page = await readToolContent(source, raw, edge, backwards, controller.signal, first.revision);
      if (controller.signal.aborted) {
        return;
      }
      if (page.startOffset === page.endOffset || (backwards ? page.endOffset !== edge : page.startOffset !== edge)) {
        throw new Error("内容位置已经变化，请重新展开查看。");
      }
      const joined = backwards ? [page, ...current] : [...current, page];
      const next = joined.length <= maximumWindows ? joined : backwards ? joined.slice(0, maximumWindows) : joined.slice(-maximumWindows);
      const retained = backwards ? current[0] : next[0];
      anchor.current = point(retained.startOffset);
      windows.current = next;
      setPages(next);
    } catch (failure) {
      if (!controller.signal.aborted) {
        if (failure instanceof ApiError && [401, 403, 404].includes(failure.status)) {
          windows.current = [];
          setPages([]);
          setUnavailable(true);
        }
        setError(errorMessage(failure));
      }
    } finally {
      if (active.current === controller) {
        active.current = null;
        if (!controller.signal.aborted) {
          setLoading(false);
        }
      }
    }
  }, [first.revision, first.sizeBytes, point, raw, source]);

  useLayoutEffect(() => {
    const box = viewport.current;
    if (!box) {
      return;
    }
    if (!restored.current) {
      box.scrollTop = positionRef.current.top;
      box.scrollLeft = positionRef.current.left;
      restored.current = true;
    } else if (anchor.current) {
      const previous = anchor.current, current = point(previous.offset);
      if (current) {
        box.scrollTop = previous.scrollTop + current.top - previous.top;
        box.scrollLeft = previous.scrollLeft + current.left - previous.left;
      }
      anchor.current = null;
    }
    remember();
  }, [pages, point, positionRef, remember]);

  useEffect(() => () => {
    active.current?.abort();
    active.current = null;
  }, []);
  useEffect(() => {
    const box = viewport.current;
    if (!box || active.current || error || unavailable) {
      return;
    }
    if (box.scrollHeight <= box.clientHeight + 4 && box.scrollWidth <= box.clientWidth + 4) {
      // 搜索定位到很短的末尾时先补上相邻原文，使用户仍能向前滚动。
      void load(windows.current.at(-1)!.eof);
    }
  }, [pages, load, error, unavailable]);

  const scroll = useCallback(() => {
    remember();
    const box = viewport.current;
    if (!box || active.current || error) {
      return;
    }
    const vertical = box.scrollHeight > box.clientHeight + 4;
    const start = vertical ? box.scrollTop : box.scrollLeft;
    const end = vertical ? box.scrollHeight - box.scrollTop - box.clientHeight : box.scrollWidth - box.scrollLeft - box.clientWidth;
    if (start < 80 && windows.current[0].startOffset > 0) {
      void load(true);
    } else if (end < 160) {
      void load(false);
    }
  }, [error, load, remember]);

  return <div className={styles.continuousContent}>
    {!unavailable && <div ref={viewport} className={styles.textViewport} tabIndex={0} role="region"
                          aria-label={raw ? uiText("完整原始调用内容") : uiText("完整") + label} onScroll={scroll}>
      <pre className={styles.continuousText}>{pages.map((page) => <span key={page.startOffset}
                                                                        data-content-offset={page.startOffset}
                                                                        ref={(element) => {
                                                                          if (element) {
                                                                            nodes.current.set(page.startOffset, element);
                                                                          } else {
                                                                            nodes.current.delete(page.startOffset);
                                                                          }
                                                                        }}>{page.content}</span>)}</pre>
    </div>}
    {loading && <div className={styles.readerStatus} role="status">{uiText("正在读取内容…")}</div>}
    {error &&
      <div className={styles.contentNotice} role="alert">{localizeUiMessage(error ?? "", uiText)}{!unavailable &&
        <Button onClick={() => void load(lastDirection.current === "before")}>{uiText("重试")}</Button>}</div>}
  </div>;
}
