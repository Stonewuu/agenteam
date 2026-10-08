import {TodoPage} from "@/features/todo/components/todo-page";

export default async function Page({params}: { params: Promise<{ enterpriseId: string }> }) {
  const {enterpriseId} = await params;
  return <TodoPage enterpriseId={enterpriseId}/>;
}
