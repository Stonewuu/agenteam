"use client";

import {useWorkspaceLocation} from "@/features/auth/components/workspace-location-provider";
import {ChannelSettingsPage} from "@/features/integration/components/channel-settings-page";

export default function Page() {
  const {enterpriseId} = useWorkspaceLocation();
  return <ChannelSettingsPage enterpriseId={enterpriseId}/>;
}
