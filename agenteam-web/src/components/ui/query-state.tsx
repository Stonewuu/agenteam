"use client";

import {localizeUiMessage} from "@/lib/i18n/ui-message";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";
import type {ReactNode} from "react";
import {LoadingState} from "./loading-state";
import {LoadingTransition} from "./loading-transition";
import {IconAlertCircle, IconChevronLeft, IconChevronRight, IconInbox, IconRefresh} from "./icons";
import styles from "./surface.module.css";

export function QueryState({
                             loading,
                             error,
                             empty,
                             retry,
                             children,
                             hasData,
                             loadingLayout = "rows",
                             loadingContent,
                             emptyContent,
                             contentClassName,
                             contentLayout = "stack"
                           }: {
  loading: boolean;
  error: string;
  empty: string;
  retry: () => void;
  children?: ReactNode;
  hasData: boolean;
  loadingLayout?: "rows" | "cards";
  loadingContent?: ReactNode;
  emptyContent?: ReactNode;
  contentClassName?: string;
  contentLayout?: "flow" | "stack";
}) {
  const uiText = useT();
  const state = hasData ? "ready" : error ? "error" : loading ? "loading" : "empty";
  return <LoadingTransition state={state} layout={contentLayout}
                            contentClassName={hasData ? contentClassName : undefined}>
    {state === "loading" ? loadingContent ??
      <LoadingState layout={loadingLayout}/> : state === "empty" && emptyContent ? emptyContent : !hasData ?
      <div className={styles.empty} data-query-empty>
        <span className={styles.emptyIcon}>{error ? <IconAlertCircle size={27} variant="Bulk"/> :
          <IconInbox size={27} variant="Bulk"/>}</span>
        {error ? <><p role="alert">{localizeUiMessage(error ?? "", uiText)}</p><Button className={styles.button}
                                                                                       type="button"
                                                                                       onClick={retry}><IconRefresh
            size={16}/>{uiText("重新加载")}</Button></>
          : loading ? <p role="status">{uiText("正在加载…")}</p> : <p>{empty}</p>}
      </div> : <>{(loading || error) && <div className={styles.queryStatus} aria-live="polite">{loading &&
        <p className={styles.loading}>{uiText("正在更新…")}</p>}
        {error && <div className={styles.feedback}><p className={styles.error}
                                                      role="alert">{localizeUiMessage(error ?? "", uiText)}</p><Button
          className={styles.button} type="button" onClick={retry}><IconRefresh size={16}/>{uiText("重新加载")}</Button>
        </div>}</div>}{children}</>}
  </LoadingTransition>;
}

export function Pagination({previous, hasMore, loading, back, next}: {
  previous: unknown[];
  hasMore?: boolean;
  loading: boolean;
  back: () => void;
  next: () => void
}) {
  const uiText = useT();
  if (!previous.length && !hasMore) {
    return null;
  }
  return <nav aria-label={uiText("列表翻页")} className={styles.pagination}><Button className={styles.button}
                                                                                    type="button"
                                                                                    disabled={loading || !previous.length}
                                                                                    onClick={back}><IconChevronLeft
    size={15}/>{uiText("上一页")}</Button>
    <Button className={styles.button} type="button" disabled={loading || !hasMore}
            onClick={next}>{uiText("下一页")}<IconChevronRight size={15}/></Button></nav>;
}
