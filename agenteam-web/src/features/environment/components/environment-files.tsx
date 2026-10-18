"use client";

import {useEffect, useState} from "react";
import {Button} from "@/components/ui/button";
import {Input} from "@/components/ui/input";
import {Select} from "@/components/ui/select";
import {QueryState} from "@/components/ui/query-state";
import {IconArchive, IconArrowLeft, IconArrowRight, IconArrowUp, IconChevronRight, IconCopy, IconDownload, IconFileText, IconFolder, IconImage, IconLayoutGrid, IconListDetails, IconRefresh, IconSearch, IconTerminal} from "@/components/ui/icons";
import {apiRequest, errorMessage} from "@/lib/http/api-client";
import {useT} from "@/lib/i18n/locale-provider";
import type {EnvironmentDirectory, EnvironmentFile} from "../types/environment";
import {formatFileSize, fileKind} from "../lib/environment-file-format";
import {EnvironmentFilePreview} from "./environment-file-preview";
import ui from "@/components/ui/surface.module.css";
import styles from "./environment-files.module.css";

function FileIcon({file, size = 22}: {file: EnvironmentFile; size?: number}) {
  const kind = fileKind(file.name);
  const Icon = file.directory ? IconFolder : kind === "image" ? IconImage : kind === "code" ? IconTerminal : kind === "archive" ? IconArchive : IconFileText;
  return <Icon size={size} variant="Bulk"/>;
}

export function EnvironmentFiles({base, version, terminalDirectory, terminalReady, terminalBusy, onTerminalDirectory}: {
  base: string; version: number; terminalDirectory?: string; terminalReady: boolean; terminalBusy: boolean;
  onTerminalDirectory: (path: string) => void;
}) {
  const t = useT();
  const [history, setHistory] = useState({paths: ["/workspace"], index: 0});
  const path = history.paths[history.index];
  const [query, setQuery] = useState("");
  const [search, setSearch] = useState("");
  const [sort, setSort] = useState("name");
  const [mode, setMode] = useState<"list" | "grid">("list");
  const [offset, setOffset] = useState(0);
  const [directory, setDirectory] = useState<EnvironmentDirectory | null>(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  const [refresh, setRefresh] = useState(0);
  const [preview, setPreview] = useState<EnvironmentFile | null>(null);
  const [selected, setSelected] = useState<EnvironmentFile | null>(null);
  const [copied, setCopied] = useState(false);
  const current = directory?.path === path ? directory : null;
  const changeDirectory = (next: string) => {
    if (next !== path) {
      setHistory(value => ({paths: [...value.paths.slice(0, value.index + 1), next], index: value.index + 1}));
      setOffset(0);
      setSelected(null);
      setQuery("");
      setSearch("");
    }
  };
  const back = (step: number) => {
    setHistory(value => ({...value, index: Math.min(value.paths.length - 1, Math.max(0, value.index + step))}));
    setOffset(0);
    setSelected(null);
    setQuery("");
    setSearch("");
  };

  useEffect(() => {
    const timer = setTimeout(() => {
      setSearch(query);
      setOffset(0);
    }, 220);
    return () => clearTimeout(timer);
  }, [query]);

  useEffect(() => {
    const controller = new AbortController();
    void apiRequest<EnvironmentDirectory>(base + "/files?" + new URLSearchParams({path, offset: String(offset), query: search, sort}), {signal: controller.signal})
      .then(data => {
        setDirectory(data);
        setError("");
      }).catch(failure => {
        if (!controller.signal.aborted) {
          setError(errorMessage(failure));
        }
      }).finally(() => {
        if (!controller.signal.aborted) {
          setLoading(false);
        }
      });
    return () => controller.abort();
  }, [base, path, offset, search, sort, refresh, version]);

  const parts = path.split("/").filter(Boolean);
  const canLocate = terminalDirectory && (terminalDirectory === "/workspace" || terminalDirectory.startsWith("/workspace/"));
  return <section className={styles.panel} aria-label={t("文件浏览")}>
    <header className={styles.heading}><div><IconFolder size={20}/><h2>{t("文件浏览")}</h2></div>
      <Button className={styles.iconButton} title={t("刷新文件")} aria-label={t("刷新文件")} onClick={() => setRefresh(value => value + 1)}><IconRefresh size={17}/></Button>
    </header>
    <div className={styles.navigation}>
      <Button className={styles.iconButton} disabled={history.index === 0} title={t("后退")} aria-label={t("后退")} onClick={() => back(-1)}><IconArrowLeft size={17}/></Button>
      <Button className={styles.iconButton} disabled={history.index === history.paths.length - 1} title={t("前进")} aria-label={t("前进")} onClick={() => back(1)}><IconArrowRight size={17}/></Button>
      <Button className={styles.iconButton} disabled={path === "/workspace"} title={t("上级目录")} aria-label={t("上级目录")} onClick={() => changeDirectory(path.slice(0, path.lastIndexOf("/")))}><IconArrowUp size={17}/></Button>
      <nav className={styles.breadcrumb} aria-label={t("文件路径")}>{parts.map((part, i) => <span key={i}>
        {i > 0 && <IconChevronRight size={12}/>}<Button className={styles.crumb} title={part} aria-current={i === parts.length - 1 ? "location" : undefined}
          onClick={() => changeDirectory("/" + parts.slice(0, i + 1).join("/"))}>{i === 0 ? t("工作文件") : part}</Button>
      </span>)}</nav>
    </div>
    <div className={styles.filter}>
      <div className={styles.search}><IconSearch size={16}/><Input className={ui.input} aria-label={t("搜索当前目录")} placeholder={t("搜索当前目录")}
        value={query} onChange={event => setQuery(event.target.value)}/></div>
      <div className={styles.viewControls}>
        <Select className={styles.sort} aria-label={t("文件排序")} value={sort} onChange={event => {
          setSort(event.target.value);
          setOffset(0);
        }}><option value="name">{t("名称排序")}</option><option value="modified">{t("最近修改")}</option><option value="size">{t("大小排序")}</option></Select>
        <div className={styles.toggle} role="group" aria-label={t("文件视图")}>
          <Button className={styles.iconButton} title={t("列表视图")} aria-label={t("列表视图")} aria-pressed={mode === "list"} onClick={() => setMode("list")}><IconListDetails size={16}/></Button>
          <Button className={styles.iconButton} title={t("网格视图")} aria-label={t("网格视图")} aria-pressed={mode === "grid"} onClick={() => setMode("grid")}><IconLayoutGrid size={16}/></Button>
        </div>
      </div>
    </div>
    <div className={styles.files}>
      <QueryState loading={loading || !current && !error} error={error} empty="" hasData={current !== null} retry={() => setRefresh(value => value + 1)}
        emptyContent={<div className={styles.empty}><IconFolder size={36}/><p>{t("此目录暂无文件")}</p></div>}>
        {current?.entries.length ? <>
          {mode === "list" && <div className={styles.columnTitles}><span>{t("名称")}</span><span>{t("大小")}</span></div>}
          <ul className={mode === "grid" ? styles.grid : styles.list} aria-label={t("环境文件")}>
            {current.entries.map(file => <li key={file.path}>
              <Button className={styles.file} data-selected={selected?.path === file.path || undefined}
                onFocus={() => setSelected(file)}
                onClick={() => {
                  setSelected(file);
                  if (file.directory) {
                    changeDirectory(file.path);
                  } else {
                    setPreview(file);
                  }
                }} onKeyDown={event => {
                  if (event.key === "ArrowDown" || event.key === "ArrowUp") {
                    event.preventDefault();
                    const row = event.currentTarget.closest("li");
                    const sibling = event.key === "ArrowDown" ? row?.nextElementSibling : row?.previousElementSibling;
                    sibling?.querySelector<HTMLButtonElement>("button")?.focus();
                  } else if (event.key === "Backspace" && path !== "/workspace") {
                    event.preventDefault();
                    changeDirectory(path.slice(0, path.lastIndexOf("/")));
                  }
                }}>
                <span className={styles.fileIcon} data-icon-surface><FileIcon file={file} size={mode === "grid" ? 29 : 21}/></span>
                <span className={styles.fileName}><strong title={file.name}>{file.name}</strong><small>{file.directory ? t("文件夹") : new Date(file.modifiedAt).toLocaleDateString()}</small></span>
                <span className={styles.fileSize}>{file.directory ? <IconChevronRight size={14}/> : formatFileSize(file.size)}</span>
              </Button>
            </li>)}
          </ul>
        </> : <div className={styles.empty}><IconFolder size={36} variant="Bulk"/><strong>{t(search ? "没有找到匹配的文件" : "此目录暂无文件")}</strong>
          <p>{t(search ? "试试其他名称，或清空搜索。" : "在终端中创建文件后，可在这里查看。")}</p>
          {search && <Button className={ui.button} onClick={() => setQuery("")}>{t("清空搜索")}</Button>}
        </div>}
        {(offset > 0 || current?.nextOffset !== null && current?.nextOffset !== undefined) && <div className={styles.pagination}>
          <Button className={styles.smallButton} disabled={!offset} onClick={() => setOffset(value => Math.max(0, value - 100))}>{t("上一页")}</Button>
          <span>{Math.floor(offset / 100) + 1}</span>
          <Button className={styles.smallButton} disabled={current?.nextOffset == null} onClick={() => setOffset(current?.nextOffset ?? 0)}>{t("下一页")}</Button>
        </div>}
      </QueryState>
    </div>
    <div className={styles.folderActions}>
      <Button className={styles.smallButton} disabled={!terminalReady || terminalBusy} title={terminalBusy ? t("请等待终端命令结束") : path}
        onClick={() => onTerminalDirectory(path)}><IconTerminal size={15}/>{t("在终端打开")}</Button>
      <Button className={styles.iconButton} title={t("定位终端目录")} aria-label={t("定位终端目录")} disabled={!canLocate}
        onClick={() => {
          if (canLocate && terminalDirectory) {
            changeDirectory(terminalDirectory);
          }
        }}><IconFolder size={17}/></Button>
      <Button className={styles.iconButton} title={t(copied ? "已复制" : "复制路径")} aria-label={t(copied ? "已复制" : "复制路径")} onClick={async () => {
        try {
          await navigator.clipboard.writeText(selected?.path ?? path);
          setCopied(true);
          setTimeout(() => setCopied(false), 1500);
        } catch {
          setError(t("无法复制，请选中文字后使用浏览器的复制操作。"));
        }
      }}><IconCopy size={17}/></Button>
      {selected && !selected.directory && <a className={styles.iconButton} href={base + "/files/content?" + new URLSearchParams({path: selected.path})}
        download={selected.name} title={t("下载文件")} aria-label={t("下载文件")}><IconDownload size={17}/></a>}
    </div>
    <footer className={styles.footer}><span>{current ? t("{0} 个项目", [current.total]) : t("正在读取…")}</span><span>{t("工作文件会保留")}</span></footer>
    {preview && <EnvironmentFilePreview key={preview.path} base={base} file={preview} onClose={() => setPreview(null)}/>}
  </section>;
}
