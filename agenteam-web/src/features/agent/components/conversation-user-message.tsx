"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";

import {useId, useLayoutEffect, useRef, useState} from "react";
import type {DisplayBlock, DisplayMessage} from "../types/conversation-display";
import {FileDownloadButton} from "@/features/file/components/file-download-button";
import {CitationCard} from "@/features/knowledge/components/citation-card";
import styles from "./conversation-user-message.module.css";
import skillStyles from "@/features/skill/components/workspace-skills.module.css";
import {EnterpriseDateTime} from "@/features/auth/components/enterprise-date-time";
import {IconChevronDown, IconUser} from "@/components/ui/icons";

export function ConversationUserMessage({message, enterprise}: { message: DisplayMessage; enterprise: string }) {
  const uiText = useT();
  const labels = message.blocks.filter((block): block is Extract<DisplayBlock, {
    kind: "status"
  }> => block.kind === "status");
  return <div className={styles.content}>
    <div className={styles.meta}><EnterpriseDateTime value={message.createdAt} compact/><span>{uiText("你")}</span><span
      className={styles.avatar}><IconUser size={18}/></span></div>
    {(message.content || labels.length > 0) &&
      <div className={styles.bubble}>{message.content && <CollapsibleUserText text={message.content}/>}
        {labels.length > 0 && <div className={skillStyles.selections}>{labels.map((block) => <span key={block.id}
                                                                                                   className={skillStyles.selection}>{block.label}</span>)}</div>}
      </div>}
    {message.attachments.map((file) => <FileDownloadButton key={file.id} enterpriseId={enterprise} file={file}/>)}
    {message.blocks.map((block) => block.kind === "citation" ?
      <CitationCard key={block.id} enterpriseId={enterprise} citation={block.citation}/> : null)}
  </div>;
}

function CollapsibleUserText({text}: { text: string }) {
  const uiText = useT();
  const frame = useRef<HTMLDivElement>(null);
  const content = useRef<HTMLDivElement>(null);
  const contentId = useId();
  const [expanded, setExpanded] = useState(false);
  const [measurement, setMeasurement] = useState({height: 0, limit: 240});
  useLayoutEffect(() => {
    const outer = frame.current;
    const inner = content.current;
    if (!outer || !inner) {
      return;
    }
    const measure = () => {
      const height = Math.ceil(inner.scrollHeight);
      const limit = Number.parseFloat(getComputedStyle(outer).getPropertyValue("--user-message-limit")) || 240;
      setMeasurement((previous) => previous.height === height && previous.limit === limit ? previous : {height, limit});
    };
    const observer = new ResizeObserver(measure);
    observer.observe(inner);
    measure();
    return () => observer.disconnect();
  }, [text]);
  const overflowing = measurement.height > measurement.limit + 1;
  return <div className={styles.text}>
    <div ref={frame} id={contentId} className={styles.textFrame} data-measured={measurement.height > 0}
         data-clipped={overflowing && !expanded}
         style={measurement.height ? {height: expanded ? measurement.height : Math.min(measurement.height, measurement.limit)} : undefined}>
      <div ref={content} className={styles.textContent}>{text}</div>
    </div>
    {overflowing && <Button className={styles.expand} type="button" aria-expanded={expanded} aria-controls={contentId}
                            onClick={() => setExpanded((value) => !value)}>{expanded ? uiText("收起消息") : uiText("展开完整消息")}<IconChevronDown
      size={15}/></Button>}
  </div>;
}
