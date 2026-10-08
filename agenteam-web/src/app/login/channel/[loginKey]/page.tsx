import {ChannelLoginPage} from "@/features/integration/components/channel-login-page";

export default async function Page({params}: {params: Promise<{loginKey: string}>}) {
  const {loginKey} = await params;
  return <ChannelLoginPage loginKey={loginKey}/>;
}
