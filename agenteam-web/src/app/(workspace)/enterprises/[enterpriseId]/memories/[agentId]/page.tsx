import {MemoryDetailPage} from "@/features/memory/components/memory-detail-page";

export default async function Page({params}: { params: Promise<{ enterpriseId: string; agentId: string }> }) {
  const {enterpriseId, agentId} = await params;
  return <MemoryDetailPage enterpriseId={enterpriseId} agentId={agentId}/>;
}
