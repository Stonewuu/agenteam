import {NotificationPage} from "@/features/notification/components/notification-page";

export default async function Page({params}: { params: Promise<{ enterpriseId: string }> }) {
  const {enterpriseId} = await params;
  return <NotificationPage enterpriseId={enterpriseId}/>;
}
