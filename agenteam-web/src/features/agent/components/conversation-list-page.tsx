"use client";

import {EnterpriseGate} from "@/features/auth/components/enterprise-gate";
import ConversationWorkspace from "./conversation-workspace";

/** 会话列表由持续挂载的布局提供，此入口直接展示对话工作区。 */
export function ConversationListPage({enterpriseId}: { enterpriseId: string }) {
  return <EnterpriseGate enterpriseId={enterpriseId} permission="conversation.view">{({user, context}) =>
    <ConversationWorkspace key={`${user.id}:${enterpriseId}`} user={user} context={context}/>}</EnterpriseGate>;
}
