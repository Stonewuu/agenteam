"use client";

import {useWorkspaceLocation} from "@/features/auth/components/workspace-location-provider";
import {useParams} from "next/navigation";
import {TodoDetailPage} from "@/features/todo/components/todo-detail-page";

export default function Page() {
  const {enterpriseId} = useWorkspaceLocation();
  const {todoId} = useParams<{todoId: string}>();
  return <TodoDetailPage enterpriseId={enterpriseId} todoId={todoId}/>;
}
