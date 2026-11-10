import type {FileReference, RunQuestion} from "@/features/agent/types/execution";

export type RoomCandidate = {id: string; name: string; description: string; icon: string; color: string};
export type RoomSummary = {id: string; title: string; updatedAt: string};
export type RoomEntry = {id: string; taskId: string; sequence: number; authorName: string; recipientName?: string | null; authorIcon?: string | null; authorColor?: string | null; participantId: string | null; kind: string; text: string; createdAt: string; attachments: FileReference[]};
export type RoomMember = {id: string; agentId: string; name: string; icon: string | null; color: string | null; attachmentsEnabled: boolean; replyAttachmentsEnabled: boolean};
export type RoomDetail = {
  room: RoomSummary;
  members: RoomMember[];
  task: {id: string; status: string; revision: number; inputRevision: number; errorCode: string | null; ownerAgentId: string; attachmentsEnabled: boolean} | null;
  entries: RoomEntry[];
  questions: RunQuestion[];
  activeTaskIds: string[];
  queuedMessages?: {id: string; agentId: string; recipientName: string; text: string; selected: boolean}[];
};

export const collaborationHome = "/collaboration/tasks";
export const newRoomUrl = collaborationHome + "?create=1";
export const roomApi = (enterprise: string) => "/api/v1/enterprises/" + encodeURIComponent(enterprise) + "/collaboration";
export const roomUrl = (room: string) => "/collaboration/rooms/" + encodeURIComponent(room);

export function roomRefreshInterval(data: RoomDetail | null) {
  return data?.activeTaskIds?.length || data?.queuedMessages?.length ? 1800 : 0;
}
