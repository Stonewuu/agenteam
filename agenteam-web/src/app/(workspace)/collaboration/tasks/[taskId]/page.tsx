"use client";

import {useWorkspaceLocation} from "@/features/auth/components/workspace-location-provider";
import {useParams, useSearchParams} from "next/navigation";
import {CollaborationPage} from "@/features/collaboration/components/collaboration-page";

export default function Page() {
  const {enterpriseId} = useWorkspaceLocation();
  const {taskId} = useParams<{taskId: string}>();
  const replyMode = useSearchParams().get("replyMode") ?? undefined;
  return <CollaborationPage enterpriseId={enterpriseId} taskId={taskId} openReplyMode={replyMode === "edit"}/>;
}
