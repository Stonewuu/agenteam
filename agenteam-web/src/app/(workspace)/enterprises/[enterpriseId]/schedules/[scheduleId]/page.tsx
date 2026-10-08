import {ScheduleDetailPage} from "@/features/schedule/components/schedule-detail-page";

export default async function Page({params}: { params: Promise<{ enterpriseId: string; scheduleId: string }> }) {
  const {enterpriseId, scheduleId} = await params;
  return <ScheduleDetailPage enterpriseId={enterpriseId} scheduleId={scheduleId}/>;
}
