export const taskStatusFilters = ["active", "waiting_input", "waiting_approval", "blocked", "completed", "failed", "cancelled", "cancelling"] as const;

export type CollaborationListState = {
  query: string; status: string; cursor: string | null; previous: (string | null)[]; scrollTop: number;
};

export const emptyListState: CollaborationListState = {query: "", status: "", cursor: null, previous: [], scrollTop: 0};

export function listStateKey(enterprise: string, user: string) {
  return `agenteam:collaboration-list:v1:${encodeURIComponent(enterprise)}:${encodeURIComponent(user)}`;
}

export function readListState(value: unknown): CollaborationListState {
  if (!value || typeof value !== "object") {
    return emptyListState;
  }
  const state = value as Record<string, unknown>;
  const validCursor = (cursor: unknown): cursor is string | null => cursor === null || typeof cursor === "string" && cursor.length > 0 && cursor.length <= 2048;
  const validStatus = state.status === "" || taskStatusFilters.some(status => status === state.status);
  if (typeof state.query !== "string" || [...state.query].length > 100 || !validStatus || !validCursor(state.cursor)
    || !Array.isArray(state.previous) || !state.previous.every(validCursor)) {
    return emptyListState;
  }
  return {query: state.query, status: state.status as string, cursor: state.cursor, previous: state.previous,
    scrollTop: typeof state.scrollTop === "number" && Number.isFinite(state.scrollTop) ? Math.max(0, state.scrollTop) : 0};
}

export function changeListCriteria(state: CollaborationListState, values: Partial<Pick<CollaborationListState, "query" | "status">>): CollaborationListState {
  return {...state, ...values, cursor: null, previous: [], scrollTop: 0};
}
