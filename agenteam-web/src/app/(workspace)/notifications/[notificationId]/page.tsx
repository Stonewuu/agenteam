"use client";

import {useWorkspaceLocation} from "@/features/auth/components/workspace-location-provider";
import {useParams} from "next/navigation";
import {NotificationDetailPage} from "@/features/notification/components/notification-detail-page";

export default function Page() {
  const {enterpriseId} = useWorkspaceLocation();
  const {notificationId} = useParams<{notificationId: string}>();
  return <NotificationDetailPage enterpriseId={enterpriseId} notificationId={notificationId}/>;
}
