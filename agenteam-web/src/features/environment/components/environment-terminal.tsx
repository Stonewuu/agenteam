"use client";

import {useEffect, useImperativeHandle, useRef, useState, type Ref} from "react";
import type {Terminal} from "@xterm/xterm";
import type {SearchAddon} from "@xterm/addon-search";
import {Button} from "@/components/ui/button";
import {Input} from "@/components/ui/input";
import {Dialog} from "@/components/ui/dialog";
import {IconArrowDown, IconArrowUp, IconCopy, IconMaximize, IconPlug, IconSearch, IconTerminal, IconTrash, IconX} from "@/components/ui/icons";
import {useT} from "@/lib/i18n/locale-provider";
import {errorMessage} from "@/lib/http/api-client";
import {TerminalConnection} from "../lib/terminal-connection";
import type {TerminalView} from "../types/environment";
import ui from "@/components/ui/surface.module.css";
import styles from "./environment-terminal.module.css";
import "@xterm/xterm/css/xterm.css";

export type EnvironmentTerminalHandle = {close: () => Promise<void>; openDirectory: (path: string) => void};

const searchDecorations = {matchBackground: "#506077", matchOverviewRuler: "#94c5ef",
  activeMatchBackground: "#8b6637", activeMatchColorOverviewRuler: "#e9cd89"};

export function EnvironmentTerminal({base, connected, enabled, connecting, expanded, onConnect, onExpand, onState, ref}: {
  base: string; connected: boolean; enabled: boolean; connecting: boolean; expanded: boolean;
  onConnect: () => void; onExpand: () => void; onState: (view: TerminalView) => void;
  ref?: Ref<EnvironmentTerminalHandle>;
}) {
  const t = useT();
  const host = useRef<HTMLDivElement>(null);
  const terminal = useRef<Terminal | null>(null);
  const connection = useRef<TerminalConnection | null>(null);
  const search = useRef<SearchAddon | null>(null);
  const stateCallback = useRef(onState);
  const [view, setView] = useState<TerminalView | null>(null);
  const [error, setError] = useState("");
  const [revision, setRevision] = useState(0);
  const [finding, setFinding] = useState(false);
  const [query, setQuery] = useState("");
  const [selected, setSelected] = useState(false);
  const [copied, setCopied] = useState(false);
  const [paste, setPaste] = useState<string | null>(null);
  const [results, setResults] = useState({resultIndex: -1, resultCount: 0});
  const ready = connected && view?.status === "running" && !error;

  useEffect(() => {
    stateCallback.current = onState;
  }, [onState]);

  useEffect(() => {
    if (!finding) {
      search.current?.clearDecorations();
    }
  }, [finding]);

  useImperativeHandle(ref, () => ({
    close: async () => {
      await connection.current?.close();
    },
    openDirectory: path => {
      if (!view?.busy && ready) {
        connection.current?.input("\x15cd -- '" + path.replaceAll("'", "'\\''") + "'\r");
        terminal.current?.focus();
      }
    },
  }), [ready, view?.busy]);

  useEffect(() => {
    if (!connected || !host.current) {
      return;
    }
    let disposed = false;
    let cleanup = () => {};
    const initialize = async () => {
      const [{Terminal: Xterm}, {FitAddon}, {SearchAddon: Search}] = await Promise.all([
        import("@xterm/xterm"), import("@xterm/addon-fit"), import("@xterm/addon-search"),
      ]);
      if (disposed || !host.current) {
        return;
      }
      setView(null);
      setError("");
      const term = new Xterm({
        cursorBlink: !matchMedia("(prefers-reduced-motion: reduce)").matches,
        cursorStyle: "bar", fontSize: 14, lineHeight: 1.45, scrollback: 5000,
        fontFamily: '"Cascadia Code", "SFMono-Regular", Consolas, "Noto Sans SC", "Microsoft YaHei", monospace',
        // 固定版本的搜索扩展通过装饰接口显示匹配计数和高亮。
        allowProposedApi: true, screenReaderMode: true, allowTransparency: false,
        theme: {background: "#171e29", foreground: "#e0e7ef", cursor: "#94c5ef", selectionBackground: "#405774",
          black: "#273344", brightBlack: "#8f9cac", red: "#f08b8d", green: "#a7d8a0", yellow: "#e9cd89", blue: "#8cb9e9",
          magenta: "#c2a7ed", cyan: "#91d4d0", white: "#e0e7ef", brightWhite: "#ffffff"},
      });
      const fit = new FitAddon();
      const finder = new Search();
      term.loadAddon(fit);
      term.loadAddon(finder);
      term.open(host.current);
      fit.fit();
      term.textarea?.setAttribute("aria-label", t("交互终端"));
      let writing = false;
      term.parser.registerOscHandler(777, value => {
        const match = /^agenteam-size;(\d+);(\d+)$/.exec(value);
        if (match) {
          term.resize(Math.min(400, Math.max(20, Number(match[1]))), Math.min(160, Math.max(5, Number(match[2]))));
        }
        return true;
      });
      const session = new TerminalConnection(base, Math.max(20, term.cols), Math.max(5, term.rows),
        (bytes, reset) => new Promise<void>(resolve => {
          if (disposed) {
            resolve();
            return;
          }
          if (reset) {
            term.reset();
          }
          if (bytes.length) {
            writing = true;
            term.write(bytes, () => {
              writing = false;
              fit.fit();
              resolve();
            });
          } else {
            resolve();
          }
        }), next => {
          if (!disposed) {
            setView(previous => previous?.status === next.status && previous.directory === next.directory
              && previous.busy === next.busy && previous.error === next.error ? previous : next);
            stateCallback.current(next);
          }
        }, failure => {
          if (!disposed) {
            setError(errorMessage(failure));
          }
        });
      terminal.current = term;
      connection.current = session;
      search.current = finder;
      term.onData(data => session.input(data));
      term.onResize(size => {
        if (!writing) {
          session.resize(size.cols, size.rows);
        }
      });
      term.onSelectionChange(() => setSelected(term.hasSelection()));
      finder.onDidChangeResults(setResults);
      // 终端输出不获得剪贴板权限，也不能自动打开任意链接。
      term.parser.registerOscHandler(52, () => true);
      term.attachCustomKeyEventHandler(event => {
        if (event.type === "keydown" && (event.ctrlKey || event.metaKey) && event.shiftKey && event.key.toLowerCase() === "f") {
          event.preventDefault();
          setFinding(true);
          return false;
        }
        return true;
      });
      const pasteHandler = (event: ClipboardEvent) => {
        const value = event.clipboardData?.getData("text/plain");
        if (value && /[\r\n]/.test(value)) {
          event.preventDefault();
          event.stopImmediatePropagation();
          setPaste(value);
        }
      };
      const element = host.current;
      element.addEventListener("paste", pasteHandler, true);
      const observer = new ResizeObserver(() => {
        if (element.clientWidth > 0 && element.clientHeight > 0) {
          fit.fit();
        }
      });
      observer.observe(element);
      cleanup = () => {
        observer.disconnect();
        element.removeEventListener("paste", pasteHandler, true);
        session.dispose();
        term.dispose();
        terminal.current = null;
        connection.current = null;
        search.current = null;
      };
      await session.open();
      if (!disposed) {
        term.focus();
      }
    };
    void initialize().catch(failure => {
      if (!disposed) {
        setError(errorMessage(failure));
      }
    });
    return () => {
      disposed = true;
      cleanup();
    };
  }, [base, connected, revision, t]);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(terminal.current?.getSelection() ?? "");
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      setError(t("无法复制，请选中文字后使用浏览器的复制操作。"));
    }
  };

  return <section className={styles.panel} aria-label={t("终端")}>
    <header className={styles.toolbar}>
      <div className={styles.title}><IconTerminal size={20}/><h2>{t("终端")}</h2>
        {ready && <span className={styles.sessionState}>{t(view?.busy ? "命令运行中" : "已连接")}</span>}
      </div>
      <div className={styles.actions}>
        <Button className={styles.tool} disabled={!ready} aria-pressed={finding} onClick={() => setFinding(value => !value)}><IconSearch size={16}/>{t("查找")}</Button>
        <Button className={styles.tool} disabled={!selected} onClick={() => void copy()}><IconCopy size={16}/>{t(copied ? "已复制" : "复制")}</Button>
        <Button className={styles.tool} disabled={!ready} onClick={() => terminal.current?.clear()}><IconTrash size={16}/>{t("清屏")}</Button>
        <Button className={styles.iconButton} title={t(expanded ? "显示文件浏览" : "展开终端")} aria-label={t(expanded ? "显示文件浏览" : "展开终端")} onClick={onExpand}><IconMaximize size={17}/></Button>
      </div>
    </header>
    {finding && <div className={styles.search}>
      <Input autoFocus aria-label={t("查找终端内容")} className={ui.input} placeholder={t("查找终端内容")} value={query}
        onChange={event => {
          setQuery(event.target.value);
          search.current?.findNext(event.target.value, {incremental: true, decorations: searchDecorations});
        }} onKeyDown={event => {
          if (event.key === "Escape") {
            setFinding(false);
            terminal.current?.focus();
          } else if (event.key === "Enter") {
            event.preventDefault();
            search.current?.[event.shiftKey ? "findPrevious" : "findNext"](query, {decorations: searchDecorations});
          }
        }}/>
      <span aria-live="polite">{query ? `${results.resultIndex + 1}/${results.resultCount}` : ""}</span>
      <Button className={styles.iconButton} aria-label={t("上一个匹配")} onClick={() => search.current?.findPrevious(query, {decorations: searchDecorations})}><IconArrowUp size={17}/></Button>
      <Button className={styles.iconButton} aria-label={t("下一个匹配")} onClick={() => search.current?.findNext(query, {decorations: searchDecorations})}><IconArrowDown size={17}/></Button>
      <Button className={styles.iconButton} aria-label={t("关闭查找")} onClick={() => setFinding(false)}><IconX size={17}/></Button>
    </div>}
    <div className={styles.screen}>
      <div className={styles.host} ref={host} hidden={!connected}/>
      {!connected && <div className={styles.empty}>
        <span className={styles.emptyIcon}><IconTerminal size={34}/></span>
        <h3>{t("你的工作，从这里开始")}</h3>
        <p>{t(enabled ? "连接环境，直接输入命令。目录切换和运行结果会保留在当前终端。" : "请联系管理员开启工作环境。")}</p>
        <Button className={`${ui.primary} ${styles.connect}`} disabled={!enabled || connecting} onClick={onConnect}><IconPlug size={17}/>{t(connecting ? "正在连接…" : "连接环境")}</Button>
        <div className={styles.shortcuts}><span><kbd>Tab</kbd>{t("补全")}</span><span><kbd><IconArrowUp size={11}/><IconArrowDown size={11}/></kbd>{t("历史命令")}</span><span><kbd>Ctrl C</kbd>{t("中断命令")}</span></div>
      </div>}
      {connected && (!view || error || view.status === "closed" || view.status === "error") && <div className={styles.connectionNotice} role="status">
        <span>{t(error || view?.error || (view ? "终端已断开" : "正在打开终端…"))}</span>
        {(error || view) && <Button className={styles.tool} onClick={() => setRevision(value => value + 1)}>{t("重新连接")}</Button>}
      </div>}
    </div>
    <footer className={styles.footer}><span title={view?.directory}>{view?.directory ?? "/workspace"}</span>
      {ready && <div><Button className={styles.keyButton} onClick={() => {
        connection.current?.input("\x03");
        terminal.current?.focus();
      }}>{t("中断")}<kbd>Ctrl C</kbd></Button><Button className={styles.keyButton} onClick={() => {
        connection.current?.input("\t");
        terminal.current?.focus();
      }}>{t("补全")}<kbd>Tab</kbd></Button></div>}
    </footer>
    {paste !== null && <Dialog title={t("粘贴多行内容？")} icon={<IconTerminal size={22}/>} onClose={() => setPaste(null)} footer={<>
      <Button className={ui.button} onClick={() => setPaste(null)}>{t("取消")}</Button>
      <Button className={ui.primary} onClick={() => {
        terminal.current?.paste(paste);
        setPaste(null);
        terminal.current?.focus();
      }}>{t("粘贴到终端")}</Button>
    </>}><p>{t("多行内容可能包含会立即执行的命令，请确认内容。")}</p><pre className={styles.pastePreview}>{paste}</pre></Dialog>}
  </section>;
}
