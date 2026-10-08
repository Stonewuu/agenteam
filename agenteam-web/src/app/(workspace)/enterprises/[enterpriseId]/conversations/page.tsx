import {ConversationListPage} from "@/features/agent/components/conversation-list-page";

export default async function Page({params}: { params: Promise<{ enterpriseId: string }> }) {
  const {enterpriseId} = await params;
  return <ConversationListPage enterpriseId={enterpriseId}/>;
}
