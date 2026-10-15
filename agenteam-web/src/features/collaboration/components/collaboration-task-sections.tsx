"use client";

import Link from "next/link";
import {useState} from "react";
import {AnimatedHeight} from "@/components/ui/animated-height";
import {Button} from "@/components/ui/button";
import {DetailSection} from "@/components/ui/detail-section";
import {Guidance} from "@/components/ui/guidance";
import {IconAlertCircle, IconCalendar, IconClock, IconDownload, IconFileText, IconHistory, IconMessages, IconNotes, IconPencil, IconRefresh, IconRobot, IconUser} from "@/components/ui/icons";
import {EnterpriseDateTime} from "@/features/auth/components/enterprise-date-time";
import {useT} from "@/lib/i18n/locale-provider";
import {collaborationErrors, type CollaborationArtifact, type CollaborationDetail} from "../types/collaboration";
import {CollaborationStatus} from "./collaboration-status";
import {CollaborationReplyControls} from "./collaboration-reply-controls";
import ui from "@/components/ui/surface.module.css";
import styles from "./collaboration.module.css";

const deliveryKinds: Record<string, string> = {
  accepted: "接收任务", started: "开始处理", input_added: "收到补充要求", waiting_input: "等待补充要求",
  waiting_approval: "等待确认操作", handed_off: "交接岗位工作", completed: "任务完成", completed_file: "发送成果文件",
  cancelled: "任务已停止", blocked: "需要处理", failed: "任务未完成", assigned: "安排岗位协作",
  waiting_dependency: "等待协作成果", work_completed: "协助工作完成",
  reply_settings: "回复方式",
};

function artifactPreview(summary: string) {
  const paragraph = summary.trim().split(/\r?\n\s*\r?\n/, 1)[0];
  return paragraph.length > 240 ? `${paragraph.slice(0, 240)}…` : paragraph;
}

function ExpandableText({text}: {text: string}) {
  const t = useT();
  const [expanded, setExpanded] = useState(false);
  const long = text.length > 420;
  return <div><AnimatedHeight><p className={styles.text}>{long && !expanded ? `${text.slice(0, 420)}…` : text}</p></AnimatedHeight>
    {long && <Button className={`${ui.button} ${styles.moreButton}`} aria-expanded={expanded} onClick={() => setExpanded((value) => !value)}>
      {t(expanded ? "收起" : "展开完整内容")}
    </Button>}
  </div>;
}

export function CollaborationRequirements({detail}: {detail: CollaborationDetail}) {
  const t = useT();
  return <DetailSection className={styles.section} icon={<IconNotes size={19}/>} title={t("任务要求")}>
    <div className={styles.requirements}><ExpandableText text={detail.requirements.text}/>
      {detail.requirements.additionalRequirements?.map((input, index) => <div key={index} className={styles.requirement}>
        <span className={styles.requirementLabel}><IconPencil size={14}/>{t("补充要求 {0}", [index + 1])}</span>
        <ExpandableText text={input.text}/>
      </div>)}
    </div>
  </DetailSection>;
}

export function CollaborationQuestions({detail, onSelect, selected, busy}: {
  detail: CollaborationDetail; onSelect: (questionId: string, text: string) => void; selected: Record<string, string>; busy: boolean;
}) {
  const t = useT();
  const questions = detail.questions.filter((question) => question.inputRevision === detail.task.inputRevision);
  if (!questions.length || detail.task.status !== "waiting_input") {
    return null;
  }
  return <Guidance title={t("回答后继续处理任务")} tone="warning" icon={<IconAlertCircle size={19}/>}>
    <div className={styles.list}>{questions.map((question) => <div key={question.id}>
      <p className={styles.text}>{question.text}</p>
      {question.options.length > 0 && <div className={styles.questionOptions}>{question.options.map((option) =>
        <Button key={option} type="button" className={ui.button} aria-pressed={selected[question.id] === option} disabled={busy}
          onClick={() => onSelect(question.id, option)}>{option}</Button>)}</div>}
    </div>)}</div>
    <p className={styles.answerHint}>{t("在下方填写回答，或回到原群聊回复提问并 @ 机器人。")}</p>
  </Guidance>;
}

export function CollaborationWorks({detail, root, busy, onRetry}: {
  detail: CollaborationDetail; root: string; busy: boolean; onRetry: (workId: string) => void;
}) {
  const t = useT();
  return <DetailSection className={styles.section} icon={<IconRobot size={19}/>} title={t("岗位工作")}>
    <div className={styles.workGrid}>{detail.works.map((work) => {
      const role = detail.roles.find((item) => item.id === work.roleId);
      return <article className={styles.card} key={work.id}>
        <div className={styles.roleHeading}><span className={styles.roleIcon} data-icon-surface><IconRobot size={20} variant="Bulk"/></span>
          <div className={styles.roleCopy}><h3>{role?.name ?? t("岗位")}</h3><p>{t(work.id === detail.ownerWorkId ? "负责最终交付" : work.purpose === "assist" ? "协助处理" : "岗位工作")}</p></div>
        </div>
        <div><CollaborationStatus status={work.status}/></div>
        {role?.description && <p className={styles.muted}>{role.description}</p>}
        {["blocked", "failed"].includes(work.status) && work.errorCode && collaborationErrors[work.errorCode]
          && <p className={styles.text}>{t(collaborationErrors[work.errorCode])}</p>}
        <div className={styles.actions}>
          {role?.conversationId && <Link className={work.status === "waiting_approval" ? ui.primary : ui.button}
            href={`${root}/conversations/${encodeURIComponent(role.conversationId)}`}>
            <IconMessages size={16}/>{t(work.status === "waiting_approval" ? "查看并确认操作" : "查看岗位对话")}
          </Link>}
          {["failed", "blocked", "cancelled"].includes(work.status) && work.inputRevision === detail.task.inputRevision
            && work.errorCode !== "TOOL_RESULT_UNKNOWN" && !work.activeRunId
            && ["active", "blocked", "waiting_input"].includes(detail.task.status) && <Button className={ui.button} disabled={busy} onClick={() => onRetry(work.id)}>
              <IconRefresh size={16}/>{t("重试此工作")}
            </Button>}
        </div>
      </article>;
    })}</div>
  </DetailSection>;
}

export function CollaborationArtifacts({artifacts, onOpen}: {artifacts: CollaborationArtifact[]; onOpen: (value: CollaborationArtifact) => void}) {
  const t = useT();
  return <DetailSection className={styles.section} icon={<IconDownload size={19}/>} title={t("协作成果")}>
    {artifacts.length ? artifacts.map((value) => <article className={styles.artifactRow} key={value.id}>
      <span className={styles.artifactIcon} data-icon-surface>{value.kind === "file" ? <IconDownload size={20}/> : <IconFileText size={20}/>}</span>
      <div className={styles.artifactCopy}><div className={styles.heading}><h3>{value.title}</h3><span className={styles.version}>{t("版本 {0}", [value.version])}</span></div>
        <p className={styles.summary}>{artifactPreview(value.summary)}</p>
        <div className={styles.actions}><Button className={ui.button} onClick={() => onOpen(value)}>
          {value.kind === "file" ? <IconDownload size={16}/> : <IconFileText size={16}/>}{t(value.kind === "file" ? "查看成果文件" : "查看完整成果")}
        </Button></div>
      </div>
    </article>) : <p className={styles.muted}>{t("岗位尚未提交成果。")}</p>}
  </DetailSection>;
}

export function CollaborationTaskInfo({detail, path, onChanged, openReplyMode}: {
  detail: CollaborationDetail; path: string; onChanged: () => void; openReplyMode?: boolean;
}) {
  const t = useT();
  return <DetailSection className={styles.section} icon={<IconFileText size={19}/>} title={t("任务信息")}>
    <dl className={styles.facts}>
      <div className={styles.fact}><dt><IconUser size={15}/>{t("当前负责人")}</dt><dd>{detail.roles.find((role) => role.id === detail.ownerRoleId)?.name ?? "—"}</dd></div>
      <div className={styles.fact}><dt><IconCalendar size={15}/>{t("创建时间")}</dt><dd><EnterpriseDateTime value={detail.task.createdAt}/></dd></div>
      <div className={styles.fact}><dt><IconClock size={15}/>{t(detail.task.finishedAt ? "结束时间" : "截止时间")}</dt><dd><EnterpriseDateTime value={detail.task.finishedAt ?? detail.task.deadlineAt}/></dd></div>
      <CollaborationReplyControls path={path} detail={detail} onChanged={onChanged} initiallyOpen={openReplyMode}/>
    </dl>
  </DetailSection>;
}

export function CollaborationDeliveries({detail}: {detail: CollaborationDetail}) {
  const t = useT();
  const [all, setAll] = useState(false);
  const ordered = detail.deliveries.filter((delivery) => delivery.kind !== "reply_settings_update")
    .sort((left, right) => right.createdAt.localeCompare(left.createdAt));
  const visible = all ? ordered : ordered.slice(0, 5);
  return <DetailSection className={styles.section} icon={<IconHistory size={19}/>} title={t("消息回传")}>
    <AnimatedHeight><div className={styles.deliveryList}>{visible.map((delivery) => <div className={styles.delivery} key={delivery.id}>
      <span className={styles.deliveryMark} data-status={delivery.status} aria-hidden="true"/>
      <div className={styles.deliveryTitle}><strong>{t(deliveryKinds[delivery.kind] ?? "发送消息")}</strong>
        <span className={styles.muted}>{detail.roles.find((role) => role.botId === delivery.botId)?.name ?? t("岗位机器人")}</span>
      </div>
      <div className={styles.deliveryMeta}><CollaborationStatus status={delivery.status} delivery/><EnterpriseDateTime value={delivery.createdAt}/></div>
    </div>)}</div></AnimatedHeight>
    {!ordered.length && <p className={styles.muted}>{t("暂无聊天发送记录。")}</p>}
    {ordered.length > 5 && <Button className={`${ui.button} ${styles.moreButton}`} aria-expanded={all} onClick={() => setAll((value) => !value)}>
      <IconHistory size={16}/>{t(all ? "收起" : "查看全部回传记录")}
    </Button>}
  </DetailSection>;
}
