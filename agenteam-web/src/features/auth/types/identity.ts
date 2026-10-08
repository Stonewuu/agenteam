import type {Locale} from "@/lib/i18n/locales";

export type EnterpriseChoice = {
  id: string;
  name: string;
  description: string;
  status: "active" | "disabled";
  timezone: string;
};

export type Preferences = {
  theme: "light" | "dark" | "system";
  taskCompletionNotifications: boolean;
  memoryEnabled: boolean;
  responseLanguage: Locale;
  revision: string;
};

export type IdentityUser = {
  capabilities: string[];
  authenticationMethod?: "password" | "channel";
  restrictedEnterpriseId?: string | null;
  id: string;
  username: string;
  displayName: string;
  email: string | null;
  emailVerified: boolean;
  superAdmin: boolean;
  lastEnterpriseId: string | null;
  enterprises: EnterpriseChoice[];
  preferences: Preferences;
  revision: string;
};

export type EnterpriseMemberView = {
  userId: string;
  displayName: string;
  email: string | null;
  status: "active" | "disabled" | "removed";
  teamIds: string[];
  roleIds: string[];
  joinedAt: string;
  revision: string;
};

export type EnterpriseContext = {
  capabilities: string[];
  enterprise: EnterpriseChoice;
  member: EnterpriseMemberView;
  permissions: string[];
  permissionVersion: string;
  menus: { key: string; label: string; area: string; path: string }[];
};

export type BootstrapInput = {
  setupCredential: string;
  username: string;
  displayName: string;
  password: string;
  enterpriseName: string;
  email: string;
  timezone: string;
};
