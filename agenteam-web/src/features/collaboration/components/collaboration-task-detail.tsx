"use client";

import Link from "next/link";
import {useEffect, useState} from "react";
import {Button} from "@/components/ui/button";
import {IconCollaboration, IconArrowLeft, IconRefresh} from "@/components/ui/icons";
import {PageHeader} from "@/components/ui/page-header";
import {QueryState} from "@/components/ui/query-state";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import {useApiQuery} from "@/lib/http/use-api-query";
import {useT} from "@/lib/i18n/locale-provider";
import {useCollaborationAction} from "../hooks/use-collaboration-action";
import {collaborationErrors, collaborationPath, type CollaborationDetail, type CollaborationArtifact} from "../types/collaboration";
import {CollaborationTaskActions} from "./collaboration-task-actions";
import {CollaborationArtifactDialog} from "./collaboration-artifact-dialog";
import {CollaborationStatus} from "./collaboration-status";
import {CollaborationFiles} from "./collaboration-files";
import {CollaborationArtifacts, CollaborationDeliveries, CollaborationRequirements, CollaborationTaskInfo, CollaborationWorks} from "./collaboration-task-sections";
import ui from "@/components/ui/surface.module.css";
import styles from "./collaboration.module.css";

export function CollaborationTaskDetail({enterpriseId, taskId, openReplyMode}: {enterpriseId: string; taskId: string; openReplyMode?: boolean}) {
  const t = useT();
  const path = `${collaborationPath(enterpriseId)}/${encodeURIComponent(taskId)}`;
  const root = `/enterprises/${encodeURIComponent(enterpriseId)}`;
  const [refresh, setRefresh] = useState({version: 0, manual: false});
  const [artifact, setArtifact] = useState<CollaborationArtifact | null>(null);
  const query = useApiQuery<CollaborationDetail>(path, refresh.version);
  const detail = query.data;
  const taskStatus = detail?.task.status;
  const pendingDelivery = detail?.deliveries.some((delivery) => ["pending", "leased", "sending", "retry_wait"].includes(delivery.status)) ?? false;
  const action = useCollaborationAction();
  const changed = () => setRefresh((value) => ({version: value.version + 1, manual: true}));
  useEffect(() => {
    if (!taskStatus || query.loading || ["cancelled", "failed"].includes(taskStatus) && !pendingDelivery) {
      return;
    }
    const timer = window.setInterval(() => {
      if (!document.hidden) {
        setRefresh((value) => ({version: value.version + 1, manual: false}));
      }
    }, 5000);
    return () => window.clearInterval(timer);
  }, [taskStatus, pendingDelivery, query.loading]);
  return <div className={styles.page}>
    <PageHeader title={t("协作任务")} inlineMetadata icon={<IconCollaboration size={24} variant="Bulk"/>}
      metadata={detail ? <><CollaborationStatus status={detail.task.status}/><span className={styles.headerCode}>{detail.task.shortCode}</span></> : undefined}
      actions={<div className={styles.actions}><Link className={ui.button} href={`${root}/collaboration/tasks`}><IconArrowLeft size={16}/>{t("任务列表")}</Link>
        <Button className={ui.button} onClick={changed} disabled={query.loading && (refresh.manual || !detail)}><IconRefresh size={16}/>{t("刷新")}</Button></div>}/>
    <QueryState {...query} loading={query.loading && !detail} hasData={Boolean(detail)} empty={t("任务暂时无法读取。")}>
      {detail && <div className={styles.body}>
        {!["cancelled", "completed"].includes(detail.task.status) && detail.task.errorCode && collaborationErrors[detail.task.errorCode]
          ? <p className={styles.notice}>{t(collaborationErrors[detail.task.errorCode])}</p> : null}
        <div className={styles.layout}>
          <div className={styles.main}>
            <CollaborationTaskActions path={path} detail={detail} onChanged={changed}/>
            {detail.roles.filter((role) => role.conversationId).map((role) => <CollaborationFiles key={role.id} enterpriseId={enterpriseId}
              conversationId={role.conversationId!} roleName={role.name} refreshKey={`${detail.task.revision}:${refresh.version}`}/>)}
            {detail.task.status === "completed" && <CollaborationArtifacts artifacts={detail.artifacts} onOpen={setArtifact}/>}
            <CollaborationRequirements detail={detail}/>
            <CollaborationWorks detail={detail} root={root} busy={action.busy} onRetry={(workId) => {
              void action.execute(async () => {
                await action.submit(`${path}/retry`, {expectedRevision: detail.task.revision, workItemId: workId});
                changed();
              }, t("已请求重试岗位工作。"));
            }}/>
            <MutationFeedback action={action} onReload={changed}/>
            {detail.task.status !== "completed" && <CollaborationArtifacts artifacts={detail.artifacts} onOpen={setArtifact}/>}
          </div>
          <aside className={styles.aside} aria-label={t("任务信息与回传")}>
            <CollaborationTaskInfo detail={detail} path={path} onChanged={changed} openReplyMode={openReplyMode}/>
            <CollaborationDeliveries detail={detail}/>
          </aside>
        </div>
      </div>}
    </QueryState>
    {artifact && <CollaborationArtifactDialog enterpriseId={enterpriseId} path={path} artifact={artifact} onClose={() => setArtifact(null)}/>}
  </div>;
}
