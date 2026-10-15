"use client";

import {useState} from "react";
import {Button} from "@/components/ui/button";
import {DetailSection} from "@/components/ui/detail-section";
import {Guidance} from "@/components/ui/guidance";
import {IconMessages, IconPencil} from "@/components/ui/icons";
import {Pagination, QueryState} from "@/components/ui/query-state";
import {useApiPage} from "@/lib/http/use-api-query";
import {useT} from "@/lib/i18n/locale-provider";
import {replyPreferencesPath, type ReplyPreference} from "../types/reply-preferences";
import {ReplyModeDialog} from "./reply-mode-dialog";
import {ReplySource} from "./reply-mode-choices";
import ui from "@/components/ui/surface.module.css";
import styles from "./reply-preferences.module.css";

export function ReplyPreferencesPanel({enterpriseId, selectedConversation}: {enterpriseId: string; selectedConversation?: string | null}) {
  const t = useT();
  const path = replyPreferencesPath(enterpriseId);
  const [refresh, setRefresh] = useState(0);
  const [editing, setEditing] = useState(selectedConversation ?? null);
  const query = useApiPage<ReplyPreference>(path, refresh);
  return <DetailSection title={t("我的回复偏好")} icon={<IconMessages size={20}/>}>
    <div className={styles.form}>
      <p className={styles.hint}>{t("设置你在不同群聊和私聊中新任务的回复方式。")}</p>
      <QueryState {...query} hasData={Boolean(query.data?.items.length)} empty={t("还没有机器人聊天记录。")}
        emptyContent={<Guidance title={t("先和机器人聊聊")} icon={<IconMessages size={20}/>}>
          {t("在飞书或企业微信与机器人对话后，可在这里设置默认回复方式。")}
        </Guidance>}>
        <div className={styles.list}>{query.data?.items.map((value) => <article className={styles.row} key={value.conversationId}>
          <div className={styles.rowCopy}><ReplySource value={value}/>
            <strong>{t(value.outputVisibility === "chat_full" ? "直接回复内容" : "仅发送链接")}</strong>
            {!value.fullAllowed && <small>{t("本群已设为仅发送链接")}</small>}</div>
          <Button className={ui.button} onClick={() => setEditing(value.conversationId)}><IconPencil size={16}/>{t("修改")}</Button>
        </article>)}</div>
      </QueryState>
      <Pagination {...query} hasMore={query.data?.hasMore}/>
    </div>
    {editing && <ReplyModeDialog path={`${path}/${encodeURIComponent(editing)}`} onClose={() => setEditing(null)} onSaved={() => {
      setEditing(null);
      setRefresh((value) => value + 1);
    }}/>}
  </DetailSection>;
}
