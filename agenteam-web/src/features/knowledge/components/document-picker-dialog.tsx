"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Input} from "@/components/ui/input";
import {Checkbox} from "@/components/ui/checkbox";

import type {RefObject} from "react";
import {useSelectionQuery} from "@/components/ui/use-selection-query";
import {SelectionAction, type SelectionAnchor, SelectionSurface} from "@/components/ui/selection-surface";
import {Pagination, QueryState} from "@/components/ui/query-state";
import {useApiPage} from "@/lib/http/use-api-query";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import type {DocumentOption} from "../types/knowledge";
import ui from "@/components/ui/surface.module.css";
import styles from "./knowledge.module.css";

export function DocumentPickerDialog({
                                       enterpriseId,
                                       agentId,
                                       conversationId,
                                       selected,
                                       onChange,
                                       onClose,
                                       onChooseEmployee,
                                       anchor,
                                       returnFocus,
                                       initialQuery = ""
                                     }: {
  enterpriseId: string; agentId: string; conversationId: string | null; selected: DocumentOption[];
  onChange: (value: DocumentOption[]) => void; onClose: () => void; onChooseEmployee: () => void;
  anchor?: SelectionAnchor | null; returnFocus?: RefObject<HTMLElement | null>; initialQuery?: string;
}) {
  const uiText = useT();
  const [query, setQuery] = useSelectionQuery(initialQuery);
  const list = useApiPage<DocumentOption>(organizationPath(enterpriseId, `/agents/${encodeURIComponent(agentId)}/input-options?kind=document&query=${encodeURIComponent(query)}${conversationId ? `&conversationId=${encodeURIComponent(conversationId)}` : ""}`));
  return <SelectionSurface title={uiText("引用资料")} onClose={onClose} anchor={anchor} returnFocus={returnFocus}>
    <div className="selection-search"><Input type="search" className={ui.input} aria-label={uiText("查找文档")}
                                             placeholder={uiText("搜索参考文档…")} value={query} maxLength={100}
                                             onChange={(event) => setQuery(event.target.value)}/></div>
    <div className="selection-results"><QueryState {...list} hasData={Boolean(list.data?.items.length)}
                                                   empty={uiText("没有可供当前员工引用的文档。")}>
      <ul className={styles.options} data-selection-list>
        {list.data?.items.map((document) => {
          const checked = selected.some((item) => item.documentId === document.documentId);
          return <li key={document.documentId}><label><Checkbox data-selection-option checked={checked}
                                                                disabled={list.loading || !checked && selected.length >= 10}
                                                                onCheckedChange={(checked) => onChange(checked
                                                                  ? [...selected.filter((item) => item.documentId !== document.documentId), document] : selected.filter((item) => item.documentId !== document.documentId))}/>
            <span><strong>{document.name}</strong><small>{document.knowledgeName}</small></span></label></li>;
        })}
      </ul>
    </QueryState></div>
    <Pagination {...list} hasMore={list.data?.hasMore}/>
    <div className="selection-footer"><SelectionAction type="button" className={ui.button}
                                                       onAction={(close) => close(onChooseEmployee)}>{uiText("选择员工")}</SelectionAction><SelectionAction
      type="button" className={ui.primary}
      onAction={(close) => close()}>{uiText("完成选择")}{selected.length ? `（${selected.length}）` : ""}</SelectionAction>
    </div>
  </SelectionSurface>;
}
