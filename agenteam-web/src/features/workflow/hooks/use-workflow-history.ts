"use client";

import {useState} from "react";
import type {WorkflowConfig} from "@/features/resource/types/resource";

type History = {present: WorkflowConfig; past: WorkflowConfig[]; future: WorkflowConfig[]; key: string};

/** 历史属于完整文档，进入循环或切换面板不会丢失；外部草稿更新重新建立基准。 */
export function useWorkflowHistory(value: WorkflowConfig, write: (value: WorkflowConfig) => void) {
  const [history, setHistory] = useState<History>({present: value, past: [], future: [], key: ""});
  if (history.present !== value && JSON.stringify(history.present) !== JSON.stringify(value)) {
    setHistory({present: value, past: [], future: [], key: ""});
  }
  const commit = (next: WorkflowConfig, key = "") => {
    if (JSON.stringify(value) === JSON.stringify(next)) {
      return;
    }
    setHistory({present: next, past: key && key === history.key ? history.past : [...history.past.slice(-49), value], future: [], key});
    write(next);
  };
  const undo = (redo = false) => {
    const source = redo ? history.future : history.past;
    const next = source.at(-1);
    if (!next) {
      return;
    }
    setHistory({present: next, key: "", past: redo ? [...history.past, value] : source.slice(0, -1),
      future: redo ? source.slice(0, -1) : [...history.future, value]});
    write(next);
  };
  return {commit, undo, canUndo: history.past.length > 0, canRedo: history.future.length > 0,
    endEdit: () => setHistory((current) => current.key ? {...current, key: ""} : current)};
}
