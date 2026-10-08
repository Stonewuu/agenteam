"use client";

import {useEffect, useState} from "react";
import type {BlockStatus} from "../types/execution";

/** 结束后保留两秒；手动操作、重新执行或新的确认请求都会取消旧计时。 */
export function useExecutionDisclosure(status: BlockStatus, pendingIds: string[] = [], defaultOpen = true) {
  const phase = status === "waiting_approval" || pendingIds.length > 0 ? "confirmation"
    : ["completed", "failed", "cancelled", "skipped"].includes(status) ? "finished" : "active";
  const [state, setState] = useState({
    phase, pendingIds,
    open: phase === "confirmation" || phase === "active" && defaultOpen, completion: 0, autoClose: false
  });

  if (state.phase !== phase || state.pendingIds.join("|") !== pendingIds.join("|")) {
    const ended = phase === "finished" && state.phase !== "finished";
    const reopen = (phase === "confirmation" || phase === "active" && defaultOpen) && state.phase !== phase
      || pendingIds.some((id) => !state.pendingIds.includes(id));
    setState({
      phase,
      pendingIds,
      open: reopen || state.open,
      completion: state.completion + (ended ? 1 : 0),
      autoClose: ended ? state.open : phase === "finished" && state.autoClose,
    });
  }

  const {autoClose, completion} = state;
  useEffect(() => {
    if (!autoClose) {
      return;
    }
    const timer = window.setTimeout(() => {
      setState((current) => current.autoClose && current.completion === completion
        ? {...current, open: false, autoClose: false} : current);
    }, 2000);
    return () => window.clearTimeout(timer);
  }, [autoClose, completion]);

  function setOpen(open: boolean) {
    setState((current) => ({...current, open, autoClose: false}));
  }

  return {open: state.open, setOpen, finished: phase === "finished"};
}
