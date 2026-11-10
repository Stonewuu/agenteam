import type {RoomEntry} from "../types/web-room";

const completedKinds = new Set(["completed", "work_completed"]);

/** 只合并连续的同一成员记录，成员切换或一轮回复结束后另起一段。 */
export function groupRoomEntries(entries: RoomEntry[]): RoomEntry[][] {
  const turns: RoomEntry[][] = [];
  for (const entry of entries) {
    const current = turns.at(-1);
    const previous = current?.at(-1);
    if (current && previous && entry.participantId && entry.participantId === previous.participantId
      && entry.taskId === previous.taskId && !completedKinds.has(previous.kind)) {
      current.push(entry);
    } else {
      turns.push([entry]);
    }
  }
  return turns;
}
