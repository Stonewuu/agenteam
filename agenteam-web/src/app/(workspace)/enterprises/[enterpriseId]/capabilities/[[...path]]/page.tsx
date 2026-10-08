import {notFound} from "next/navigation";
import {CapabilitiesPage} from "@/features/resource/components/capabilities-page";
import {resourceTypes} from "@/features/resource/lib/resource-display";

export default async function Page({params}: { params: Promise<{ enterpriseId: string; path?: string[] }> }) {
  const {enterpriseId, path} = await params;
  if (path && (path.length > 2 || !resourceTypes.some((type) => type.path === path[0]))) {
    notFound();
  }
  return <CapabilitiesPage enterpriseId={enterpriseId} section={path?.[0]} resourceId={path?.[1]}/>;
}
