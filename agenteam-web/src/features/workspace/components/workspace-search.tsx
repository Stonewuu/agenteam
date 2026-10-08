"use client";

import {useEnterpriseIdentity} from "@/features/auth/components/enterprise-gate";
import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";
import {Input} from "@/components/ui/input";

import {useEffect, useState} from "react";
import Link from "next/link";
import {useRouter} from "next/navigation";
import {menuPath} from "../lib/navigation";
import {searchContentGroups, searchMenuGroups} from "../lib/search-presentation";
import {IconCommand, IconSearch} from "@/components/ui/icons";
import {ResourceAvatar} from "@/components/ui/resource-avatar";
import {Dialog, useDialogControl} from "@/components/ui/dialog";
import {QueryState} from "@/components/ui/query-state";
import {useApiQuery} from "@/lib/http/use-api-query";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import type {SearchItem, SearchResult} from "../types/workspace";
import ui from "@/components/ui/surface.module.css";
import styles from "./workspace-search.module.css";

const kinds = {
  agent: "agents",
  skill: "skills",
  plugin: "plugins",
  workflow: "workflows",
  knowledge: "knowledge",
  data: "data"
};

function destination(root: string, item: SearchItem) {
  switch (item.targetType) {
    case "menu":
      return menuPath(item.targetId) ? `${root}/${menuPath(item.targetId)}` : null;
    case "conversation":
      return `${root}/conversations/${encodeURIComponent(item.targetId)}`;
    case "employee":
      return `${root}/employees?employee=${encodeURIComponent(item.targetId)}`;
    case "resource":
      return item.resourceKind && kinds[item.resourceKind] ? `${root}/capabilities/${kinds[item.resourceKind]}/${encodeURIComponent(item.targetId)}` : null;
  }
}

export function WorkspaceSearch({enterpriseId, permissions, className}: {
  enterpriseId: string;
  permissions: readonly string[];
  className?: string
}) {
  const uiText = useT();
  const [open, setOpen] = useState(false);
  const control = useDialogControl();
  const {close} = control;
  useEffect(() => {
    const key = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "k") {
        event.preventDefault();
        if (open) {
          close();
        } else {
          setOpen(true);
        }
      }
    };
    window.addEventListener("keydown", key);
    return () => window.removeEventListener("keydown", key);
  }, [open, close]);
  return <><Button className={className ?? ui.button} type="button" aria-label={uiText("搜索当前企业")}
                   onClick={() => setOpen(true)}><IconSearch
    size={17}/><span>{uiText("搜索工作空间")}</span><kbd><IconCommand size={11}/> K</kbd></Button>
    {open && <SearchDialog key={enterpriseId} enterpriseId={enterpriseId} permissions={permissions} control={control}
                           onClose={() => setOpen(false)}/>}</>;
}

function SearchDialog({enterpriseId, permissions, onClose, control}: {
  enterpriseId: string;
  permissions: readonly string[];
  onClose: () => void;
  control: ReturnType<typeof useDialogControl>
}) {
  const uiText = useT();
  const router = useRouter();
  const [query, setQuery] = useState("");
  const result = useApiQuery<SearchResult>(query.trim() ? organizationPath(enterpriseId, `/search?query=${encodeURIComponent(query)}`) : null, 0, 300);
  const identity = useEnterpriseIdentity();
  const groups = [...searchMenuGroups(query, permissions, uiText, identity?.context.capabilities ?? []), ...searchContentGroups(result.data, uiText)];
  const root = `/enterprises/${encodeURIComponent(enterpriseId)}`;
  return <Dialog title={uiText("搜索")} onClose={onClose} dialogRef={control.ref}>
    <div className={styles.search}><label
      className={ui.field}><span>{uiText("搜索菜单、对话、员工或能力中心的内容")}</span><Input className={ui.input}
                                                                                             type="search" value={query}
                                                                                             maxLength={100}
                                                                                             placeholder={uiText("输入名称或标题")}
                                                                                             onChange={(event) => setQuery(event.target.value)}/></label>
      <QueryState {...result} hasData={Boolean(groups.length)}
                  empty={query.trim() ? uiText("没有匹配结果。") : uiText("没有可显示的菜单。")}>
        <div className={styles.results}>{groups.map((group) => <section key={group.key} aria-label={group.label}>
          <h2>{group.label}</h2>
          <ul>{group.items.map((item) => {
            const href = destination(root, item);
            return href && <li key={item.id}><Link href={href} onNavigate={(event) => {
              event.preventDefault();
              control.close(() => {
                onClose();
                router.push(href);
              });
            }}>
              {item.icon && <ResourceAvatar icon={item.icon} color={item.color ?? undefined} size="small"/>}
              <div className={styles.resultCopy}><strong>{item.name}</strong>{item.description &&
                <span>{item.description}</span>}</div>
            </Link></li>;
          })}</ul>
        </section>)}</div>
      </QueryState>
    </div>
  </Dialog>;
}
