"use client";

import {useWorkspaceLocation} from "@/features/auth/components/workspace-location-provider";
import {EmployeePage} from "@/features/employee/components/employee-page";

export default function Page() {
  const {enterpriseId} = useWorkspaceLocation();
  return <EmployeePage enterpriseId={enterpriseId}/>;
}
