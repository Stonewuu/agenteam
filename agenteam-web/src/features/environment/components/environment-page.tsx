"use client";

import {useCallback, useEffect, useRef, useState, type CSSProperties} from "react";
import {EnterpriseGate} from "@/features/auth/components/enterprise-gate";
import {PlatformShell} from "@/features/workspace/components/platform-shell";
import {Button} from "@/components/ui/button";
import {PageHeader} from "@/components/ui/page-header";
import {QueryState} from "@/components/ui/query-state";
import {DetailStatus} from "@/components/ui/detail-section";
import {Dialog} from "@/components/ui/dialog";
import {IconClock, IconDeviceDesktop, IconFolder, IconLogout, IconPlayerStop, IconPlug, IconRefresh, IconTerminal} from "@/components/ui/icons";
import {useT} from "@/lib/i18n/locale-provider";
import {errorMessage} from "@/lib/http/api-client";
import {useEnvironment} from "../hooks/use-environment";
import type {TerminalView} from "../types/environment";
import {EnvironmentFiles} from "./environment-files";
import {EnvironmentTerminal, type EnvironmentTerminalHandle} from "./environment-terminal";
import ui from "@/components/ui/surface.module.css";
import styles from "./environment-page.module.css";

export function EnvironmentPage({enterpriseId}: {enterpriseId: string}) {
  const t = useT();
  return <EnterpriseGate enterpriseId={enterpriseId} permission="agent.run">{({user, context}) =>
    <PlatformShell user={user} context={context} area="user" title={t("我的环境")}>
      <EnvironmentContent key={enterpriseId + ":" + user.id} enterpriseId={enterpriseId}/>
    </PlatformShell>
  }</EnterpriseGate>;
}

function EnvironmentContent({enterpriseId}: {enterpriseId: string}) {
  const t = useT();
  const model = useEnvironment(enterpriseId);
  const terminal = useRef<EnvironmentTerminalHandle>(null);
  const board = useRef<HTMLDivElement>(null);
  const [terminalState, setTerminalState] = useState<TerminalView | null>(null);
  const [filesVersion, setFilesVersion] = useState(0);
  const previousBusy = useRef(false);
  const previousOutput = useRef({id: "", end: 0});
  const fileRefreshTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const [expanded, setExpanded] = useState(false);
  const [mobile, setMobile] = useState<"terminal" | "files">("terminal");
  const [filesWidth, setFilesWidth] = useState(36);
  const [confirm, setConfirm] = useState<"stop" | "disconnect" | null>(null);
  const [closing, setClosing] = useState(false);
  const [error, setError] = useState("");
  const state = model.environment;
  const pending = Boolean(model.pending) || closing;
  const ready = model.connected && terminalState?.status === "running";
  const onTerminalState = useCallback((next: TerminalView) => {
    setTerminalState(previous => previous?.status === next.status && previous.directory === next.directory && previous.busy === next.busy
      ? previous : next);
    if (previousBusy.current && !next.busy || next.status === "closed") {
      setFilesVersion(value => value + 1);
    }
    if (previousOutput.current.id !== next.id) {
      setFilesVersion(value => value + 1);
    } else if (previousOutput.current.end !== next.end && !next.busy) {
      if (fileRefreshTimer.current) {
        clearTimeout(fileRefreshTimer.current);
      }
      fileRefreshTimer.current = setTimeout(() => setFilesVersion(value => value + 1), 800);
    }
    previousOutput.current = {id: next.id, end: next.end};
    previousBusy.current = next.busy;
  }, []);

  useEffect(() => () => {
    if (fileRefreshTimer.current) {
      clearTimeout(fileRefreshTimer.current);
    }
  }, []);

  const reload = model.reload;
  useEffect(() => {
    void reload();
  }, [terminalState?.busy, terminalState?.status, reload]);

  const finish = async () => {
    setClosing(true);
    setError("");
    try {
      await terminal.current?.close();
      if (confirm === "stop") {
        if (!await model.stop()) {
          return;
        }
      } else {
        model.disconnect();
      }
      setConfirm(null);
      setFilesVersion(value => value + 1);
    } catch (failure) {
      setError(errorMessage(failure));
    } finally {
      setClosing(false);
    }
  };

  return <div className={styles.page} onPointerDown={model.activity} onKeyDown={model.activity} onWheel={model.activity}>
    <PageHeader title={t("我的环境")} icon={<IconDeviceDesktop size={28} variant="Bulk"/>}
      description={t("在当前工作环境中运行命令、查看和下载文件。")} inlineMetadata
      metadata={state && <DetailStatus tone={state.status === "running" ? "success" : "neutral"}>{t(state.status === "running" ? "运行中" : state.status === "stopping" ? "正在停止" : "已停止")}</DetailStatus>}
      actions={<>
        <Button className={ui.button} disabled={pending} onClick={() => {
          void model.reload();
          setFilesVersion(value => value + 1);
        }}><IconRefresh size={16}/>{t("刷新")}</Button>
        {model.connected ? <Button className={ui.button} disabled={pending} onClick={() => setConfirm("disconnect")}><IconLogout size={16}/>{t("断开连接")}</Button>
          : <Button className={ui.primary} disabled={pending || !state?.enabled || state.status === "stopping"} onClick={() => void model.connect()}><IconPlug size={16}/>{t(model.pending === "connect" ? "正在连接…" : "连接环境")}</Button>}
        {state?.status === "running" && <Button className={ui.button} disabled={pending || state.busy || terminalState?.busy && model.connected}
          title={state.busy ? t("请先结束运行中的任务或命令") : undefined} onClick={() => setConfirm("stop")}><IconPlayerStop size={16}/>{t("停止环境")}</Button>}
      </>}/>
    <QueryState loading={!model.loaded} error={model.error} empty="" hasData={state !== null} retry={() => void model.reload()}>
      <div className={styles.workbench} ref={board} data-expanded={expanded || undefined} data-mobile={mobile}
        style={{"--files-width": filesWidth + "%"} as CSSProperties}>
        <div className={styles.mobileTabs} role="tablist" aria-label={t("环境工作区")}>
          <Button role="tab" aria-selected={mobile === "terminal"} onClick={() => setMobile("terminal")}><IconTerminal size={18}/>{t("终端")}</Button>
          <Button role="tab" aria-selected={mobile === "files"} onClick={() => setMobile("files")}><IconFolder size={18}/>{t("文件浏览")}</Button>
        </div>
        <div className={styles.filesPane}>
          <EnvironmentFiles base={model.base} version={filesVersion} terminalDirectory={terminalState?.directory}
            terminalReady={ready} terminalBusy={Boolean(terminalState?.busy)} onTerminalDirectory={path => {
              terminal.current?.openDirectory(path);
              setMobile("terminal");
            }}/>
        </div>
        <div className={styles.divider} role="separator" aria-label={t("调整文件区域宽度")} aria-orientation="vertical"
          aria-valuenow={Math.round(filesWidth)} aria-valuemin={26} aria-valuemax={52} tabIndex={0}
          onKeyDown={event => {
            if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
              event.preventDefault();
              setFilesWidth(value => Math.max(26, Math.min(52, value + (event.key === "ArrowLeft" ? -2 : 2))));
            }
          }} onPointerDown={event => {
            event.currentTarget.setPointerCapture(event.pointerId);
          }} onPointerMove={event => {
            if (!event.currentTarget.hasPointerCapture(event.pointerId) || !board.current) {
              return;
            }
            const rect = board.current.getBoundingClientRect();
            setFilesWidth(Math.max(26, Math.min(52, (event.clientX - rect.left) / rect.width * 100)));
          }} onPointerUp={event => event.currentTarget.releasePointerCapture(event.pointerId)}/>
        <div className={styles.terminalPane}>
          <EnvironmentTerminal ref={terminal} base={model.base} connected={model.connected} enabled={Boolean(state?.enabled)}
            connecting={model.pending === "connect"} expanded={expanded} onConnect={() => void model.connect()}
            onExpand={() => setExpanded(value => !value)} onState={onTerminalState}/>
        </div>
      </div>
      <p className={styles.idleHint}><IconClock size={15}/>{t("空闲 {0} 分钟后自动停止，工作文件保留。", [Math.ceil((state?.idleSeconds ?? 1800) / 60)])}</p>
    </QueryState>
    {confirm && <Dialog title={t(confirm === "stop" ? "停止环境？" : "断开连接？")} icon={<IconPlayerStop size={21}/>}
      size="small" busy={pending} onClose={() => setConfirm(null)} footer={<>
        <Button className={ui.button} disabled={pending} onClick={() => setConfirm(null)}>{t("取消")}</Button>
        <Button className={ui.primary} disabled={pending} onClick={() => void finish()}>{t(closing ? confirm === "stop" ? "正在停止…" : "正在断开…" : confirm === "stop" ? "停止环境" : "断开连接")}</Button>
      </>}>
      <p>{t(confirm === "stop" ? "工作文件会保留，下次连接时可以继续使用。" : "终端及其中的命令将关闭，工作文件保留。智能体任务会继续运行。")}</p>
      {(error || model.error) && <p className={ui.error} role="alert">{t(error || model.error)}</p>}
    </Dialog>}
  </div>;
}
