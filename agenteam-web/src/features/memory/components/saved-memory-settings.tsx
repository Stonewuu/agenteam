"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";

import {useState} from "react";
import Link from "next/link";
import {Select} from "@/components/ui/select";
import {EmployeeIdentity} from "@/features/employee/components/employee-identity";
import {IconInbox, IconPencil, IconTrash} from "@/components/ui/icons";
import {Pagination, QueryState} from "@/components/ui/query-state";
import {useApiPage} from "@/lib/http/use-api-query";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {enterprisePath} from "@/features/auth/lib/identity-navigation";
import type {IdentityUser} from "@/features/auth/types/identity";
import type {Memory, MemoryAgent} from "../types/memory";
import {MemoryDeleteDialog} from "./memory-delete-dialog";
import {MemoryEditor} from "./memory-editor";
import ui from "@/components/ui/surface.module.css";
import styles from "./memory.module.css";

export function SavedMemorySettings({user}: { user: IdentityUser }) {
  const uiText = useT();
  const [choice, setChoice] = useState(user.lastEnterpriseId ?? "");
  const enterprise = user.enterprises.find((item) => item.id === choice)?.id ?? user.enterprises[0]?.id;
  return <div className={styles.savedSettings}><h3>{uiText("已保存的偏好")}</h3>{user.enterprises.length > 1 &&
    <Select aria-label={uiText("查看偏好的企业")} value={enterprise}
            onChange={(event) => setChoice(event.target.value)}>{user.enterprises.map((item) => <option key={item.id}
                                                                                                        value={item.id}>{item.name}</option>)}</Select>}
    {enterprise ? <SavedByAgent key={enterprise} enterpriseId={enterprise}/> : <MemoryEmptyState/>}
  </div>;
}

function SavedByAgent({enterpriseId}: { enterpriseId: string }) {
  const uiText = useT();
  const [selected, setSelected] = useState("");
  const [refresh, setRefresh] = useState(0);
  const agents = useApiPage<MemoryAgent>(organizationPath(enterpriseId, "/memories/agents"), refresh, 0);
  const current = agents.data?.items.find((item) => item.agentId === selected) ?? agents.data?.items[0];
  return <><QueryState {...agents} hasData={Boolean(current)} empty={uiText("尚未保存偏好。")}
                       emptyContent={<MemoryEmptyState/>}>{current && <>
    <Select aria-label={uiText("查看偏好的员工")} value={current.agentId}
            onChange={(event) => setSelected(event.target.value)} renderOption={(option) => {
      const employee = agents.data?.items.find((item) => item.agentId === option.value);
      return employee ? <EmployeeIdentity name={employee.agentName} icon={employee.agentIcon}
                                          color={employee.agentColor}/> : option.label;
    }}>{agents.data?.items.map((item) => <option key={item.agentId}
                                                 value={item.agentId}>{item.agentName}</option>)}</Select>
    <SavedItems key={current.agentId} enterpriseId={enterpriseId} agent={current}
                onChanged={() => setRefresh((value) => value + 1)}/>
  </>}</QueryState><Pagination {...agents} hasMore={agents.data?.hasMore}/>{current &&
    <Link className={ui.button} href={enterprisePath(enterpriseId, "/memories")}>{uiText("管理偏好")}</Link>}</>;
}

function MemoryEmptyState() {
  const uiText = useT();
  return <div className={styles.emptySaved}><span><IconInbox size={28}/></span><h3>{uiText("尚未保存偏好")}</h3>
    <p>{uiText("可以在对话中确认并保存希望员工记住的工作习惯。")}</p></div>;
}

function SavedItems({enterpriseId, agent, onChanged}: {
  enterpriseId: string;
  agent: MemoryAgent;
  onChanged: () => void
}) {
  const uiText = useT();
  const [refresh, setRefresh] = useState(0);
  const [editing, setEditing] = useState<Memory | null>(null);
  const [deleting, setDeleting] = useState<Memory | null>(null);
  const list = useApiPage<Memory>(organizationPath(enterpriseId, `/agents/${encodeURIComponent(agent.agentId)}/memories`), refresh, 0);
  const changed = () => {
    setEditing(null);
    setDeleting(null);
    setRefresh((value) => value + 1);
    onChanged();
  };
  return <><QueryState {...list} hasData={Boolean(list.data?.items.length)} empty={uiText("尚未保存偏好。")}>
    <div className={styles.savedList}>{list.data?.items.map((item) => <article key={item.id}>
      <div><strong>{item.memoryKey}</strong><p>{item.content}</p></div>
      <div className={ui.actions}><Button type="button" className="icon-button"
                                          aria-label={uiText("编辑{0}", [item.memoryKey])}
                                          onClick={() => setEditing(item)}><IconPencil size={17}/></Button><Button
        type="button" className="icon-button" aria-label={uiText("删除{0}", [item.memoryKey])}
        onClick={() => setDeleting(item)}><IconTrash size={17}/></Button></div>
    </article>)}</div>
  </QueryState><Pagination {...list} hasMore={list.data?.hasMore}/>
    {editing && <MemoryEditor enterpriseId={enterpriseId} agentId={agent.agentId} initial={editing}
                              onClose={() => setEditing(null)} onReload={changed} onSaved={changed}/>}
    {deleting && <MemoryDeleteDialog enterpriseId={enterpriseId} agentId={agent.agentId} agentName={agent.agentName}
                                     memory={deleting} onClose={() => setDeleting(null)} onReload={changed}
                                     onDeleted={changed}/>}
  </>;
}
