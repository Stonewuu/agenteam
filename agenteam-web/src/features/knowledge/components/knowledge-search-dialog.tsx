"use client";

import {localizeUiMessage} from "@/lib/i18n/ui-message";

import {useT} from "@/lib/i18n/locale-provider";

import {Input} from "@/components/ui/input";
import {Button} from "@/components/ui/button";

import {useState} from "react";
import {Dialog} from "@/components/ui/dialog";
import {apiRequest} from "@/lib/http/api-client";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import type {Citation} from "@/features/agent/types/execution";
import {CitationCard} from "./citation-card";
import ui from "@/components/ui/surface.module.css";

export function KnowledgeSearchDialog({enterpriseId, resourceId, onClose}: {
  enterpriseId: string;
  resourceId: string;
  onClose: () => void
}) {
  const uiText = useT();
  return <Dialog title={uiText("检索文档")} onClose={onClose}><KnowledgeSearchForm enterpriseId={enterpriseId}
                                                                                   resourceId={resourceId}/></Dialog>;
}

export function KnowledgeSearchForm({enterpriseId, resourceId}: { enterpriseId: string; resourceId: string }) {
  const uiText = useT();
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<Citation[] | null>(null);
  const action = useFormAction();
  return <form className={ui.form} onSubmit={(event) => {
    event.preventDefault();
    void action.execute(async () => {
      setResults(null);
      setResults(await apiRequest<Citation[]>(organizationPath(enterpriseId, `/knowledge/${encodeURIComponent(resourceId)}/search`), {
        method: "POST",
        body: {query: query.trim(), limit: 8}
      }));
    }, "");
  }}><label className={ui.field}><span>{uiText("检索内容")}</span><Input className={ui.input} required minLength={2}
                                                                         maxLength={500} value={query}
                                                                         onChange={(event) => {
                                                                           setQuery(event.target.value);
                                                                           setResults(null);
                                                                         }}/></label>
    <div className={ui.actions}><Button className={ui.primary}
                                        disabled={action.busy || query.trim().length < 2}>{action.busy ? uiText("正在检索…") : uiText("检索")}</Button>
    </div>
    {action.error && <p className={ui.error} role="alert">{localizeUiMessage(action.error ?? "", uiText)}</p>}
    {results?.length === 0 && <p className={ui.empty}>{uiText("没有找到相关内容。")}</p>}
    {results?.map((citation) => <CitationCard key={citation.chunkId} enterpriseId={enterpriseId} citation={citation}/>)}
  </form>;
}
