"use client";

import {localizeUiMessage} from "@/lib/i18n/ui-message";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";
import {Input} from "@/components/ui/input";

import {useState} from "react";
import {Dialog, DialogAction} from "@/components/ui/dialog";
import {ResourceIcon} from "@/components/ui/resource-icon";
import {Pagination, QueryState} from "@/components/ui/query-state";
import {type ApiPage, useApiPage, useApiQuery} from "@/lib/http/use-api-query";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import type {SkillOption, WorkspaceSkill} from "../types/skill";
import ui from "@/components/ui/surface.module.css";
import styles from "./workspace-skills.module.css";

export function WorkspaceSkills({enterprise, selectedAgent, onSelect}: {
  enterprise: string;
  selectedAgent: string | null;
  onSelect: (skill: SkillOption, agent: string) => void
}) {
  const uiText = useT();
  const list = useApiQuery<ApiPage<WorkspaceSkill>>(organizationPath(enterprise, "/workspace/skills?limit=6"));
  const [more, setMore] = useState(false);
  const [choosing, setChoosing] = useState<WorkspaceSkill | null>(null);

  function choose(value: WorkspaceSkill) {
    const current = value.employees.find((employee) => employee.agentId === selectedAgent);
    if (current || value.employees.length === 1) {
      setMore(false);
      onSelect(value.skill, (current ?? value.employees[0]).agentId);
    } else {
      setMore(false);
      setChoosing(value);
    }
  }

  // 首次加载和空结果都不显示标题，避免切换页面时单独闪现“技能”。
  if (!list.error && !list.data?.items.length && !list.data?.hasMore) {
    return null;
  }
  return <section className={styles.section} aria-label={uiText("工作台技能")}>
    <div className={styles.heading}><h3>{uiText("技能")}</h3>{list.data?.hasMore &&
      <Button className={ui.button} type="button" onClick={() => setMore(true)}>{uiText("更多技能")}</Button>}</div>
    {list.error &&
      <p className={ui.error} role="alert">{localizeUiMessage(list.error ?? "", uiText)}<Button className={ui.button}
                                                                                                type="button"
                                                                                                onClick={list.retry}>{uiText("重试")}</Button>
      </p>}
    <SkillCards items={list.data?.items ?? []} onSelect={choose}/>
    {more && <AllSkills enterprise={enterprise} onClose={() => setMore(false)} onSelect={choose}/>}
    {choosing && <Dialog title={uiText("选择执行“{0}”的员工", [choosing.skill.name])} onClose={() => setChoosing(null)}>
      <div className={styles.employees}>{choosing.employees.map((employee) =>
        <DialogAction className={ui.button} type="button" key={employee.agentId} onAction={(close) => close(() => {
          onSelect(choosing.skill, employee.agentId);
          setChoosing(null);
        })}><strong>{employee.name}</strong><span
          className={ui.description}>{employee.description}</span></DialogAction>)}</div>
    </Dialog>}
  </section>;
}

function SkillCards({items, onSelect}: { items: WorkspaceSkill[]; onSelect: (value: WorkspaceSkill) => void }) {
  return <div className={styles.grid}>{items.map((value) => <Button className={styles.card} type="button"
                                                                    key={value.skill.versionId}
                                                                    onClick={() => onSelect(value)}>
    <ResourceIcon name={value.skill.icon}
                  size={21}/><span><strong>{value.skill.name}</strong><span>{value.skill.description}</span></span>
  </Button>)}</div>;
}

function AllSkills({enterprise, onClose, onSelect}: {
  enterprise: string;
  onClose: () => void;
  onSelect: (value: WorkspaceSkill) => void
}) {
  const uiText = useT();
  const [query, setQuery] = useState("");
  const list = useApiPage<WorkspaceSkill>(organizationPath(enterprise, `/workspace/skills?query=${encodeURIComponent(query)}`));
  return <Dialog title={uiText("工作台技能")} onClose={onClose} wide>
    <div className={ui.form}><Input type="search" className={ui.input} value={query} maxLength={100}
                                    aria-label={uiText("搜索工作台技能")} placeholder={uiText("搜索技能")}
                                    onChange={(event) => setQuery(event.target.value)}/>
      <QueryState {...list} hasData={Boolean(list.data?.items.length)}
                  empty={uiText("当前页没有匹配的可用技能。")}><SkillCards items={list.data?.items ?? []}
                                                                          onSelect={onSelect}/></QueryState><Pagination {...list}
                                                                                                                        hasMore={list.data?.hasMore}/>
    </div>
  </Dialog>;
}
