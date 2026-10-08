import type { IconShield } from "@/components/ui/icons";

export type ManagementNavigationItem = {
  id: string;
  label: string;
  permission: string;
  managePermission?: string;
  capability?: string;
  searchTerms?: string;
  hidden?: boolean;
  icon: typeof IconShield;
};

export type PlatformNavigationItem = {
  path: string;
  label: string;
  capability?: string;
  icon: typeof IconShield;
};

export type EditionNavigationExtension = {
  management: readonly ManagementNavigationItem[];
  platform: readonly PlatformNavigationItem[];
};
