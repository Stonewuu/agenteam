"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Input} from "@/components/ui/input";
import {Checkbox} from "@/components/ui/checkbox";
import {Button} from "@/components/ui/button";

import type {RefObject} from "react";
import {useSelectionQuery} from "@/components/ui/use-selection-query";
import {SelectionAction, type SelectionAnchor, SelectionSurface} from "@/components/ui/selection-surface";
import {Pagination, QueryState} from "@/components/ui/query-state";
import {ResourceIcon} from "@/components/ui/resource-icon";
import {useApiPage} from "@/lib/http/use-api-query";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import type {SkillOption} from "../types/skill";
import ui from "@/components/ui/surface.module.css";
import styles from "@/features/plugin/components/plugin.module.css";

export function SkillPickerDialog({
                                    enterprise,
                                    agent,
                                    conversation,
                                    selected,
                                    onChange,
                                    onClose,
                                    anchor,
                                    returnFocus,
                                    initialQuery = ""
                                  }: {
  enterprise: string;
  agent: string;
  conversation: string | null;
  selected: SkillOption[];
  onChange: (value: SkillOption[]) => void;
  onClose: () => void;
  anchor?: SelectionAnchor | null;
  returnFocus?: RefObject<HTMLElement | null>;
  initialQuery?: string;
}) {
  const uiText = useT();
  const [query, setQuery] = useSelectionQuery(initialQuery);
  const parameters = new URLSearchParams({kind: "skill", query});
  if (conversation) {
    parameters.set("conversationId", conversation);
  }
  const list = useApiPage<SkillOption>(organizationPath(enterprise, `/agents/${encodeURIComponent(agent)}/input-options?${parameters}`));
  return <SelectionSurface title={uiText("选择技能")} onClose={onClose} anchor={anchor} returnFocus={returnFocus}>
    <div className="selection-search"><Input className={ui.input} type="search" placeholder={uiText("搜索技能")}
                                             aria-label={uiText("搜索技能")} value={query} maxLength={100}
                                             onChange={(event) => setQuery(event.target.value)}/></div>
    <p className="selection-hint">{uiText("一次最多选择三个技能。")}</p>
    <div className="selection-results"><QueryState {...list} hasData={Boolean(list.data?.items.length)}
                                                   empty={uiText("当前员工没有匹配的可用技能。")}>
      <div className={styles.tools} data-selection-list>
        {list.data?.items.map((skill) => {
          const checked = selected.some((value) => value.versionId === skill.versionId);
          return <label key={skill.versionId} className={styles.tool}>
            <Checkbox data-selection-option checked={checked}
                      disabled={list.loading || !checked && selected.length >= 3}
                      onCheckedChange={(checked) => onChange(checked ? [...selected, skill] : selected.filter((value) => value.versionId !== skill.versionId))}/>
            <ResourceIcon name={skill.icon} size={20}/><span
            className={styles.toolContent}><strong>{skill.name}</strong><p
            className={ui.description}>{skill.description}</p></span>
          </label>;
        })}
      </div>
    </QueryState></div>
    <Pagination {...list} hasMore={list.data?.hasMore}/>
    <div className="selection-footer"><Button type="button" className={ui.button} onClick={() => onChange([])}
                                              disabled={!selected.length}>{uiText("清空选择")}</Button><SelectionAction
      type="button" className={ui.primary}
      onAction={(close) => close()}>{uiText("完成选择")}{selected.length ? `（${selected.length}）` : ""}</SelectionAction>
    </div>
  </SelectionSurface>;
}
