import {CollaborationPage} from "@/features/collaboration/components/collaboration-page";

export default async function Page({searchParams}: {searchParams: Promise<{create?: string}>}) {
  return <CollaborationPage create={(await searchParams).create === "1"}/>;
}
