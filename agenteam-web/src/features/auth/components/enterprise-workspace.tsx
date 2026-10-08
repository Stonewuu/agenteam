"use client";

import {useT} from "@/lib/i18n/locale-provider";

import Link from "next/link";
import ConversationWorkspace from "@/features/agent/components/conversation-workspace";
import {enterprisePath} from "../lib/identity-navigation";
import {EnterpriseGate} from "./enterprise-gate";
import styles from "./auth.module.css";

export function EnterpriseWorkspace({enterpriseId}: { enterpriseId: string }) {
  const uiText = useT();
  return <EnterpriseGate key={enterpriseId} enterpriseId={enterpriseId} permission="workspace.view">
    {({user, context}) => context.permissions.includes("conversation.view") ?
      <ConversationWorkspace key={`${user.id}:${enterpriseId}`} user={user} context={context}/>
      : <main className={styles.page}>
        <section className={styles.card}><h1 className={styles.title}>{uiText("暂时无法打开对话")}</h1>
          <p className={styles.description}>{uiText("当前账号没有查看对话的权限，请联系企业管理员。")}</p>
          <div className={styles.actions}><Link className={styles.textLink} href="/settings">{uiText("个人设置")}</Link>
            {context.permissions.includes("admin.view") && <Link className={styles.textLink}
                                                                 href={enterprisePath(enterpriseId, "/management")}>{uiText("企业管理")}</Link>}
          </div>
        </section>
      </main>}
  </EnterpriseGate>;
}
