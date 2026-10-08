"use client";

import {useState} from "react";
import {AnimatedHeight} from "@/components/ui/animated-height";
import {Select} from "@/components/ui/select";
import {useT} from "@/lib/i18n/locale-provider";
import type {Grant} from "../types/resource";
import {GrantFields} from "./resource-access-dialog";
import ui from "@/components/ui/surface.module.css";

export function AgentUseScopeFields({enterpriseId, resourceId, grants, onChange, visible = true}: {
  enterpriseId: string; resourceId: string; grants: Grant[]; onChange: (grants: Grant[]) => void; visible?: boolean;
}) {
  const uiText = useT();
  const [scope, setScope] = useState(() => grants.some((grant) => grant.subjectType === "enterprise") ? "enterprise" : grants.length ? "custom" : "owner");
  return <AnimatedHeight preserveControlShadows>{visible && <div className={ui.form}>
    <label className={ui.field}><span>{uiText("可雇佣范围")}</span>
      <Select className={ui.select} value={scope} onChange={(event) => {
        const next = event.target.value;
        setScope(next);
        onChange(next === "enterprise" ? [{subjectType: "enterprise", subjectId: enterpriseId, capability: "use"}]
          : next === "owner" ? [] : grants.filter((grant) => grant.subjectType !== "enterprise"));
      }}>
        <option value="enterprise">{uiText("全体企业成员")}</option>
        <option value="custom">{uiText("指定成员或团队")}</option>
        <option value="owner">{uiText("仅所有者")}</option>
      </Select>
    </label>
    <p
      className={ui.description}>{scope === "enterprise" ? uiText("上架后，当前企业成员可在员工广场查看并雇佣该员工。你可以缩小开放范围。")
      : scope === "owner" ? uiText("当前仅所有者可以查看并雇佣，其他成员不会在员工广场看到该员工。")
        : uiText("只有选中的成员或团队可以查看并雇佣；未选择对象时仅所有者可用。")}</p>
    {scope === "custom" &&
      <GrantFields enterpriseId={enterpriseId} resourceId={resourceId} grants={grants} onChange={onChange} useOnly/>}
  </div>}</AnimatedHeight>;
}
