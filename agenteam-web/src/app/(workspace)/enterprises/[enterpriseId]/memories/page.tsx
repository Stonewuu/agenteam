import {MemoryPage} from "@/features/memory/components/memory-page";

export default async function Page({params}: { params: Promise<{ enterpriseId: string }> }) {
  const {enterpriseId} = await params;
  return <MemoryPage enterpriseId={enterpriseId}/>;
}
