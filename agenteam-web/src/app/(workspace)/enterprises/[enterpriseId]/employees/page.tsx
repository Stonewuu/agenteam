import {EmployeePage} from "@/features/employee/components/employee-page";

export default async function Page({params}: { params: Promise<{ enterpriseId: string }> }) {
  const {enterpriseId} = await params;
  return <EmployeePage enterpriseId={enterpriseId}/>;
}
