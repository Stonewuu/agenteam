"use client";

import {useWorkspaceLocation} from "@/features/auth/components/workspace-location-provider";
import {NotificationPage} from "@/features/notification/components/notification-page";

export default function Page() {
  const {enterpriseId} = useWorkspaceLocation();
  return <NotificationPage enterpriseId={enterpriseId}/>;
}
