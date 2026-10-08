"use client";

import {useState} from "react";

/** 输入框中的命令变化时同步搜索；直接编辑弹窗搜索框时保留自己的输入。 */
export function useSelectionQuery(initialQuery = "") {
  const [query, setQuery] = useState({source: initialQuery, value: initialQuery});
  if (query.source !== initialQuery) {
    setQuery({source: initialQuery, value: initialQuery});
  }
  return [query.value, (value: string) => setQuery({source: initialQuery, value})] as const;
}
