import {ChannelSettingsPage} from "@/features/integration/components/channel-settings-page";

export default async function Page({params}: {params: Promise<{enterpriseId: string}>}) {
  const {enterpriseId} = await params;
  return <ChannelSettingsPage enterpriseId={enterpriseId}/>;
}
