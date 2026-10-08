"use client";
import {IconBuilding} from "@/components/ui/icons";
import {editionClientExtension} from "@/features/edition/client-extension";
import type {EnterpriseMenuProps} from "@/features/edition/types/client-extension";

export function EnterpriseMenu(props: EnterpriseMenuProps) {
  const Menu = editionClientExtension.enterpriseMenu;
  if (Menu && props.user.capabilities.includes("enterprise.switch")) {
    return <Menu {...props}/>;
  }
  return <span className="enterprise-trigger"><IconBuilding size={17}/><span>{props.context.enterprise.name}</span></span>;
}
