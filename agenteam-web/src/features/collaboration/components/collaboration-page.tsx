"use client";

import {useWorkspaceLocation} from "@/features/auth/components/workspace-location-provider";
import {EnterpriseGate} from "@/features/auth/components/enterprise-gate";
import {PlatformShell} from "@/features/workspace/components/platform-shell";
import {useT} from "@/lib/i18n/locale-provider";
import {CollaborationTaskDetail} from "./collaboration-task-detail";
import {CollaborationTaskList} from "./collaboration-task-list";

export function CollaborationPage({taskId, openReplyMode, create = false}: {taskId?: string; openReplyMode?: boolean; create?: boolean}) {
  const {enterpriseId} = useWorkspaceLocation();
  const t = useT();
  return <EnterpriseGate enterpriseId={enterpriseId} permission="agent.run">{({user, context}) => <PlatformShell user={user} context={context} area="user" title={t("协作任务")}>
    {taskId ? <CollaborationTaskDetail key={taskId} enterpriseId={enterpriseId} taskId={taskId} openReplyMode={openReplyMode}/>
      : <CollaborationTaskList enterpriseId={enterpriseId} userId={context.member.userId} create={create}/>}
  </PlatformShell>}</EnterpriseGate>;
}
