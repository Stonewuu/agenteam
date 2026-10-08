"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";

import {PageHeader} from "@/components/ui/page-header";

import {useEnterpriseIdentity} from "@/features/auth/components/enterprise-gate";
import {useState} from "react";
import {usePathname, useSearchParams} from "next/navigation";
import {Tabs} from "@/components/ui/tabs";
import {MotionPanel} from "@/components/ui/motion-panel";
import {IconPlus, IconRefresh} from "@/components/ui/icons";
import {SearchInput} from "@/components/ui/search-input";
import {HireApplications} from "@/features/employee/components/hire-applications";
import {MemberCreateDialog} from "./member-create-dialog";
import {MemberPanel} from "./member-panel";
import {InvitationEditor, InvitationPanel} from "./invitation-panel";
import ui from "@/components/ui/surface.module.css";
import styles from "./organization.module.css";

export function MembersOverview({enterpriseId, userId, permissions, query, onQuery, initialTab = "members"}: {
  enterpriseId: string;
  userId: string;
  permissions: string[];
  query: string;
  onQuery: (value: string) => void;
  initialTab?: string
}) {
  const uiText = useT();
  const [creating, setCreating] = useState(false);
  const identity = useEnterpriseIdentity();
  const canCreate = Boolean(identity?.context.capabilities.includes("enterprise.invite"));
  const params = useSearchParams();
  const pathname = usePathname();
  const [inviting, setInviting] = useState(false);
  const [refresh, setRefresh] = useState(0);
  const tabs = [{value: "members", label: uiText("企业成员")}, {
    value: "invitations",
    label: uiText("邀请记录")
  }, ...(permissions.includes("agent.hire_approve") ? [{value: "applications", label: uiText("雇佣审批")}] : [])];
  const requested = params.get("tab") ?? initialTab;
  const tab = tabs.some((item) => item.value === requested) ? requested : "members";
  const changed = () => setRefresh((value) => value + 1);
  const common = {enterpriseId, permissions, query, onQuery};
  return <><PageHeader title={uiText("成员与邀请")} description={uiText("让合适的人加入团队，分配清楚的工作角色。")}
                       actions={<>{canCreate && <><Button className={ui.button}
                                                          onClick={() => setCreating(true)}>{uiText("创建用户")}</Button><Button
                         className={ui.primary} onClick={() => setInviting(true)}><IconPlus
                         size={17}/>{uiText("邀请成员")}</Button></>}</>}/>
    <div className={styles.membersFilters}><Tabs value={tab} onChange={(value) => {
      const next = new URLSearchParams(params);
      next.set("tab", value);
      next.delete("query");
      window.history.pushState(null, "", `${pathname}?${next}`);
    }} items={tabs} label={uiText("成员管理分类")}/>
      <div className={ui.actions}>{tab !== "applications" &&
        <SearchInput value={query} onChange={(event) => onQuery(event.target.value)}
                     placeholder={tab === "members" ? uiText("搜索姓名或邮箱…") : uiText("搜索受邀邮箱…")}
                     aria-label={tab === "members" ? uiText("查找成员") : uiText("查找邀请")}/>}<Button
        className="icon-button" aria-label={uiText("刷新成员管理")} onClick={changed}><IconRefresh size={18}/></Button>
      </div>
    </div>
    <MotionPanel value={tab}>
      {tab === "members" && <MemberPanel {...common} userId={userId} hideSearch externalRefresh={refresh}/>}
      {tab === "invitations" && <InvitationPanel {...common} externalRefresh={refresh} hideInvite hideToolbar/>}
      {tab === "applications" &&
        <HireApplications enterpriseId={enterpriseId} refresh={refresh} onChanged={changed} table/>}
    </MotionPanel>
    {creating &&
      <MemberCreateDialog enterpriseId={enterpriseId} canReadTeams={permissions.includes("enterprise.teams.view")}
                          onClose={() => setCreating(false)} onDone={changed}/>}
    {inviting && <InvitationEditor enterpriseId={enterpriseId} initial={null}
                                   canReadTeams={permissions.includes("enterprise.teams.view")}
                                   onClose={() => setInviting(false)} onDone={() => {
      changed();
      const next = new URLSearchParams(params);
      next.set("tab", "invitations");
      window.history.pushState(null, "", `${pathname}?${next}`);
    }}/>}
  </>;
}
