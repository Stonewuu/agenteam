import {NotificationDetailPage} from "@/features/notification/components/notification-detail-page";

export default async function Page({params}: {params: Promise<{enterpriseId: string; notificationId: string}>}) {
  const {enterpriseId, notificationId} = await params;
  return <NotificationDetailPage enterpriseId={enterpriseId} notificationId={notificationId}/>;
}
