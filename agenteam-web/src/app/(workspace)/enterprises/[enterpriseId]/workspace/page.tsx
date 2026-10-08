import {HomePage} from "@/features/workspace/components/home-page";

export default async function Page({params}: { params: Promise<{ enterpriseId: string }> }) {
  const {enterpriseId} = await params;
  return <HomePage enterpriseId={enterpriseId}/>;
}
