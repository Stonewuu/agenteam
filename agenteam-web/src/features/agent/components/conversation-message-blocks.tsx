"use client";

import {localizeSavedToolLabel} from "@/features/plugin/lib/tool-display-name";

import {useT} from "@/lib/i18n/locale-provider";
import {localizeCatalog} from "@/lib/i18n/translate";

import {Button} from "@/components/ui/button";
import {ResourceAvatar} from "@/components/ui/resource-avatar";

import {MarkdownContent} from "@/components/ui/markdown-content";
import type {BlockStatus, RunApproval} from "../types/execution";
import type {DisplayBlock} from "../types/conversation-display";
import styles from "./assistant-message.module.css";
import {useApiQuery} from "@/lib/http/use-api-query";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {RunApprovalCard} from "./run-approval-card";
import {
  ConversationFileAttachment,
  ConversationFileImage,
  ConversationFileLink
} from "@/features/file/components/conversation-message-file";
import {CitationCard} from "@/features/knowledge/components/citation-card";
import {ToolCallDetails} from "@/features/plugin/components/tool-call-details";
import {toolPayloadFailure} from "@/features/plugin/lib/tool-payload-format";
import {ToolContentReader} from "@/features/plugin/components/tool-content-reader";
import {ToolContentVisibility} from "@/features/plugin/components/tool-content-visibility";
import {ToolContentBoundary} from "@/features/plugin/components/tool-content-boundary";
import type {ToolReadingPosition} from "@/features/plugin/types/tool-content";
import {IconArrowsSplit, IconBulb, IconChevronDown, IconRobot, IconSettings} from "@/components/ui/icons";
import {Fragment, memo, type ReactNode, useMemo, useRef, useState} from "react";
import {Collapsible, CollapsibleContent, CollapsibleTrigger} from "@/components/ui/shadcn/collapsible";
import executionStyles from "./conversation-execution.module.css";
import {ConversationThinkingBlock} from "./conversation-thinking-block";
import {ConversationToolBlock, ToolActivityIcon} from "./conversation-tool-block";
import {useExecutionDisclosure} from "../hooks/use-execution-disclosure";
import {
  groupBlockStatus,
  groupConversationBlocks,
  isSubagentTool,
  mergeRunApprovals,
  mergeToolConfirmations,
  pendingToolConfirmationIds,
  toolBlockStatus,
  toolStatusLabel
} from "../lib/conversation-display";
import {fileToolAction, fileToolPresentation, groupFileToolBlocks} from "../lib/conversation-file-tool";

export const blockStatus: Record<BlockStatus, string> = {
  pending: "等待执行",
  running: "处理中",
  waiting_approval: "等待确认",
  completed: "已完成",
  failed: "未完成",
  cancelled: "已停止",
  skipped: "已跳过"
};

export const ConversationMarkdown = memo(function ConversationMarkdown({content, animate = false}: {
  content: string;
  animate?: boolean
}) {
  return <MarkdownContent content={content} animate={animate} className={styles.markdown}/>;
});

type BlockContext = {
  enterprise: string;
  runId: string | null;
  onChanged?: () => void;
  liveApprovals?: ReadonlyMap<string, RunApproval>
};
type ResolvedContext = BlockContext & {
  approvals: ReadonlyMap<string, RunApproval>;
  approvalError: string;
  approvalsLoading: boolean;
  reloadApprovals: () => void;
  recordDecision: (approval: RunApproval) => void
};

function approvalVersions(blocks: DisplayBlock[]): string[] {
  return blocks.flatMap((block) => [...(block.kind === "approval" ? [`${block.approvalId}:${block.revision}`] : []), ...approvalVersions(block.blocks)]);
}

export function ConversationBlocks({blocks, ...context}: { blocks: DisplayBlock[] } & BlockContext) {
  const scope = `${context.enterprise}:${context.runId}`;
  const [decisions, setDecisions] = useState<{ scope: string; values: RunApproval[] }>({scope, values: []});
  const revision = approvalVersions(blocks).join("|");
  const query = useApiQuery<RunApproval[]>(revision && context.runId ? organizationPath(context.enterprise, `/runs/${encodeURIComponent(context.runId)}/approvals`) : null, revision);
  const approvals = mergeRunApprovals(mergeRunApprovals(context.liveApprovals, query.data ?? []), decisions.scope === scope ? decisions.values : []);
  return <RenderBlocks blocks={mergeToolConfirmations(blocks)} context={{
    ...context, approvals, approvalError: query.error, approvalsLoading: query.loading,
    recordDecision: (approval) => setDecisions((previous) => ({
      scope,
      values: [...(previous.scope === scope ? previous.values.filter((value) => value.id !== approval.id) : []), approval]
    })),
    reloadApprovals: () => {
      query.retry();
      context.onChanged?.();
    }
  }}/>;
}

function ExecutionBlock({title, status, icon, kind, children, pendingIds = [], defaultOpen = true}: {
  title: string;
  status: BlockStatus;
  icon: ReactNode;
  kind: string;
  children: ReactNode;
  pendingIds?: string[];
  defaultOpen?: boolean
}) {
  const uiText = useT();
  const {open, setOpen} = useExecutionDisclosure(status, pendingIds, defaultOpen);
  return <Collapsible className={executionStyles.block} data-kind={kind} open={open} onOpenChange={setOpen}>
    <CollapsibleTrigger className={executionStyles.executionTrigger}><span
      className={executionStyles.blockIcon}>{icon}</span><strong>{title}</strong><span className={executionStyles.state}
                                                                                       data-state={status}>{localizeCatalog(blockStatus, uiText)[status]}</span><IconChevronDown
      size={14}/></CollapsibleTrigger>
    <CollapsibleContent className="agenteam-disclosure-content" keepMounted={false}>
      <div className={executionStyles.body}>{children}</div>
    </CollapsibleContent>
  </Collapsible>;
}

function RenderBlocks({blocks, context}: { blocks: DisplayBlock[]; context: ResolvedContext }) {
  return <div className={executionStyles.flow}>{groupConversationBlocks(blocks).map((group) => {
    const content = group.blocks.map((block) => <RenderBlock key={block.id} block={block} context={context}/>);
    if (group.kind === "thinking") {
      const status = groupBlockStatus(group.blocks);
      return group.blocks.some(hasVisibleContent) ?
        <ConversationThinkingBlock key={group.id} status={status}>{content}</ConversationThinkingBlock> : null;
    }
    if (group.kind === "tool") {
      return <Fragment key={group.id}>{groupFileToolBlocks(group.blocks).map((files) => files.action
        ? <section key={files.id} className={executionStyles.fileToolGroup} data-file-action={files.action}
                   aria-label={fileToolAction(files.blocks[0])?.label}>
          {files.blocks.length > 1 &&
            <div className={executionStyles.fileToolGroupTitle}>{fileToolAction(files.blocks[0])?.label}</div>}
          <div className={executionStyles.fileToolGroupItems}
               data-multiple={files.blocks.length > 1 || undefined}>{files.blocks.map((block) => <RenderBlock
            key={block.id} block={block} context={context} grouped={files.blocks.length > 1}/>)}</div>
        </section> : <Fragment key={files.id}>{files.blocks.map((block) => <RenderBlock key={block.id} block={block}
                                                                                        context={context}/>)}</Fragment>)}</Fragment>;
    }
    return <Fragment key={group.id}>{content}</Fragment>;
  })}</div>;
}

function hasVisibleContent(block: DisplayBlock): boolean {
  if (("content" in block && block.content.trim()) || block.step?.publicSummary?.trim() || ("summary" in block && block.summary.trim())) {
    return true;
  }
  return block.kind !== "thinking" && block.kind !== "text" || block.blocks.some(hasVisibleContent);
}

function RenderBlock({block, context, grouped = false}: {
  block: DisplayBlock;
  context: ResolvedContext;
  grouped?: boolean
}) {
  const uiText = useT();
  const children = block.kind === "tool" ? block.blocks.filter((child) => child.kind !== "approval") : block.blocks;
  const nested = children.length > 0 ?
    <div className={executionStyles.children}><RenderBlocks blocks={children} context={context}/></div> : null;
  const summary = block.step?.publicSummary;
  const note = summary && (!("summary" in block) || summary !== block.summary) && (!("content" in block) || summary !== block.content) ?
    <div className={executionStyles.stepSummary}><ConversationMarkdown content={summary}/></div> : null;
  if (block.kind === "step") {
    return <div className={executionStyles.standaloneStep} key={block.id}>
      <div className={executionStyles.stepLine}><IconSettings size={15}/><strong>{block.label}</strong><span
        className={executionStyles.state}
        data-state={block.status}>{localizeCatalog(blockStatus, uiText)[block.status]}</span></div>
      {block.summary && <ConversationMarkdown content={block.summary}/>}{nested}
    </div>;
  }
  if (block.kind === "text" || block.kind === "thinking") {
    return <>{block.content &&
      <div className={block.kind === "thinking" ? executionStyles.thinking : styles.textBlock}><ConversationMarkdown
        content={block.content} animate={block.animate}/></div>}{note}{nested}</>;
  }
  if (block.kind === "summary") {
    return block.content || nested || note ?
      <ExecutionBlock key={block.id} title={block.label} status={block.status} icon={<IconBulb size={17}/>}
                      kind="summary">{block.content &&
        <ConversationMarkdown content={block.content} animate={block.animate}/>}{note}{nested}</ExecutionBlock> : null;
  }
  if (block.kind === "agent") {
    return <ExecutionBlock
      title={block.label === "子智能体" ? uiText("子智能体") : uiText("子智能体 · {0}", [block.label])}
      status={block.status} kind="agent"
      icon={block.agentIcon ? <ResourceAvatar icon={block.agentIcon} color={block.agentColor ?? undefined}
                                              size="small"/> : block.status === "running" ?
        <ToolActivityIcon status={block.status}/> : <IconRobot size={18}/>}
      pendingIds={pendingToolConfirmationIds(block.blocks, context.approvals)}>
      {block.blocks.length > 0 && <RenderBlocks blocks={block.blocks} context={context}/>}{block.summary &&
      <ConversationMarkdown content={block.summary}/>}{note}
    </ExecutionBlock>;
  }
  if (block.kind === "workflow") {
    return <ExecutionBlock key={block.id} title={block.label} status={block.status} kind={block.kind}
                           icon={<IconArrowsSplit size={18}/>}
                           pendingIds={pendingToolConfirmationIds(block.blocks, context.approvals)}>
      {nested}{block.summary && <ConversationMarkdown content={block.summary}/>}{note}
    </ExecutionBlock>;
  }
  if (block.kind === "tool") {
    const contentUrl = context.runId && block.stepId ? organizationPath(context.enterprise,
      `/runs/${encodeURIComponent(context.runId)}/steps/${encodeURIComponent(block.stepId)}/tool-content`) : null;
    if (isSubagentTool(block)) {
      const hasAgentOutput = block.blocks.some((child) => child.kind === "agent");
      const failure = block.status === "failed" && block.blocks.every((child) => !("status" in child) || child.status !== "failed") && block.result;
      if (hasAgentOutput) {
        return <><RenderBlocks blocks={block.blocks} context={context}/>{failure &&
          <div className={styles.statusError}><NativeToolReply block={block} url={contentUrl}/></div>}</>;
      }
      return <ExecutionBlock title={uiText("子智能体")} status={block.status} kind="agent"
                             defaultOpen={!block.result.startsWith('{"truncated":true')}
                             icon={block.status === "running" ? <ToolActivityIcon status={block.status}/> :
                               <IconRobot size={18}/>}>
        {block.blocks.length > 0 && <RenderBlocks blocks={block.blocks} context={context}/>}
        {block.summary && <ConversationMarkdown content={block.summary}/>}{note}
        {block.result && <NativeToolReply block={block} url={contentUrl}/>}
      </ExecutionBlock>;
    }
    const confirmations = block.blocks.filter((child): child is Extract<DisplayBlock, {
      kind: "approval"
    }> => child.kind === "approval");
    const status = toolBlockStatus(block, context.approvals);
    const summaries = [block.summary, summary].filter((text): text is string => Boolean(text)).filter((text, index, values) => text !== block.label && values.indexOf(text) === index
      && !confirmations.some((confirmation) => context.approvals.get(confirmation.approvalId)?.summary.description === text));
    const waitingForContent = (status === "pending" || status === "running") && !block.input && !block.result
      && summaries.length === 0 && confirmations.length === 0 && children.length === 0;
    const file = fileToolPresentation(block);
    const completed = status === "completed";
    return <ConversationToolBlock
      label={file ? uiText(completed ? file.completed : file.label) : localizeSavedToolLabel(block.label, uiText)}
      status={status} statusText={file && completed && !grouped ? "" : uiText(toolStatusLabel(block, status))}
      toolCallId={block.toolCallId} waitingForContent={waitingForContent}
      grouped={grouped}
      subject={file?.file && <ConversationFileLink file={file.file} available={completed}/>}
      preview={file?.image && file.file && completed &&
        <ConversationFileImage file={file.file} width={file.width} height={file.height} revision={file.revision}/>}
      notice={status === "failed" ? toolPayloadFailure(block.result) : null}
      pendingIds={pendingToolConfirmationIds([block], context.approvals)}>
      <ToolCallDetails input={confirmations.length ? "" : block.input} result={block.result}
                       inputSource={contentUrl ? {
                         url: contentUrl,
                         part: "input",
                         revision: block.callStatus
                       } : undefined}
                       resultSource={contentUrl ? {
                         url: contentUrl,
                         part: "result",
                         revision: block.resultStatus
                       } : undefined}>
        {summaries.map((text, index) => <ConversationMarkdown key={index} content={text}/>)}
        {confirmations.map((confirmation) => <Fragment key={confirmation.id}><ConfirmationBlock block={confirmation}
                                                                                                context={context}
                                                                                                compact
                                                                                                toolLabel={block.label}/>
          {confirmation.blocks.length > 0 &&
            <div className={executionStyles.children}><RenderBlocks blocks={confirmation.blocks} context={context}/>
            </div>}</Fragment>)}
        {nested}
      </ToolCallDetails>
    </ConversationToolBlock>;
  }
  if (block.kind === "approval") {
    return <><ConfirmationBlock block={block} context={context}/>{nested}</>;
  }
  if (block.kind === "file") {
    return <Fragment key={block.id}><ConversationFileAttachment enterpriseId={context.enterprise}
                                                                file={block.file}/>{nested}</Fragment>;
  }
  if (block.kind === "citation") {
    return <Fragment key={block.id}><CitationCard enterpriseId={context.enterprise} citation={block.citation}/>{nested}
    </Fragment>;
  }
  return <Fragment key={block.id}>
    <div className={`${styles.statusBlock} ${block.status === "failed" ? styles.statusError : ""}`}>{block.label}</div>
    {nested}</Fragment>;
}

function NativeToolReply({block, url}: { block: Extract<DisplayBlock, { kind: "tool" }>; url: string | null }) {
  const positionRef = useRef<ToolReadingPosition>({offset: 0, top: 0, left: 0});
  const source = useMemo(() => url ? {
    url,
    part: "result" as const,
    revision: block.resultStatus
  } : null, [url, block.resultStatus]);
  if (!source || !block.result.startsWith('{"truncated":true')) {
    return <ConversationMarkdown content={block.result}/>;
  }
  return <ToolContentBoundary><ToolContentVisibility>{(ready) => <ToolContentReader source={source} raw={false}
                                                                                    positionRef={positionRef}
                                                                                    onReady={ready}
                                                                                    render={(content) =>
                                                                                      <ConversationMarkdown
                                                                                        content={content}/>}/>}</ToolContentVisibility></ToolContentBoundary>;
}

function ConfirmationBlock({block, context, compact = false, toolLabel}: {
  block: Extract<DisplayBlock, { kind: "approval" }>;
  context: ResolvedContext;
  compact?: boolean;
  toolLabel?: string
}) {
  const uiText = useT();
  const approval = context.approvals.get(block.approvalId);
  return approval ?
    <RunApprovalCard enterprise={context.enterprise} approval={approval} onChanged={context.reloadApprovals}
                     onDecision={context.recordDecision} compact={compact} toolLabel={toolLabel}/>
    : <div className={executionStyles.approvalNotice}
           role={context.approvalError ? "alert" : "status"}>{context.approvalError || (context.approvalsLoading ? uiText("正在读取确认内容…") : uiText("暂时无法读取确认内容。"))}
      {!context.approvalsLoading &&
        <Button type="button" onClick={context.reloadApprovals}>{uiText("重新加载")}</Button>}</div>;
}
