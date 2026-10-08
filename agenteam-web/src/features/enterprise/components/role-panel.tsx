"use client";

import { type ReactNode, useState } from "react";
import { useT } from "@/lib/i18n/locale-provider";
import { Button } from "@/components/ui/button";
import { PageHeader } from "@/components/ui/page-header";
import { SearchInput } from "@/components/ui/search-input";
import { IconShieldCheck } from "@/components/ui/icons";
import { useApiQuery } from "@/lib/http/use-api-query";
import { organizationPath } from "../api/organization-api";
import { useCollectionPage } from "../hooks/use-collection-page";
import type { Permission, Role } from "../types/organization";
import { CollectionStatus, statusLabel } from "./organization-shared";
import { RoleDetails } from "./role-details";
import styles from "./organization.module.css";

/** 角色列表和只读详情共有，管理动作由调用方提供。 */
export function RolePanel({
  enterpriseId,
  permissions,
  query,
  onQuery,
  refresh = 0,
  headerActions,
  toolbar,
  roleActions,
}: {
  enterpriseId: string;
  permissions: string[];
  query: string;
  onQuery: (value: string) => void;
  refresh?: number;
  headerActions?: ReactNode;
  toolbar?: ReactNode;
  roleActions?: (role: Role) => ReactNode;
}) {
  const uiText = useT();
  const list = useCollectionPage<Role>(enterpriseId, "roles", query, refresh);
  const [selected, setSelected] = useState<Role | null>(null);
  const canReadPermissions = permissions.includes("enterprise.permissions.view");
  const names = useApiQuery<Permission[]>(canReadPermissions ? organizationPath(enterpriseId, "/permissions") : null);
  return (
    <>
      <PageHeader
        title={uiText("角色与权限")}
        description={uiText("查看各角色的访问权限和成员数量。")}
        actions={headerActions}
      />
      <div className={styles.toolbar}>
        {toolbar}
        <SearchInput
          placeholder={uiText("搜索角色…")}
          aria-label={uiText("查找角色")}
          value={query}
          onChange={(event) => {
            list.first();
            onQuery(event.target.value);
          }}
        />
      </div>
      <CollectionStatus list={list} empty={query ? uiText("没有匹配的角色。") : uiText("暂无可查看的角色。")}>
        <div className={styles.cardGrid}>
          {list.page?.items.map((role) => (
            <article key={role.id} className={styles.managementCard}>
              <div className={styles.cardHeading}>
                <span className="avatar purple">
                  <IconShieldCheck size={25} />
                </span>
                <span className="badge neutral">
                  {role.builtin ? uiText("内置角色") : uiText(statusLabel(role.status))}
                </span>
              </div>
              <h2>{role.name}</h2>
              <p>{role.description}</p>
              <div className={styles.permissionSummary}>
                {names.data
                  ?.filter((item) => role.permissions.includes(item.code))
                  .slice(0, 3)
                  .map((item) => (
                    <span className="tag" key={item.code}>
                      {uiText(item.name)}
                    </span>
                  ))}
              </div>
              <footer className={styles.teamFooter}>
                <span>
                  {role.memberCount}
                  {uiText(" 位成员")}
                </span>
                <div className={styles.rowActions}>
                  <Button className={styles.textButton} onClick={() => setSelected(role)}>
                    {uiText("查看权限")}
                  </Button>
                  {roleActions?.(role)}
                </div>
              </footer>
            </article>
          ))}
        </div>
      </CollectionStatus>
      {selected && (
        <RoleDetails
          enterpriseId={enterpriseId}
          role={selected}
          canReadPermissions={canReadPermissions}
          onClose={() => setSelected(null)}
        />
      )}
    </>
  );
}
