"use client";

import {useParams, useSearchParams} from "next/navigation";
import {CollaborationPage} from "@/features/collaboration/components/collaboration-page";

export default function Page() {
  const {taskId} = useParams<{taskId: string}>();
  const replyMode = useSearchParams().get("replyMode") ?? undefined;
  return <CollaborationPage taskId={taskId} openReplyMode={replyMode === "edit"}/>;
}
