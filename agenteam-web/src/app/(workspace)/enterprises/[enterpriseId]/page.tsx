import {redirect} from "next/navigation";
import {enterprisePath} from "@/features/auth/lib/identity-navigation";

export default async function Page({params}: { params: Promise<{ enterpriseId: string }> }) {
  const {enterpriseId} = await params;
  redirect(enterprisePath(enterpriseId, "/workspace"));
}
