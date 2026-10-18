import {EnvironmentPage} from "@/features/environment/components/environment-page";

export default async function Page({params}: {params: Promise<{enterpriseId: string}>}) {
  const {enterpriseId} = await params;
  return <EnvironmentPage enterpriseId={enterpriseId}/>;
}
