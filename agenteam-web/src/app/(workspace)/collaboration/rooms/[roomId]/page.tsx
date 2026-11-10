import {redirect} from "next/navigation";
import {newRoomUrl} from "@/features/collaboration/types/web-room";
import {WebRoomPage} from "@/features/collaboration/components/web-room-page";

export default async function Page({params}: {params: Promise<{roomId: string}>}) {
  const {roomId} = await params;
  if (roomId === "new") {
    redirect(newRoomUrl);
  }
  return <WebRoomPage room={roomId}/>;
}
