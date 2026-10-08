"use client";

import { useT } from "@/lib/i18n/locale-provider";
import { Dialog, DialogCancel } from "@/components/ui/dialog";
import { QueryState } from "@/components/ui/query-state";
import { useApiQuery } from "@/lib/http/use-api-query";
import { organizationPath } from "../api/organization-api";
import type { Permission, Role } from "../types/organization";
import ui from "@/components/ui/surface.module.css";
import styles from "./role-details.module.css";

/** 两版共有的角色只读详情，不加载角色写入接口。 */
export function RoleDetails({
  enterpriseId,
  role,
  canReadPermissions,
  onClose,
}: {
  enterpriseId: string;
  role: Role;
  canReadPermissions: boolean;
  onClose: () => void;
}) {
  const uiText = useT();
  const catalog = useApiQuery<Permission[]>(canReadPermissions ? organizationPath(enterpriseId, "/permissions") : null);
  const permissions = catalog.data?.filter((permission) => role.permissions.includes(permission.code));
  return (
    <Dialog
      title={role.name}
      onClose={onClose}
      wide
      footer={<DialogCancel className={ui.button}>{uiText("关闭")}</DialogCancel>}
    >
      <div className={styles.content}>
        <p>{role.description || uiText("暂无简介")}</p>
        <dl className={styles.facts}>
          <dt>{uiText("数据范围")}</dt>
          <dd>{uiText({ own: "本人", team: "所在团队", enterprise: "企业" }[role.dataScope])}</dd>
          <dt>{uiText("成员")}</dt>
          <dd>
            {role.memberCount}
            {uiText(" 位")}
          </dd>
        </dl>
        {canReadPermissions && (
          <QueryState {...catalog} hasData={Boolean(permissions?.length)} empty={uiText("暂无可查看的权限。")}>
            <ul className={styles.permissions}>
              {permissions?.map((permission) => (
                <li key={permission.code}>{uiText(permission.name)}</li>
              ))}
            </ul>
          </QueryState>
        )}
      </div>
    </Dialog>
  );
}
