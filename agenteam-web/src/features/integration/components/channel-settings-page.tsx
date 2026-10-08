"use client";

import {PageHeader} from "@/components/ui/page-header";
import {IconPlug} from "@/components/ui/icons";
import {EnterpriseGate} from "@/features/auth/components/enterprise-gate";
import {PlatformShell} from "@/features/workspace/components/platform-shell";
import {useT} from "@/lib/i18n/locale-provider";
import {ChannelSettingsPanel} from "./channel-settings-panel";
import styles from "./integration.module.css";

export function ChannelSettingsPage({enterpriseId}: {enterpriseId: string}) {
  const t = useT();
  return <EnterpriseGate enterpriseId={enterpriseId}>{({user, context}) =>
    <PlatformShell user={user} context={context} area="user" title={t("企业账号绑定")}>
      <div className={styles.settingsPage}><PageHeader title={t("企业账号绑定")} icon={<IconPlug size={24} variant="Bulk"/>}/>
        <ChannelSettingsPanel enterpriseId={enterpriseId} superAdmin={user.superAdmin}/></div>
    </PlatformShell>}
  </EnterpriseGate>;
}
