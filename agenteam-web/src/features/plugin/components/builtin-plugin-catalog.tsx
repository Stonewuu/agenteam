"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";

import {Dialog} from "@/components/ui/dialog";
import {ResourceAvatar} from "@/components/ui/resource-avatar";
import {QueryState} from "@/components/ui/query-state";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {useApiQuery} from "@/lib/http/use-api-query";
import type {BuiltinPlugin} from "../types/plugin";
import {resourceDisplayName, resourceDisplayDescription} from "@/features/resource/lib/resource-localization";
import ui from "@/components/ui/surface.module.css";
import styles from "./builtin-plugin-catalog.module.css";

export function BuiltinPluginCatalog({enterpriseId, canCreate, onChoose, onClose}: {
  enterpriseId: string; canCreate: boolean; onChoose: (plugin: BuiltinPlugin) => void; onClose: () => void;
}) {
  const uiText = useT();
  const list = useApiQuery<BuiltinPlugin[]>(organizationPath(enterpriseId, "/plugins/builtins"));
  return <Dialog title={uiText("内置插件")} onClose={onClose} wide>
    <QueryState {...list} hasData={Boolean(list.data?.length)} empty={uiText("暂无可用的内置插件。")}>
      <div className={styles.grid}>{list.data?.map((plugin) => <article key={plugin.code} className={styles.card}>
        <ResourceAvatar icon={plugin.icon} color={plugin.color} />
        <div className={styles.content}><h3>{resourceDisplayName({...plugin, source: "builtin", builtinCode: plugin.code}, uiText)}</h3><p>{resourceDisplayDescription({...plugin, source: "builtin", builtinCode: plugin.code}, uiText)}</p></div>
        <div className={styles.footer}>{plugin.toolNames && <span>{plugin.toolNames.length}{uiText(" 个工具")}</span>}
          {canCreate &&
            <Button type="button" className={ui.button} aria-label={uiText("自定义{0}", [resourceDisplayName({...plugin, source: "builtin", builtinCode: plugin.code}, uiText)])}
                    onClick={() => onChoose(plugin)}>{uiText("自定义工具")}</Button>}</div>
      </article>)}</div>
    </QueryState>
  </Dialog>;
}
