"use client";

import {useWorkspaceLocation} from "@/features/auth/components/workspace-location-provider";
import {TodoPage} from "@/features/todo/components/todo-page";

export default function Page() {
  const {enterpriseId} = useWorkspaceLocation();
  return <TodoPage enterpriseId={enterpriseId}/>;
}
