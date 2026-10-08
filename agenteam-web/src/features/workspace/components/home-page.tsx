"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";

import {PageHeader} from "@/components/ui/page-header";

import Link from "next/link";
import {useState} from "react";
import {EnterpriseGate} from "@/features/auth/components/enterprise-gate";
import {EnterpriseDateTime} from "@/features/auth/components/enterprise-date-time";
import type {EnterpriseContext, IdentityUser} from "@/features/auth/types/identity";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import type {Todo} from "@/features/todo/types/todo";
import {useApiQuery} from "@/lib/http/use-api-query";
import {QueryState} from "@/components/ui/query-state";
import {ResourceAvatar} from "@/components/ui/resource-avatar";
import {
  IconArrowRight,
  IconArrowUpRight,
  IconCalendar,
  IconCheckbox,
  IconCircle,
  IconInbox,
  IconMessages,
  IconPlus,
  IconUsers
} from "@/components/ui/icons";
import {PlatformShell} from "./platform-shell";
import {MutationFeedback} from "./mutation-feedback";
import {HomeTask} from "./home-task";
import {WorkspacePageLoading} from "./workspace-loading";
import {ActivityTrend} from "./activity-trend";
import type {HomeSummary} from "../types/workspace";
import styles from "./home-page.module.css";

export function HomePage({enterpriseId}: { enterpriseId: string }) {
  return <EnterpriseGate enterpriseId={enterpriseId} permission="workspace.view">{({user, context}) => <HomeContent
    key={`${enterpriseId}:${user.id}`} user={user} context={context}/>}</EnterpriseGate>;
}

function HomeContent({user, context}: { user: IdentityUser; context: EnterpriseContext }) {
  const uiText = useT();
  const enterprise = context.enterprise.id;
  const root = `/enterprises/${encodeURIComponent(enterprise)}`;
  const result = useApiQuery<HomeSummary>(organizationPath(enterprise, "/home"), context.permissionVersion);
  const [activityRevision, setActivityRevision] = useState(0);
  const changed = () => {
    result.retry();
    setActivityRevision((value) => value + 1);
  };
  const allowed = (permission: string) => context.permissions.includes(permission);
  const date = new Intl.DateTimeFormat(uiText.formatLocale, {
    month: "long",
    day: "numeric",
    weekday: "long",
    timeZone: context.enterprise.timezone
  }).format(new Date());
  return <PlatformShell user={user} context={context} area="user" title={uiText("工作台")}>
    <div className={styles.home}>
      <PageHeader title={uiText("今天，从哪件事开始？")} size="hero"
                  eyebrow={uiText("你好，{0}", [context.member.displayName])}
                  description={uiText("让数字员工成为你的好帮手，把精力留给更重要的事。")}
                  actions={<span className={styles.date}><IconCalendar size={15}/>{date}</span>}/>
      <QueryState {...result} hasData={Boolean(result.data)} empty={uiText("暂时没有可展示的内容。")}
                  contentLayout="flow"
                  loadingContent={allowed("agent.run") && allowed("conversation.view") ?
                    <WorkspacePageLoading layout="home" embedded showHeading={false}/> : undefined}>
        {result.data && <>
          {allowed("agent.run") && allowed("conversation.view") &&
            <HomeTask enterprise={enterprise} userId={user.id} employees={result.data.employees}
                      permissions={context.permissions} onChanged={changed}/>}
          <div className={styles.sections}>
            {allowed("conversation.view") && <section className={styles.section}>
              <header><h2><IconMessages size={18} variant="Bulk"/>{uiText("最近的任务")}</h2><Link
                href={`${root}/conversations`}>{uiText("查看全部")}<IconArrowRight size={14}/></Link></header>
              <div className={styles.recentTasks}>{result.data.recentConversations.slice(0, 3).map((conversation) =>
                <Link className={styles.recentTask}
                      href={`${root}/conversations/${encodeURIComponent(conversation.id)}`}
                      key={conversation.id}><ResourceAvatar icon={conversation.agentIcon ?? ""}
                                                            color={conversation.agentColor ?? undefined}
                                                            size="small"/><span><strong>{conversation.title}</strong><small>{conversation.agentName} · <EnterpriseDateTime
                  value={conversation.updatedAt} dateOnly/></small></span><IconArrowUpRight size={18}/></Link>)}</div>
              {!result.data.recentConversations.length &&
                <div className={styles.compactEmpty}><span className={styles.emptyIcon}><IconMessages size={25}
                                                                                                      variant="Bulk"/></span>
                  <div><h3>{uiText("从第一个任务开始")}</h3>
                    <p>{uiText("把想完成的工作交给数字员工。")}</p>{allowed("agent.run") && result.data.employees.length > 0 &&
                      <Link className="text-button" href={`${root}/new-task`}><IconPlus size={14}/>{uiText("新建任务")}
                      </Link>}{!result.data.employees.length && allowed("agent.market_view") &&
                      <Link className="text-button" href={`${root}/employees`}>{uiText("查看员工广场")}<IconArrowRight
                        size={14}/></Link>}</div>
                </div>}
            </section>}
            {allowed("todo.view") && allowed("todo.manage") && <section className={styles.section}>
              <header><h2><IconCheckbox size={18} variant="Bulk"/>{uiText("待你处理")}</h2><Link
                href={`${root}/todos`}>{uiText("全部待办")}<IconArrowRight size={14}/></Link></header>
              <div className={styles.homeTodos}>
                {result.data.todos.length ? result.data.todos.slice(0, 3).map((todo) => <HomeTodo key={todo.id}
                                                                                                  enterprise={enterprise}
                                                                                                  todo={todo}
                                                                                                  onChanged={changed}
                                                                                                  canManage={allowed("todo.manage")}/>) :
                  <div className={styles.compactEmpty}><span className={styles.emptyIcon}><IconInbox size={25}
                                                                                                     variant="Bulk"/></span>
                    <div><h3>{uiText("暂时没有待处理事项")}</h3><p>{uiText("留一点从容，开始下一件事。")}</p><Link
                      className="text-button" href={`${root}/todos`}>{uiText("查看我的待办")}<IconArrowRight size={14}/></Link>
                    </div>
                  </div>}
              </div>
            </section>}
          </div>
          {allowed("agent.run") && <section className={styles.employees}>
            <header><h2><IconUsers size={18} variant="Bulk"/>{uiText("我的数字员工")}</h2><Link
              href={`${root}/employees?tab=mine`}>{uiText("查看全部")}<IconArrowRight size={14}/></Link></header>
            {result.data.employees.length > 0 ?
              <div className={styles.employeeStrip}>{result.data.employees.slice(0, 3).map((employee) => <Link
                className={styles.employee} key={employee.agentId}
                href={employee.canRun && allowed("conversation.view") ? `${root}/new-task?agent=${encodeURIComponent(employee.agentId)}` : `${root}/employees?tab=mine&employee=${encodeURIComponent(employee.agentId)}`}><ResourceAvatar
                icon={employee.icon}
                color={employee.color}/><span><strong>{employee.name}</strong><small>{employee.businessRole}</small></span><IconArrowUpRight
                size={17}/></Link>)}</div>
              : <div className={styles.employeeEmpty}>
                <span>{uiText("还没有可用的数字员工")}</span>{allowed("agent.market_view") &&
                <Link className="text-button" href={`${root}/employees`}>{uiText("前往员工广场")}<IconArrowRight
                  size={14}/></Link>}</div>}
          </section>}
        </>}
      </QueryState>
      <ActivityTrend enterprise={enterprise} refresh={`${context.permissionVersion}:${activityRevision}`}/>
    </div>
  </PlatformShell>;
}

function HomeTodo({enterprise, todo, onChanged, canManage}: {
  enterprise: string;
  todo: Todo;
  onChanged: () => void;
  canManage: boolean
}) {
  const uiText = useT();
  const action = useFormAction();
  return <div>
    <div className={styles.todo}>
      {canManage && todo.allowedActions.includes("complete") &&
        <Button className="icon-button" type="button" disabled={action.busy}
                aria-label={uiText("完成待办：{0}", [todo.title])} onClick={() => void action.execute(async () => {
          await action.mutation.run(organizationPath(enterprise, `/todos/${encodeURIComponent(todo.id)}/status`), {
            method: "PATCH",
            revision: todo.revision,
            body: {status: "completed", reason: ""}
          });
          onChanged();
        }, "")}><IconCircle size={21}/></Button>}
      <Link className={styles.itemLink}
            href={`/enterprises/${encodeURIComponent(enterprise)}/todos/${encodeURIComponent(todo.id)}`}><strong>{todo.title}</strong><small>{todo.dueDate ? uiText("{0} 截止", [todo.dueDate]) : uiText("未设置截止日期")}</small></Link>
      {todo.priority === "high" && <span className="badge amber">{uiText("高优先级")}</span>}
    </div>
    <MutationFeedback action={action} onReload={onChanged}/></div>;
}
