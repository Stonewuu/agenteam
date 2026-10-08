"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Collapsible, CollapsibleContent, CollapsibleTrigger} from "@/components/ui/shadcn/collapsible";
import type {ReactNode} from "react";
import {IconBulb, IconChevronDown} from "@/components/ui/icons";
import type {BlockStatus} from "../types/execution";
import {useThinkingScroll} from "../hooks/use-thinking-scroll";
import {useExecutionDisclosure} from "../hooks/use-execution-disclosure";
import styles from "./conversation-execution.module.css";

/** 思考输出时默认展开，结束两秒后收起一次，保留用户手动展开或收起的选择。 */
export function ConversationThinkingBlock({status, children}: {
  status: BlockStatus; children: ReactNode;
}) {
  const uiText = useT();
  const {open, setOpen, finished} = useExecutionDisclosure(status);
  const scroll = useThinkingScroll(open, !finished);

  return <Collapsible className={styles.block} data-kind="thinking" open={open}
                      onOpenChange={setOpen}>
    <CollapsibleTrigger className={styles.thinkingTrigger}>
      <span className={styles.blockIcon}><IconBulb size={17}/></span>
      <strong>{finished ? uiText("已思考") : uiText("思考中")}</strong><IconChevronDown size={14}/>
    </CollapsibleTrigger>
    <CollapsibleContent className={styles.thinkingPanel} keepMounted>
      <div {...scroll} className={styles.thinkingScroll} role="region" aria-label={uiText("思考内容")}
           tabIndex={open ? 0 : -1} inert={!open}>
        <div className={styles.body}>{children}</div>
      </div>
    </CollapsibleContent>
  </Collapsible>;
}
