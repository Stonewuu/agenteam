"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Input} from "@/components/ui/input";

import {useState} from "react";
import {Dialog, DialogAction, DialogActions, DialogCancel} from "@/components/ui/dialog";
import {Pagination, QueryState} from "@/components/ui/query-state";
import {useApiPage} from "@/lib/http/use-api-query";
import ui from "@/components/ui/surface.module.css";
import styles from "./api-option-picker.module.css";

export type ApiOption = { id: string; name: string };

export function ApiOptionPicker({title, path, selected, clearLabel, onClose, onSelect}: {
  title: string;
  path: string;
  selected?: string | null;
  clearLabel?: string;
  onClose: () => void;
  onSelect: (value: ApiOption | null) => void;
}) {
  const uiText = useT();
  const [query, setQuery] = useState("");
  const list = useApiPage<ApiOption>(`${path}${path.includes("?") ? "&" : "?"}query=${encodeURIComponent(query)}`);
  return <Dialog title={title} onClose={onClose}>
    <Input type="search" className={ui.input} aria-label={uiText("搜索{0}", [title.replace(uiText("选择"), "")])}
           placeholder={uiText("按名称搜索")} maxLength={100} value={query}
           onChange={(event) => setQuery(event.target.value)}/>
    {clearLabel && <div className={styles.section}><DialogAction type="button" className={ui.button}
                                                                 onAction={(close) => close(() => onSelect(null))}>{clearLabel}</DialogAction>
    </div>}
    <QueryState {...list} hasData={Boolean(list.data?.items.length)}
                empty={query ? uiText("没有匹配的选项。") : uiText("暂无可选项。")}>
      <div className={styles.options}>{list.data?.items.map((item) => <DialogAction type="button" className={ui.button}
                                                                                    key={item.id}
                                                                                    aria-pressed={selected === item.id}
                                                                                    disabled={list.loading}
                                                                                    onAction={(close) => close(() => onSelect(item))}>{item.name}{selected === item.id ? uiText(" · 已选择") : ""}</DialogAction>)}</div>
    </QueryState><Pagination {...list} hasMore={list.data?.hasMore}/>
    <DialogActions className={ui.footer}><DialogCancel className={ui.button}
                                                       type="button">{uiText("取消")}</DialogCancel></DialogActions>
  </Dialog>;
}
