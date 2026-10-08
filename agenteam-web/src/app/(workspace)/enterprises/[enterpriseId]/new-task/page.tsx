import {EnterpriseWorkspace} from "@/features/auth/components/enterprise-workspace";

export default async function Page({params}: { params: Promise<{ enterpriseId: string }> }) {
  const {enterpriseId} = await params;
  return <EnterpriseWorkspace enterpriseId={enterpriseId}/>;
}
