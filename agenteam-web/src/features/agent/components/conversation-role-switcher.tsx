"use client";

import Link from "next/link";
import {useRouter} from "next/navigation";
import {Button} from "@/components/ui/button";
import {ResourceAvatar} from "@/components/ui/resource-avatar";
import {IconChevronRight, IconCollaboration} from "@/components/ui/icons";
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuGroup, DropdownMenuItem, DropdownMenuLabel,
  DropdownMenuRadioGroup, DropdownMenuRadioItem, DropdownMenuSeparator, DropdownMenuTrigger
} from "@/components/ui/shadcn/dropdown-menu";
import {useT} from "@/lib/i18n/locale-provider";
import {collaborationStatus} from "@/features/collaboration/types/collaboration";
import type {CollaborationConversationGroup} from "../types/execution";
import styles from "./conversation-role-switcher.module.css";

export function ConversationRoleSwitcher({group, current, enterprise, taskId}: {
  group?: CollaborationConversationGroup | null; current: string; enterprise: string; taskId: string;
}) {
  const t = useT();
  const router = useRouter();
  const taskPath = `/enterprises/${encodeURIComponent(enterprise)}/collaboration/tasks/${encodeURIComponent(taskId)}`;
  return <DropdownMenu>
    <DropdownMenuTrigger render={<Button type="button" className={`icon-button ${styles.trigger}`} aria-label={t("协作任务")} title={t("协作任务")}>
      <IconCollaboration size={19}/>
    </Button>}/>
    <DropdownMenuContent className={styles.menu} align="end" sideOffset={8} aria-label={t("协作任务")}>
      {group && group.roles.length > 0 && <>
        <DropdownMenuGroup>
          <DropdownMenuLabel className={styles.label}>{t("切换岗位")}</DropdownMenuLabel>
          <DropdownMenuRadioGroup className={styles.roles} aria-label={t("岗位对话")} value={current} onValueChange={id => {
            router.push(`/enterprises/${encodeURIComponent(enterprise)}/conversations/${encodeURIComponent(id)}`, {scroll: false});
          }}>
            {group.roles.map(role => <DropdownMenuRadioItem key={role.id} value={role.conversationId ?? role.id}
              className={styles.role} closeOnClick disabled={!role.conversationId}
              title={!role.conversationId ? t("此岗位还没有对话记录") : undefined}>
              <ResourceAvatar icon={role.icon ?? ""} color={role.color ?? undefined} size="tiny"/>
              <span className={styles.name}>{role.name}</span>
              <small className={styles.status} data-status={role.status}>{t(collaborationStatus[role.status] ?? "等待开始")}</small>
            </DropdownMenuRadioItem>)}
          </DropdownMenuRadioGroup>
        </DropdownMenuGroup>
        <DropdownMenuSeparator/>
      </>}
      <DropdownMenuItem className={styles.details} render={<Link href={taskPath}/>}>
        <IconCollaboration size={17}/><span>{t("查看协作任务")}</span><IconChevronRight size={16}/>
      </DropdownMenuItem>
    </DropdownMenuContent>
  </DropdownMenu>;
}
