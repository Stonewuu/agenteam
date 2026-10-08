import {TodoDetailPage} from "@/features/todo/components/todo-detail-page";

export default async function Page({params}: { params: Promise<{ enterpriseId: string; todoId: string }> }) {
  const {enterpriseId, todoId} = await params;
  return <TodoDetailPage enterpriseId={enterpriseId} todoId={todoId}/>;
}
