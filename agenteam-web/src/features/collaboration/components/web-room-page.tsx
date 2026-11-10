"use client";

import {EnterpriseGate} from "@/features/auth/components/enterprise-gate";
import {useWorkspaceLocation} from "@/features/auth/components/workspace-location-provider";
import {PlatformShell} from "@/features/workspace/components/platform-shell";
import {useT} from "@/lib/i18n/locale-provider";
import {WebRoomConversation} from "./web-room-conversation";

export function WebRoomPage({room}: {room: string}) {
  const {enterpriseId} = useWorkspaceLocation();
  const t = useT();
  return <EnterpriseGate enterpriseId={enterpriseId} permission="agent.run">{({user, context}) =>
    <PlatformShell user={user} context={context} area="user" title={t("协作任务")}>
      <WebRoomConversation key={enterpriseId + room} enterprise={enterpriseId} id={room} user={user.id}/>
    </PlatformShell>}</EnterpriseGate>;
}
