import {CollaborationPage} from "@/features/collaboration/components/collaboration-page";

export default async function Page({params, searchParams}: {
  params: Promise<{enterpriseId: string; taskId: string}>; searchParams: Promise<{replyMode?: string}>;
}) {
  const {enterpriseId, taskId} = await params;
  const {replyMode} = await searchParams;
  return <CollaborationPage enterpriseId={enterpriseId} taskId={taskId} openReplyMode={replyMode === "edit"}/>;
}
