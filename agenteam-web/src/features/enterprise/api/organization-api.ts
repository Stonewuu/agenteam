import {apiRequest} from "@/lib/http/api-client";
import type {Collection, EntityOption, Member, PageData, Role, Team} from "../types/organization";

export function organizationPath(enterpriseId: string, suffix = "") {
  return `/api/v1/enterprises/${encodeURIComponent(enterpriseId)}${suffix}`;
}

export function readCollection<T>(enterpriseId: string, collection: string, query = "", cursor?: string | null, signal?: AbortSignal, limit = 30) {
  const params = new URLSearchParams({limit: String(limit)});
  if (query) {
    params.set("query", query);
  }
  if (cursor) {
    params.set("cursor", cursor);
  }
  return apiRequest<PageData<T>>(`${organizationPath(enterpriseId)}/${collection}?${params}`, {signal});
}

export async function readTeamMembers(enterpriseId: string, teamId: string, signal?: AbortSignal) {
  const members: Member[] = [];
  let cursor: string | null = null;
  for (let page = 0; page < 6; page++) {
    const response: PageData<Member> = await readCollection(enterpriseId, `teams/${encodeURIComponent(teamId)}/members`, "", cursor, signal, 100);
    members.push(...response.items);
    if (!response.hasMore) {
      return members;
    }
    cursor = response.nextCursor;
  }
  throw new Error("团队成员数量已变化，请重新加载后再编辑。");
}

export function entityOption(collection: Collection, item: Member | Team | Role): EntityOption {
  if (collection === "members") {
    const member = item as Member;
    return {
      id: member.userId,
      name: member.displayName,
      description: member.email ?? undefined,
      disabled: member.status !== "active"
    };
  }
  const entity = item as Team | Role;
  return {id: entity.id, name: entity.name, disabled: entity.status !== "active"};
}
