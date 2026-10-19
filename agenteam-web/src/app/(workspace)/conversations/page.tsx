"use client";

import {useWorkspaceLocation} from "@/features/auth/components/workspace-location-provider";
import {ConversationListPage} from "@/features/agent/components/conversation-list-page";

export default function Page() {
  const {enterpriseId} = useWorkspaceLocation();
  return <ConversationListPage enterpriseId={enterpriseId}/>;
}
