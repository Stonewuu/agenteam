"use client";

import {useT} from "@/lib/i18n/locale-provider";
import {localizeCatalog} from "@/lib/i18n/translate";

import {toast} from "@/components/ui/toast";

import {Button} from "@/components/ui/button";
import {CollectionViewToggle} from "@/components/ui/collection-view";
import {MotionPanel} from "@/components/ui/motion-panel";
import {LoadingState} from "@/components/ui/loading-state";
import {useCollectionView} from "@/lib/use-collection-view";

import {PageHeader} from "@/components/ui/page-header";


import {useState} from "react";
import {EnterpriseGate} from "@/features/auth/components/enterprise-gate";
import type {EnterpriseContext, IdentityUser} from "@/features/auth/types/identity";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {PlatformShell} from "@/features/workspace/components/platform-shell";
import {useApiPage} from "@/lib/http/use-api-query";
import {Pagination, QueryState} from "@/components/ui/query-state";
import {TodoEditor} from "./todo-editor";
import {TodoDetailDrawer} from "./todo-detail-page";
import {Tabs} from "@/components/ui/tabs";
import {SearchInput} from "@/components/ui/search-input";
import {
  IconCheck,
  IconCheckbox,
  IconCircle,
  IconClock,
  IconList,
  IconPlus,
  IconRefresh,
  IconUser,
  IconUsers,
  IconX
} from "@/components/ui/icons";
import {TodoCollection} from "./todo-collection";
import {ApiOptionPicker} from "@/features/workspace/components/api-option-picker";
import {type Todo, type TodoOption, todoStatusNames} from "../types/todo";
import ui from "@/components/ui/surface.module.css";
import styles from "./todo.module.css";
import collectionStyles from "./todo-collection.module.css";

const statusIcons: Record<string, typeof IconCircle> = {
  pending: IconCircle,
  in_progress: IconClock,
  completed: IconCheck,
  cancelled: IconX
};

export function TodoPage({enterpriseId}: { enterpriseId: string }) {
  return <EnterpriseGate enterpriseId={enterpriseId} permission="todo.view">{({user, context}) => <TodoList
    key={enterpriseId} user={user} context={context}/>}</EnterpriseGate>;
}

function TodoList({user, context}: { user: IdentityUser; context: EnterpriseContext }) {
  const uiText = useT();
  const [view, setView] = useCollectionView();
  const enterpriseId = context.enterprise.id;
  const root = organizationPath(enterpriseId, "/todos");
  const [scope, setScope] = useState("mine");
  const [status, setStatus] = useState("open");
  const [query, setQuery] = useState("");
  const [team, setTeam] = useState<TodoOption | null>(null);
  const [picker, setPicker] = useState(false);
  const [editor, setEditor] = useState<Todo | "create" | null>(null);
  const [refresh, setRefresh] = useState(0);
  const [selected, setSelected] = useState<string | null>(null);
  const params = new URLSearchParams({scope, status, query});
  if (scope === "team" && team) {
    params.set("teamId", team.id);
  }
  const list = useApiPage<Todo>(`${root}?${params}`, refresh);
  const changed = (message?: string) => {
    if (message !== undefined) {
      toast.success(message);
    }
    list.first();
    setRefresh((value) => value + 1);
  };
  return <PlatformShell user={user} context={context} area="user" title={uiText("待办")}>
    <div className={styles.page}>
      <PageHeader title={uiText("把事情，一件件做好")} description={uiText("安排自己的工作，也和团队保持步调一致。")}
                  actions={<>{context.permissions.includes("todo.manage") &&
                    <Button className={ui.primary} onClick={() => setEditor("create")}><IconPlus
                      size={17}/>{uiText("创建待办")}</Button>}</>}/>
      <div className={styles.tabs}><Tabs value={scope} onChange={setScope} label={uiText("待办范围")} items={[{
        value: "mine",
        label: uiText("我的待办"),
        icon: IconUser
      }, ...(context.permissions.includes("todo.team_view") ? [{
        value: "team",
        label: uiText("团队待办"),
        icon: IconUsers
      }] : [])]}/><SearchInput aria-label={uiText("搜索待办标题")} placeholder={uiText("搜索待办…")} maxLength={100}
                               value={query} onChange={(event) => setQuery(event.target.value)}/></div>
      <div className={styles.toolbar}><Tabs variant="filter" value={status} onChange={setStatus}
                                            label={uiText("待办状态")} items={[{
        value: "open",
        label: uiText("未结束"),
        icon: IconCheckbox
      }, ...Object.entries(localizeCatalog(todoStatusNames, uiText)).map(([value, label]) => ({
        value,
        label,
        icon: statusIcons[value]
      })), {value: "", label: uiText("全部"), icon: IconList}]}/>
        {scope === "team" &&
          <Button className={ui.button} onClick={() => setPicker(true)}>{team?.name ?? uiText("全部团队")}</Button>}
        <div className={styles.viewActions}><CollectionViewToggle value={view} onChange={setView}/><Button
          className="icon-button" aria-label={uiText("刷新待办")} disabled={list.loading}
          onClick={() => changed()}><IconRefresh size={18}/></Button></div>
      </div>
      <MotionPanel value={view}><QueryState {...list} hasData={Boolean(list.data?.items.length)}
                                            empty={query || status !== "open" || team ? uiText("没有符合条件的待办。") : uiText("暂无待办。")}
                                            loadingContent={view === "grid" ?
                                              <div className={collectionStyles.loadingCards}><LoadingState
                                                layout="cards"/></div> : undefined}>
        <TodoCollection view={view} todos={list.data?.items ?? []} showOwner={scope === "team"}
                        enterpriseId={enterpriseId} onOpen={setSelected} onChanged={changed}/>
      </QueryState></MotionPanel><Pagination {...list} hasMore={list.data?.hasMore}/>
      {picker && <ApiOptionPicker title={uiText("选择团队")} path={`${root}/teams?purpose=filter`} selected={team?.id}
                                  clearLabel={uiText("全部团队")} onClose={() => setPicker(false)}
                                  onSelect={(value) => {
                                    setTeam(value);
                                    setPicker(false);
                                  }}/>}
      {editor &&
        <TodoEditor context={context} initial={editor === "create" ? null : editor} onClose={() => setEditor(null)}
                    onReload={() => {
                      setEditor(null);
                      changed();
                    }} onSaved={(value) => {
          setEditor(null);
          changed(uiText("待办已保存。"));
          if (editor === "create") {
            setSelected(value.id);
          }
        }}/>}
      {selected && <TodoDetailDrawer user={user} context={context} todoId={selected} onClose={() => setSelected(null)}
                                     onChanged={() => changed()}/>}
    </div>
  </PlatformShell>;
}
