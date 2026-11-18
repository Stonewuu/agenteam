"use client";

import {useEffect, useState} from "react";
import {Button} from "@/components/ui/button";
import {Input} from "@/components/ui/input";
import {Select} from "@/components/ui/select";
import {QueryState} from "@/components/ui/query-state";
import {toast} from "@/components/ui/toast";
import {IconArrowLeft, IconArrowRight, IconArrowUp, IconChevronRight, IconCopy, IconDatabase, IconFileText,
  IconFolder, IconLayoutGrid, IconList, IconPlus, IconRefresh, IconSearch, IconTerminal, IconUpload} from "@/components/ui/icons";
import {useApiQuery} from "@/lib/http/use-api-query";
import {useT} from "@/lib/i18n/locale-provider";
import type {EnvironmentDirectory, EnvironmentFile, EnvironmentUsage} from "../types/environment";
import {formatFileSize} from "../lib/environment-file-format";
import {EnvironmentFileDialog, type FileOperation} from "./environment-file-dialog";
import {EnvironmentFileItem} from "./environment-file-item";
import {EnvironmentUploadDialog} from "./environment-upload-dialog";
import {useEnvironmentFileDrop} from "../hooks/use-environment-file-drop";
import {EnvironmentFilePreview} from "./environment-file-preview";
import ui from "@/components/ui/surface.module.css";
import styles from "./environment-files.module.css";

export function EnvironmentFiles({base, version, initialPath, terminalDirectory, terminalReady, terminalBusy, onTerminalDirectory}: {
  base: string; version: number; initialPath: string; terminalDirectory?: string; terminalReady: boolean; terminalBusy: boolean;
  onTerminalDirectory: (path: string) => void;
}) {
  const t = useT();
  const [history, setHistory] = useState({paths: [initialPath], index: 0});
  const path = history.paths[history.index];
  const [query, setQuery] = useState("");
  const [search, setSearch] = useState("");
  const [sort, setSort] = useState("name");
  const [mode, setMode] = useState<"list" | "grid">("list");
  const [offset, setOffset] = useState(0);
  const [refresh, setRefresh] = useState(0);
  const [refreshAfter, setRefreshAfter] = useState("");
  const [preview, setPreview] = useState<EnvironmentFile | null>(null);
  const [selectedPath, setSelectedPath] = useState("");
  const [operation, setOperation] = useState<FileOperation | null>(null);
  const drop = useEnvironmentFileDrop(!operation && !preview, sources => setOperation({kind: "upload", path, sources}));
  const revision = version + ":" + refresh;
  const usage = useApiQuery<EnvironmentUsage>(base + "/files/usage" + (refreshAfter ? "?" + new URLSearchParams({after: refreshAfter}) : ""),
    revision + ":" + path, 0, false, {revalidateOnFocus: true});
  const listing = useApiQuery<EnvironmentDirectory>(base + "/files?" + new URLSearchParams({path, offset: String(offset), query: search, sort}),
    revision + ":" + (usage.data?.sampleId ?? ""), 0, false, {revalidateOnFocus: true});
  const current = listing.data;
  const selected = current?.entries.find(file => file.path === selectedPath);
  const reload = () => setRefresh(value => value + 1);

  function resetNavigation() {
    setOffset(0);
    setSelectedPath("");
    setQuery("");
    setSearch("");
  }

  function changeDirectory(next: string) {
    if (next !== path) {
      setHistory(value => ({paths: [...value.paths.slice(0, value.index + 1), next], index: value.index + 1}));
      resetNavigation();
    }
  }

  function back(step: number) {
    setHistory(value => ({...value, index: Math.min(value.paths.length - 1, Math.max(0, value.index + step))}));
    resetNavigation();
  }

  function up() {
    if (path !== "/workspace") {
      changeDirectory(path.slice(0, path.lastIndexOf("/")));
    }
  }

  async function copy(value: string) {
    try {
      await navigator.clipboard.writeText(value);
      toast.success(t("路径已复制"));
    } catch {
      toast.error(t("无法复制，请选中文字后使用浏览器的复制操作。"));
    }
  }

  useEffect(() => {
    const timer = setTimeout(() => {
      setSearch(query);
      setOffset(0);
    }, 220);
    return () => clearTimeout(timer);
  }, [query]);

  const parts = path.split("/").filter(Boolean);
  const canLocate = terminalDirectory && (terminalDirectory === "/workspace" || terminalDirectory.startsWith("/workspace/"));
  return <section className={styles.panel} aria-label={t("文件浏览")} {...drop.handlers}>
    {drop.dragging && <div className={styles.dropOverlay} role="status"><div>
      <IconUpload size={29}/><strong>{t("松开后确认上传")}</strong><span>{path}</span>
    </div></div>}
    <header className={styles.heading}>
      <div className={styles.headingTitle}><IconFolder size={20}/><h2>{t("文件浏览")}</h2></div>
      <div className={styles.headingActions}>
        <Button className={styles.createButton} title={t("新建文件夹")} aria-label={t("新建文件夹")}
          onClick={() => setOperation({kind: "folder", path})}><IconPlus size={16}/><span>{t("新建文件夹")}</span></Button>
        <Button className={styles.uploadButton} title={t("上传文件")} aria-label={t("上传文件")} onClick={() => setOperation({kind: "upload", path})}><IconUpload size={16}/><span>{t("上传")}</span></Button>
        <Button className={styles.iconButton} title={t("刷新文件")} aria-label={t("刷新文件")} onClick={() => {
          setRefreshAfter(usage.data?.sampleId ?? "");
          reload();
        }}><IconRefresh size={17}/></Button>
      </div>
    </header>
    <div className={styles.usage} aria-label={t("环境用量")}>
      <span><IconFileText size={15}/>{t("全部文件")}<strong>{usage.error ? "—" : usage.data?.fileCount.toLocaleString() ?? "—"}</strong></span>
      <span><IconDatabase size={15}/>{t("占用空间")}<strong>{usage.data && !usage.error ? formatFileSize(usage.data.totalBytes) : "—"}</strong></span>
      {usage.error && <Button className={styles.retry} title={usage.error} onClick={usage.retry}>{t("重新统计")}</Button>}
    </div>
    <div className={styles.navigation}>
      <Button className={styles.iconButton} disabled={history.index === 0} title={t("后退")} aria-label={t("后退")} onClick={() => back(-1)}><IconArrowLeft size={17}/></Button>
      <Button className={styles.iconButton} disabled={history.index === history.paths.length - 1} title={t("前进")} aria-label={t("前进")} onClick={() => back(1)}><IconArrowRight size={17}/></Button>
      <Button className={styles.iconButton} disabled={path === "/workspace"} title={t("上级目录")} aria-label={t("上级目录")} onClick={up}><IconArrowUp size={17}/></Button>
      <nav className={styles.breadcrumb} aria-label={t("文件路径")}>{parts.map((part, i) => <span key={i}>
        {i > 0 && <IconChevronRight size={12}/>}<Button className={styles.crumb} title={part} aria-current={i === parts.length - 1 ? "location" : undefined}
          onClick={() => changeDirectory("/" + parts.slice(0, i + 1).join("/"))}>{i === 0 ? t("工作文件") : part}</Button>
      </span>)}</nav>
    </div>
    <div className={styles.filter}>
      <div className={styles.search}><IconSearch size={16}/><Input className={ui.input} aria-label={t("搜索当前目录")} placeholder={t("搜索")}
        value={query} onChange={event => setQuery(event.target.value)}/></div>
      <div className={styles.viewControls}>
        <Select className={styles.sort} aria-label={t("文件排序")} value={sort} onChange={event => {
          setSort(event.target.value);
          setOffset(0);
        }}><option value="name">{t("名称排序")}</option><option value="modified">{t("最近修改")}</option><option value="size">{t("大小排序")}</option></Select>
        <div className={styles.toggle} role="group" aria-label={t("文件视图")}>
          <Button className={styles.iconButton} title={t("列表视图")} aria-label={t("列表视图")} aria-pressed={mode === "list"} onClick={() => setMode("list")}><IconList size={16}/></Button>
          <Button className={styles.iconButton} title={t("网格视图")} aria-label={t("网格视图")} aria-pressed={mode === "grid"} onClick={() => setMode("grid")}><IconLayoutGrid size={16}/></Button>
        </div>
      </div>
    </div>
    <div className={styles.files}>
      <QueryState loading={listing.loading} error={listing.error} empty="" hasData={current !== null} retry={listing.retry}>
        {current?.entries.length ? <>
          {mode === "list" && <div className={styles.columnTitles}><span>{t("名称")}</span><span>{t("大小")}</span><span>{t("操作")}</span></div>}
          <ul className={mode === "grid" ? styles.grid : styles.list} aria-label={t("环境文件")}>
            {current.entries.map(file => <EnvironmentFileItem key={file.path} base={base} file={file} grid={mode === "grid"}
              selected={selected?.path === file.path} terminalReady={terminalReady && !terminalBusy}
              onSelect={() => setSelectedPath(file.path)} onParent={up} onCopy={() => void copy(file.path)}
              onOpen={() => file.directory ? changeDirectory(file.path) : setPreview(file)}
              onTerminal={() => onTerminalDirectory(file.path)} onOperation={setOperation}/>)}
          </ul>
        </> : <div className={styles.empty}><IconFolder size={36} variant="Bulk"/><strong>{t(search ? "没有找到匹配的文件" : "此目录暂无文件")}</strong>
          {search ? <Button className={ui.button} onClick={() => setQuery("")}>{t("清空搜索")}</Button>
            : <Button className={ui.button} onClick={() => setOperation({kind: "upload", path})}><IconUpload size={16}/>{t("上传文件")}</Button>}
        </div>}
        {(offset > 0 || current?.nextOffset != null) && <div className={styles.pagination}>
          <Button className={styles.smallButton} disabled={!offset} onClick={() => setOffset(value => Math.max(0, value - 100))}>{t("上一页")}</Button>
          <span>{Math.floor(offset / 100) + 1}</span>
          <Button className={styles.smallButton} disabled={current?.nextOffset == null} onClick={() => setOffset(current?.nextOffset ?? 0)}>{t("下一页")}</Button>
        </div>}
      </QueryState>
    </div>
    <footer className={styles.folderActions}>
      <span className={styles.count}>{current ? t(search ? "找到 {0} 项" : "当前目录 {0} 项", [current.total]) : "—"}</span>
      <Button className={styles.smallButton} disabled={!terminalReady || terminalBusy} title={terminalBusy ? t("请等待终端命令结束") : path}
        onClick={() => onTerminalDirectory(path)}><IconTerminal size={15}/>{t("在终端打开")}</Button>
      <Button className={styles.iconButton} title={t("定位终端目录")} aria-label={t("定位终端目录")} disabled={!canLocate}
        onClick={() => {
          if (canLocate && terminalDirectory) {
            changeDirectory(terminalDirectory);
          }
        }}><IconFolder size={17}/></Button>
      <Button className={styles.iconButton} title={t("复制路径")} aria-label={t("复制路径")} onClick={() => void copy(selected?.path ?? path)}><IconCopy size={17}/></Button>
    </footer>
    {preview && <EnvironmentFilePreview key={preview.path} base={base} file={preview} onClose={() => setPreview(null)}/>}
    {operation?.kind === "upload" && <EnvironmentUploadDialog base={base} path={operation.path} initialSources={operation.sources}
      onClose={() => setOperation(null)} onChanged={reload}/>}
    {operation && operation.kind !== "upload" && <EnvironmentFileDialog base={base} operation={operation} onClose={() => setOperation(null)} onChanged={() => {
      if (operation.kind === "delete" && current?.entries.length === 1 && offset > 0) {
        setOffset(value => Math.max(0, value - 100));
      }
      reload();
    }}/>}
  </section>;
}
