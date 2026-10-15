import {CollaborationPage} from "@/features/collaboration/components/collaboration-page";

export default async function Page({params}: {params: Promise<{enterpriseId: string}>}) {
  const {enterpriseId} = await params;
  return <CollaborationPage enterpriseId={enterpriseId}/>;
}
