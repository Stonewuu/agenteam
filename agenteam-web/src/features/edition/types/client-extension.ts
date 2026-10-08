import type { ComponentType } from "react";
import type { EnterpriseChoice, EnterpriseContext, IdentityUser } from "@/features/auth/types/identity";
import type { EntityOption } from "@/features/enterprise/types/organization";
import type { Grant } from "@/features/resource/types/resource";

export type EnterpriseMenuProps = {
  user: IdentityUser;
  context: EnterpriseContext;
  suffix: string;
  go: (action: () => void) => void;
};

export type EnterpriseCreationProps = {
  actor: EntityOption;
  lookupEnterpriseId?: string;
  onClose: () => void;
  onCreated?: () => void;
};

export type EnterpriseSelectionProps = {
  enterprises: EnterpriseChoice[];
  selectedId: string;
  onSelect: (id: string) => void;
  disabled?: boolean;
};

export type EditionManagementProps = {
  user: IdentityUser;
  context: EnterpriseContext;
  query: string;
  onQuery: (value: string) => void;
};

/** 扩展组件只决定页面组合，后端继续独立核对权限与实际许可。 */
export type EditionClientExtension = {
  edition: "community" | "pro";
  enterpriseMenu?: ComponentType<EnterpriseMenuProps>;
  enterpriseCreation?: ComponentType<EnterpriseCreationProps>;
  enterpriseSelection?: ComponentType<EnterpriseSelectionProps>;
  resourceGrantSubjects?: readonly { type: Grant["subjectType"]; label: string; capability: string }[];
  managementPanels: Readonly<
    Record<
      string,
      {
        component: ComponentType<EditionManagementProps>;
        capability: string;
      }
    >
  >;
};
