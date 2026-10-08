"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";
import {Input} from "@/components/ui/input";
import {Textarea} from "@/components/ui/textarea";

import {PageHeader} from "@/components/ui/page-header";

import {useState} from "react";
import Link from "next/link";
import {type ApiPage, useApiQuery} from "@/lib/http/use-api-query";
import {QueryState} from "@/components/ui/query-state";
import {Dialog, DialogAction, DialogActions, DialogCancel, DialogForm} from "@/components/ui/dialog";
import {IconBuilding, IconChartBar, IconChevronRight, IconKey, IconPencil, IconUsers} from "@/components/ui/icons";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {FormFeedback} from "@/features/auth/components/settings-forms";
import {TimezoneSelect} from "@/features/auth/components/timezone-select";
import {useConfirmClose} from "@/features/workspace/components/use-confirm-close";
import {enterprisePath} from "@/features/auth/lib/identity-navigation";
import type {EnterpriseContext} from "@/features/auth/types/identity";
import {organizationPath} from "../api/organization-api";
import type {Enterprise, Role} from "../types/organization";
import {Modal} from "./organization-shared";
import styles from "./organization.module.css";

export function EnterpriseProfilePanel({enterpriseId, canManage, context}: {
  enterpriseId: string;
  canManage: boolean;
  context?: EnterpriseContext
}) {
  const uiText = useT();
  const [refresh, setRefresh] = useState(0);
  const [editing, setEditing] = useState(false);
  const query = useApiQuery<Enterprise>(organizationPath(enterpriseId), refresh);
  const roles = useApiQuery<ApiPage<Role>>(context?.permissions.includes("enterprise.roles.view") ? organizationPath(enterpriseId, "/roles?limit=100") : null);
  const myRoles = roles.data?.items.filter((role) => context?.member.roleIds.includes(role.id));
  const canInvite = context?.capabilities.includes("enterprise.invite");
  const canManageAccess = context?.permissions.includes("resource.grants.manage");
  const links = [
    {
      path: "members",
      name: canInvite ? uiText("邀请团队成员") : uiText("查看企业成员"),
      description: canInvite ? uiText("让同事加入企业，一起开始工作") : uiText("了解企业成员与所属团队"),
      permission: "enterprise.members.view",
      icon: IconUsers
    },
    {
      path: "access",
      name: canManageAccess ? uiText("管理使用范围") : uiText("查看使用范围"),
      description: canManageAccess ? uiText("为团队和成员分配合适的能力") : uiText("了解团队和成员可以使用的能力"),
      permission: "admin.view",
      icon: IconKey
    },
    {
      path: "usage",
      name: uiText("查看执行用量"),
      description: uiText("了解本月使用情况与次数上限"),
      permission: "usage.view",
      icon: IconChartBar
    },
  ];
  return <><PageHeader title={uiText("让团队协作，更有章法")} description={uiText("了解企业信息、成员与资源使用情况。")}
                       actions={<>{canManage && <Button className={styles.secondary} disabled={!query.data}
                                                        onClick={() => setEditing(true)}><IconPencil
                         size={16}/>{uiText("编辑企业资料")}</Button>}</>}/>
    <QueryState {...query} hasData={Boolean(query.data)} empty={uiText("暂时无法读取企业资料。")}>{query.data &&
      <div className={styles.overviewGrid}>
        <section className={styles.overviewCard}>
          <div className={styles.overviewIdentity}><span className="avatar purple"><IconBuilding size={25}/></span>
            <div><h2>{query.data.name}</h2>{query.data.description && <p>{query.data.description}</p>}</div>
          </div>
          <dl className={styles.detailGrid}>
            <dt>{uiText("联系邮箱")}</dt>
            <dd>{query.data.contactEmail ?? uiText("未设置")}</dd>
            <dt>{uiText("企业时区")}</dt>
            <dd>{query.data.timezone}</dd>
            {Boolean(myRoles?.length) && <>
              <dt>{uiText("我的角色")}</dt>
              <dd>{myRoles?.map((role) => role.name).join("、")}</dd>
            </>}</dl>
        </section>
        <section className={styles.overviewCard}><h2>{uiText("常用入口")}</h2>
          <div
            className={styles.quickLinks}>{links.filter((item) => context?.permissions.includes(item.permission)).map(({
                                                                                                                         icon: Icon,
                                                                                                                         ...item
                                                                                                                       }) =>
            <Link href={enterprisePath(enterpriseId, `/management/${item.path}`)} key={item.path}><Icon
              size={21}/><span><strong>{item.name}</strong><small>{item.description}</small></span><IconChevronRight
              size={17}/></Link>)}</div>
        </section>
      </div>}</QueryState>
    {editing && query.data &&
      <EnterpriseEditDialog enterprise={query.data} onClose={() => setEditing(false)} onSaved={() => {
        setEditing(false);
        setRefresh((value) => value + 1);
        window.dispatchEvent(new Event("agenteam:identity-changed"));
      }}/>}
  </>;
}

function EnterpriseEditDialog({enterprise, onClose, onSaved}: {
  enterprise: Enterprise;
  onClose: () => void;
  onSaved: () => void
}) {
  const uiText = useT();
  const [name, setName] = useState(enterprise.name);
  const [description, setDescription] = useState(enterprise.description);
  const [email, setEmail] = useState(enterprise.contactEmail ?? "");
  const [timezone, setTimezone] = useState(enterprise.timezone);
  const [confirmTimezone, setConfirmTimezone] = useState(false);
  const action = useFormAction();
  const dirty = name !== enterprise.name || description !== enterprise.description || email !== (enterprise.contactEmail ?? "") || timezone !== enterprise.timezone;
  const closing = useConfirmClose(dirty, action.busy, onClose);
  const save = () => void action.execute(async () => {
    await action.mutation.run<Enterprise>(organizationPath(enterprise.id), {
      method: "PATCH",
      revision: enterprise.revision,
      body: {name, description, contactEmail: email.trim() || null, timezone}
    });
    closing.finish(onSaved);
  });
  return <Modal title={uiText("编辑企业资料")} onClose={onClose} dialogRef={closing.dialogRef}
                onRequestClose={closing.canClose} busy={action.busy}><DialogForm className={styles.form}
                                                                                 onSubmit={(event) => {
                                                                                   event.preventDefault();
                                                                                   if (timezone !== enterprise.timezone) {
                                                                                     setConfirmTimezone(true);
                                                                                   } else {
                                                                                     save();
                                                                                   }
                                                                                 }}>
    <label className={styles.field}><span>{uiText("企业名称")}</span><Input className={styles.input} value={name}
                                                                            onChange={(event) => setName(event.target.value)}
                                                                            disabled={action.busy} required/></label>
    <label className={styles.field}><span>{uiText("简介")}</span><Textarea className={styles.textarea}
                                                                           value={description}
                                                                           onChange={(event) => setDescription(event.target.value)}
                                                                           disabled={action.busy}/></label>
    <label className={styles.field}><span>{uiText("联系邮箱")}</span><Input className={styles.input} type="email"
                                                                            value={email}
                                                                            onChange={(event) => setEmail(event.target.value)}
                                                                            disabled={action.busy}
                                                                            maxLength={254}/></label>
    <label className={styles.field}><span>{uiText("企业时区")}</span><TimezoneSelect value={timezone}
                                                                                     onChange={setTimezone}
                                                                                     disabled={action.busy}/></label>
    <FormFeedback action={action} onReload={async () => {
      closing.finish(onSaved);
    }}/><DialogActions className={styles.formActions}><DialogCancel className={styles.secondary} type="button"
                                                                    disabled={action.busy}>{uiText("取消")}</DialogCancel><Button
    className={styles.primary}
    disabled={action.busy}>{action.busy ? uiText("正在保存…") : uiText("保存企业资料")}</Button></DialogActions>
  </DialogForm>{closing.confirmation}{confirmTimezone &&
    <Dialog title={uiText("更改企业时区？")} onClose={() => setConfirmTimezone(false)}><p
      className={styles.description}>{uiText("企业时间将从 ")}{enterprise.timezone}{uiText(" 改为 ")}{timezone}{uiText("。已有计划继续使用各自设置的时区。")}</p>
      <DialogActions className={styles.formActions}><DialogCancel
        className={styles.secondary}>{uiText("返回修改")}</DialogCancel><DialogAction className={styles.primary}
                                                                                      onAction={(close) => close(() => {
                                                                                        setConfirmTimezone(false);
                                                                                        save();
                                                                                      })}>{uiText("确认保存")}</DialogAction></DialogActions></Dialog>}
  </Modal>;
}
