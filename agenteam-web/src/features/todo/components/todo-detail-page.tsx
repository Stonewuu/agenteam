"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {toast} from "@/components/ui/toast";

import {Button} from "@/components/ui/button";

import {PageHeader} from "@/components/ui/page-header";

import {useState} from "react";
import Link from "next/link";
import {useRouter} from "next/navigation";
import {EnterpriseGate} from "@/features/auth/components/enterprise-gate";
import type {EnterpriseContext, IdentityUser} from "@/features/auth/types/identity";
import {enterprisePath} from "@/features/auth/lib/identity-navigation";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {PlatformShell} from "@/features/workspace/components/platform-shell";
import {useApiPage, useApiQuery} from "@/lib/http/use-api-query";
import {Pagination, QueryState} from "@/components/ui/query-state";
import {Dialog, useDialogControl} from "@/components/ui/dialog";
import {NotificationOpenedMarker} from "@/features/notification/components/notification-opened-marker";
import {scheduleTime} from "@/features/schedule/lib/schedule-display";
import {TodoEditor} from "./todo-editor";
import {TodoActions} from "./todo-actions";
import {TodoFacts} from "./todo-facts";
import type {Todo, TodoHistory} from "../types/todo";
import ui from "@/components/ui/surface.module.css";
import styles from "./todo.module.css";

export function TodoDetailPage({enterpriseId, todoId}: { enterpriseId: string; todoId: string }) {
  return <EnterpriseGate enterpriseId={enterpriseId} permission="todo.view">{({user, context}) => <TodoDetail
    key={`${enterpriseId}:${todoId}`} user={user} context={context} todoId={todoId}/>}</EnterpriseGate>;
}

export function TodoDetailDrawer({user, context, todoId, onClose, onChanged}: {
  user: IdentityUser;
  context: EnterpriseContext;
  todoId: string;
  onClose: () => void;
  onChanged: () => void
}) {
  const uiText = useT();
  const dialog = useDialogControl();
  return <Dialog title={uiText("待办详情")} onClose={onClose} dialogRef={dialog.ref} drawer><TodoDetail user={user}
                                                                                                        context={context}
                                                                                                        todoId={todoId}
                                                                                                        drawer
                                                                                                        onChanged={onChanged}
                                                                                                        onDeleted={() => dialog.close(() => {
                                                                                                          onChanged();
                                                                                                          onClose();
                                                                                                        })}/></Dialog>;
}

function TodoDetail({user, context, todoId, drawer = false, onChanged, onDeleted}: {
  user: IdentityUser;
  context: EnterpriseContext;
  todoId: string;
  drawer?: boolean;
  onChanged?: () => void;
  onDeleted?: () => void
}) {
  const uiText = useT();
  const enterpriseId = context.enterprise.id;
  const router = useRouter();
  const path = organizationPath(enterpriseId, `/todos/${encodeURIComponent(todoId)}`);
  const [refresh, setRefresh] = useState(0);
  const [editing, setEditing] = useState<Todo | null>(null);
  const detail = useApiQuery<Todo>(path, refresh);
  const history = useApiPage<TodoHistory>(`${path}/history`, refresh, 0);
  const todo = detail.data;
  const changed = (message?: string) => {
    if (message !== undefined) {
      toast.success(message);
    }
    history.first();
    setRefresh((value) => value + 1);
    onChanged?.();
  };
  return <PlatformShell user={user} context={context} area="user" title={todo?.title ?? uiText("待办详情")}>
    <div className={drawer ? styles.drawerContent : undefined}>
      <NotificationOpenedMarker enterpriseId={enterpriseId} targetType="todo" targetId={todo?.id ?? null}/>
      {!drawer && <PageHeader title={todo?.title ?? uiText("待办详情")} actions={<><Link className={ui.button}
                                                                                         href={enterprisePath(enterpriseId, "/todos")}>{uiText("返回待办")}</Link><Button
        className={ui.button} disabled={detail.loading} onClick={() => changed()}>{uiText("刷新")}</Button></>}/>}
      <QueryState {...detail} hasData={Boolean(todo)} empty={uiText("待办暂不可查看。")}>{todo && <>
        {drawer && <header className={styles.header}><h1>{todo.title}</h1></header>}
        <section className={styles.card}>
          {todo.description && <p className={styles.body}>{todo.description}</p>}<TodoFacts enterpriseId={enterpriseId}
                                                                                            todo={todo}/>
          <p
            className={ui.description}>{uiText("创建人：")}{todo.createdBy.displayName} · {scheduleTime(todo.createdAt, context.enterprise.timezone, uiText.formatLocale)}{todo.completedAt && <>{uiText(" · 完成于 ")}{scheduleTime(todo.completedAt, context.enterprise.timezone, uiText.formatLocale)}</>}</p>
          <TodoActions key={`${todo.id}:${todo.revision}`} enterpriseId={enterpriseId} todo={todo}
                       onEdit={() => setEditing(todo)} onChanged={changed}
                       onDeleted={onDeleted ?? (() => router.replace(enterprisePath(enterpriseId, "/todos")))}/>
        </section>
        <section className={styles.section}><h2>{uiText("操作历史")}</h2><QueryState {...history}
                                                                                     hasData={Boolean(history.data?.items.length)}
                                                                                     empty={uiText("尚无操作记录。")}>
          <div className={styles.list}>{history.data?.items.map((item) => <article className={styles.card}
                                                                                   key={item.id}><p
            className={styles.body}>{item.actor.displayName} · {item.summary}</p>
            {item.reason && <p className={styles.body}>{item.reason}</p>}
            <time className={styles.time}
                  dateTime={item.createdAt}>{scheduleTime(item.createdAt, context.enterprise.timezone, uiText.formatLocale)}</time>
          </article>)}</div>
        </QueryState><Pagination {...history} hasMore={history.data?.hasMore}/></section>
      </>}</QueryState>
      {editing && <TodoEditor context={context} initial={editing} onClose={() => setEditing(null)} onReload={() => {
        setEditing(null);
        changed();
      }} onSaved={() => {
        setEditing(null);
        changed(uiText("待办已保存。"));
      }}/>}
    </div>
  </PlatformShell>;
}
