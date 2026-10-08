import type {EnterpriseMemberView} from "@/features/auth/types/identity";

export type Member = EnterpriseMemberView;
export type Actor = { id: string; displayName: string };
export type Versioned = { id: string; revision: string; createdAt: string; updatedAt: string };
export type Team = Versioned & {
  name: string;
  description: string;
  owner: Actor;
  status: "active" | "disabled";
  memberCount: number
};
export type Role = Versioned & {
  name: string;
  code: string;
  description: string;
  dataScope: "own" | "team" | "enterprise";
  permissions: string[];
  builtin: boolean;
  status: "active" | "disabled";
  memberCount: number
};
export type Permission = { code: string; name: string; area: "user" | "capabilities" | "admin" };
export type Invitation = Versioned & {
  email: string | null;
  displayName: string | null;
  teamIds: string[];
  roleIds: string[];
  status: "pending" | "accepted" | "expired" | "revoked";
  deliveryStatus: "pending" | "sent" | "failed";
  expiresAt: string;
  createdBy: Actor
};
export type Enterprise = Versioned & {
  name: string;
  description: string;
  contactEmail: string | null;
  timezone: string;
  quotaTimezone: string;
  pendingQuotaTimezone: string | null;
  retentionDays: number;
  status: "active" | "disabled"
};
export type PageData<T> = { items: T[]; nextCursor: string | null; hasMore: boolean };
export type Collection = "members" | "teams" | "roles" | "invitations";
export type EntityOption = { id: string; name: string; description?: string; disabled?: boolean };
