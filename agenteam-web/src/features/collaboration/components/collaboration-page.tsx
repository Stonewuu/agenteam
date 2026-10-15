"use client";

import {EnterpriseGate} from "@/features/auth/components/enterprise-gate";
import {PlatformShell} from "@/features/workspace/components/platform-shell";
import {useT} from "@/lib/i18n/locale-provider";
import {CollaborationTaskDetail} from "./collaboration-task-detail";
import {CollaborationTaskList} from "./collaboration-task-list";

export function CollaborationPage({enterpriseId, taskId, openReplyMode}: {enterpriseId: string; taskId?: string; openReplyMode?: boolean}) {
  const t = useT();
  return <EnterpriseGate enterpriseId={enterpriseId} permission="agent.run">{({user, context}) => <PlatformShell user={user} context={context} area="user" title={t("协作任务")}>
    {taskId ? <CollaborationTaskDetail key={taskId} enterpriseId={enterpriseId} taskId={taskId} openReplyMode={openReplyMode}/>
      : <CollaborationTaskList enterpriseId={enterpriseId} userId={context.member.userId}/>}
  </PlatformShell>}</EnterpriseGate>;
}
