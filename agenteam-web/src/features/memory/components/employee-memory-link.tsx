"use client";

import {useT} from "@/lib/i18n/locale-provider";

import Link from "next/link";
import {IconAdjustments} from "@/components/ui/icons";
import {useApiQuery} from "@/lib/http/use-api-query";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {enterprisePath} from "@/features/auth/lib/identity-navigation";
import type {MemoryContext} from "../types/memory";
import ui from "@/components/ui/surface.module.css";

export function EmployeeMemoryLink({enterpriseId, agentId}: { enterpriseId: string; agentId: string }) {
  const uiText = useT();
  const context = useApiQuery<MemoryContext>(organizationPath(enterpriseId, `/agents/${encodeURIComponent(agentId)}/memories/context`));
  if (!context.data?.allowedTopics.length) {
    return null;
  }
  return <Link className={ui.button}
               href={enterprisePath(enterpriseId, `/memories/${encodeURIComponent(agentId)}`)}><IconAdjustments
    size={16}/>{uiText("管理个人偏好")}</Link>;
}
