import {redirect} from "next/navigation";
import {collaborationHome} from "@/features/collaboration/types/web-room";

export default function Page() {
  redirect(collaborationHome);
}
