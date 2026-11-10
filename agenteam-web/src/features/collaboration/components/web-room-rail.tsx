"use client";

import Link from "next/link";
import {usePathname} from "next/navigation";
import {useState} from "react";
import {Button} from "@/components/ui/button";
import {IconCollaboration} from "@/components/ui/icons";
import {workspaceParts} from "@/features/auth/lib/workspace-path";
import {EnterpriseDateTime} from "@/features/auth/components/enterprise-date-time";
import {useApiQuery} from "@/lib/http/use-api-query";
import {useT} from "@/lib/i18n/locale-provider";
import {localizeUiMessage} from "@/lib/i18n/ui-message";
import {collaborationHome, roomApi, roomUrl, type RoomSummary} from "../types/web-room";
import styles from "@/features/agent/components/conversation-frame.module.css";

export function WebRoomRail({enterprise, onNavigate}: {enterprise: string; onNavigate: () => void}) {
  const t = useT();
  const current = workspaceParts(usePathname())[2];
  const [page, setPage] = useState(1);
  const list = useApiQuery<RoomSummary[]>(roomApi(enterprise) + "/rooms?page=" + page);
  return <div className={styles.rail}>
    <header className={styles.railHeader}><h2>{t("协作对话")}</h2></header>
    <div className={styles.railBody}>
      <Link className={styles.roomDirectory} href={collaborationHome} onClick={onNavigate}><IconCollaboration size={17}/>{t("全部协作任务")}</Link>
      <nav className={styles.railList} aria-label={t("协作对话列表")} aria-busy={list.loading}>
        {list.error && <div className={styles.railError} role="alert"><p>{localizeUiMessage(list.error, t)}</p>
          <Button type="button" onClick={list.retry}>{t("重新加载")}</Button></div>}
        {list.data?.map(room => <article className={styles.conversationItem} key={room.id} data-active={room.id === current}>
          <Link className={styles.conversationLink} href={roomUrl(room.id)} aria-current={room.id === current ? "page" : undefined}
            onClick={onNavigate}><strong title={room.title}>{room.title}</strong>
            <span className={styles.conversationMeta}><EnterpriseDateTime value={room.updatedAt} compact/></span></Link>
        </article>)}
        {(page > 1 || list.data?.length === 30) && <div className={styles.filterTools}>
          <Button type="button" className="text-button" disabled={page <= 1 || list.loading} onClick={() => setPage(page - 1)}>{t("上一页")}</Button>
          <Button type="button" className="text-button" disabled={list.data?.length !== 30 || list.loading} onClick={() => setPage(page + 1)}>{t("下一页")}</Button>
        </div>}
      </nav>
    </div>
  </div>;
}
