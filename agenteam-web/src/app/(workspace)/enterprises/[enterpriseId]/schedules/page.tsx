import {SchedulePage} from "@/features/schedule/components/schedule-page";

export default async function Page({params, searchParams}: {
  params: Promise<{ enterpriseId: string }>;
  searchParams: Promise<{ agent?: string | string[] }>
}) {
  const {enterpriseId} = await params;
  const {agent} = await searchParams;
  return <SchedulePage enterpriseId={enterpriseId} initialAgent={typeof agent === "string" ? agent : undefined}/>;
}
