"use client";

import {useEffect, useRef, useState} from "react";
import {useT} from "@/lib/i18n/locale-provider";
import {localizeUiMessage} from "@/lib/i18n/ui-message";
import {localizeCatalog} from "@/lib/i18n/translate";
import {Button} from "@/components/ui/button";
import {Input} from "@/components/ui/input";
import {Popover, PopoverContent, PopoverTitle, PopoverTrigger} from "@/components/ui/shadcn/popover";
import {IconArrowLeft, IconArrowRight, IconCheck, IconCopy, IconLayoutGrid, IconPlus, IconStack, IconTrash, IconX} from "@/components/ui/icons";
import {Dialog, DialogActions} from "@/components/ui/dialog";
import {ApiMutation, errorMessage} from "@/lib/http/api-client";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import type {ResourceSummary, WorkflowConfig} from "@/features/resource/types/resource";
import type {NodeType, WorkflowGraph, WorkflowNode, WorkflowValidation} from "../types/workflow";
import {graphNodeCount, nodeNames} from "../lib/workflow-graph";
import {commandFitsLimits, executeWorkflowCommand, type ConnectionOrigin, type WorkflowCommand} from "../lib/workflow-commands";
import {arrangeWorkflow} from "../lib/workflow-layout";
import {graphAtPath, replaceGraph, validGraphPath} from "../lib/workflow-editor-state";
import {useWorkflowHistory} from "../hooks/use-workflow-history";
import {WorkflowGraphCanvas} from "./workflow-graph-canvas";
import {workflowIcons, nodeChoices} from "./workflow-node-catalog";
import {WorkflowNodeFields} from "./workflow-node-fields";
import ui from "@/components/ui/surface.module.css";
import styles from "./workflow.module.css";
import panels from "./workflow-panels.module.css";

type Addable = Exclude<NodeType, "start" | "end">;
type EditorProps = {
  enterprise: string; value: WorkflowConfig; onChange: (value: WorkflowConfig) => void;
  permissions: string[]; readOnly: boolean; resource?: ResourceSummary; busy?: boolean; fillHeight?: boolean;
  assistantOpen?: boolean; onInspectorOpen?: () => void; onCanvasElement?: (element: HTMLDivElement | null) => void;
};

export function WorkflowEditor({onCanvasElement, ...props}: EditorProps) {
  const text = useT();
  const [path, setPath] = useState<string[]>([]);
  const history = useWorkflowHistory(props.value, props.onChange);
  const validPath = validGraphPath(props.value, path);
  if (validPath.length !== path.length) {
    setPath(validPath);
  }
  const graph = graphAtPath(props.value, path);
  const change = (next: WorkflowGraph, key = "") => history.commit({...props.value, ...replaceGraph(props.value, path, next)}, key ? `${path.join("/")}:${key}` : "");
  return <div className={`${styles.fields} ${props.fillHeight ? styles.fillHeight : ""}`} ref={onCanvasElement}>
    {path.length > 0 && <nav className={styles.breadcrumb} aria-label={text("循环步骤位置")}>
      <Button type="button" className={ui.button} onClick={() => setPath([])}>{text("完整流程")}</Button>
      {path.map((id, index) => <Button type="button" className={ui.button} key={path.slice(0, index + 1).join("/")} onClick={() => setPath(path.slice(0, index + 1))}>
        {graphAtPath(props.value, path.slice(0, index)).nodes.find((node) => node.nodeId === id)?.name}
      </Button>)}
    </nav>}
    <WorkflowEditorView {...props} key={path.join("/")} value={{icon: props.value.icon, color: props.value.color, ...graph}}
      resource={path.length ? undefined : props.resource} totalCount={graphNodeCount(props.value)} depth={path.length}
      onOpenLoop={(id) => setPath([...path, id])} changeGraph={change} history={history}/>
  </div>;
}

type ViewProps = EditorProps & {totalCount: number; depth: number; onOpenLoop: (id: string) => void;
  changeGraph: (graph: WorkflowGraph, key?: string) => void; history: ReturnType<typeof useWorkflowHistory>};

function WorkflowEditorView({enterprise, value, permissions, readOnly, resource, busy = false, totalCount, depth,
  onOpenLoop, assistantOpen, onInspectorOpen, changeGraph, history}: ViewProps) {
  const uiText = useT();
  const [fitKey, setFitKey] = useState(0);
  const [selected, setSelected] = useState("");
  const [selection, setSelection] = useState<string[]>([]);
  const [selectedEdge, setSelectedEdge] = useState("");
  const [adding, setAdding] = useState(false);
  const [deleting, setDeleting] = useState<string[]>([]);
  const [validation, setValidation] = useState<{config: string; result: WorkflowValidation} | null>(null);
  const [checking, setChecking] = useState(false);
  const [arranging, setArranging] = useState(false);
  const [error, setError] = useState("");
  const mutation = useRef(new ApiMutation());
  const panel = useRef<HTMLDivElement>(null);
  const latest = useRef(value);
  useEffect(() => {
    latest.current = value;
  }, [value]);
  const node = value.nodes.find((node) => node.nodeId === selected);
  const group = value.groups?.find((group) => group.groupId === selected);
  const serialized = JSON.stringify(value);
  const current = validation?.config === serialized ? validation.result : null;
  const locked = readOnly || busy || checking || arranging;
  const fieldsValid = () => Array.from(panel.current?.querySelectorAll<HTMLTextAreaElement>("textarea[data-workflow-json]") ?? []).every((field) => field.reportValidity());
  const graphChange = (graph: WorkflowGraph, key = "") => {
    if (!locked) {
      changeGraph(graph, key);
    }
  };
  const select = (id: string) => {
    if (fieldsValid()) {
      setSelected(id);
      setSelectedEdge("");
      if (id) {
        onInspectorOpen?.();
      }
    }
  };
  const dispatch = (command: WorkflowCommand, key = "") => {
    if (locked || !fieldsValid()) {
      return;
    }
    const result = executeWorkflowCommand(value, command);
    if (result.error) {
      setError(uiText(result.error));
      return;
    }
    if (!commandFitsLimits(totalCount, value, result.graph)) {
      setError(uiText("包含循环内部步骤在内，最多允许 50 个节点，100 条连接。"));
      return;
    }
    setError("");
    graphChange(result.graph, key);
    if (result.selected) {
      setSelection(result.selected);
      select(result.selected.length === 1 ? result.selected[0] : "");
    }
  };
  const canAdd = (type: Addable) => totalCount + (type === "loop" ? 3 : 1) <= 50 && (type !== "loop" || depth < 3);
  const add = (type: Addable, position?: WorkflowNode["position"], origin?: ConnectionOrigin) => {
    if (!canAdd(type)) {
      return;
    }
    dispatch({type: "add", kind: type, position, origin});
    setAdding(false);
    if (!position) {
      setFitKey((key) => key + 1);
    }
  };
  const arrange = async () => {
    if (locked || !fieldsValid()) {
      return;
    }
    setArranging(true);
    setError("");
    try {
      const next = await arrangeWorkflow(value);
      if (JSON.stringify(latest.current) === serialized) {
        changeGraph(next);
        setFitKey((key) => key + 1);
      }
    } catch (failed) {
      console.error("整理工作流布局失败", {resourceId: resource?.id}, failed);
      setError(uiText("整理布局失败，请重试。"));
    } finally {
      setArranging(false);
    }
  };
  const undo = (redo = false) => {
    if (!locked && fieldsValid()) {
      history.undo(redo);
      setSelected("");
      setSelectedEdge("");
      setSelection([]);
    }
  };
  const copy = (ids: string[]) => dispatch({type: "copy", ids});
  const requestDelete = (ids: string[]) => {
    if (!locked && fieldsValid()) {
      setDeleting(value.nodes.filter((node) => ids.includes(node.nodeId) && !["start", "end"].includes(node.type)).map((node) => node.nodeId));
    }
  };
  const changeNode = (node: WorkflowNode) => dispatch({type: "update", node}, `node:${node.nodeId}`);
  async function validate() {
    if (!resource || !fieldsValid() || checking) {
      return;
    }
    setChecking(true);
    setError("");
    try {
      const result = await mutation.current.run<WorkflowValidation>(organizationPath(enterprise, `/workflows/${encodeURIComponent(resource.id)}/validate`), {method: "POST", body: value});
      setValidation({config: serialized, result});
      const first = result.errors.find((error) => error.nodeId || error.edgeId);
      if (first) {
        select(first.nodeId ?? value.edges.find((edge) => edge.edgeId === first.edgeId)?.source ?? "");
      }
    } catch (failed) {
      console.error("校验工作流失败", {resourceId: resource.id}, failed);
      setError(errorMessage(failed));
    } finally {
      setChecking(false);
    }
  }
  return <section className={styles.editor} aria-label={uiText("工作流编辑")} onBlurCapture={history.endEdit}
    onKeyDown={(event) => {
      if (event.key === "Escape" && selected) {
        event.stopPropagation();
        select("");
        return;
      }
      if ((event.target as HTMLElement).closest("input,textarea,select,[contenteditable=true]")) {
        return;
      }
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "z") {
        event.preventDefault();
        undo(event.shiftKey);
      }
    }}>
    <div className={styles.toolbar}>
      <div className={ui.actions}>
        {!readOnly && <><Popover open={adding} onOpenChange={setAdding}>
          <PopoverTrigger className={ui.primary} disabled={locked || totalCount >= 50}><IconPlus size={16}/>{uiText("添加节点")}</PopoverTrigger>
          <PopoverContent align="start" className={styles.nodeLibrary}><PopoverTitle>{uiText("选择节点")}</PopoverTitle>
            {nodeChoices.map(({type, description}) => {
              const Icon = workflowIcons[type];
              return <Button className={styles.libraryItem} type="button" key={type} disabled={!canAdd(type)} onClick={() => add(type)}>
                <Icon size={19}/><span><strong>{uiText(nodeNames[type])}</strong><small>{uiText(description)}</small></span>
              </Button>;
            })}
          </PopoverContent></Popover>
          <Button className={ui.button} type="button" disabled={locked} onClick={() => void arrange()}><IconLayoutGrid size={16}/>{uiText(arranging ? "正在整理…" : "整理布局")}</Button>
          <span className={styles.historyControls}>
            <Button type="button" className="icon-button" disabled={locked || !history.canUndo} aria-label={uiText("撤销")} title={uiText("撤销（Ctrl+Z）")} onClick={() => undo()}><IconArrowLeft size={18}/></Button>
            <Button type="button" className="icon-button" disabled={locked || !history.canRedo} aria-label={uiText("重做")} title={uiText("重做（Ctrl+Shift+Z）")} onClick={() => undo(true)}><IconArrowRight size={18}/></Button>
          </span></>}
        <span className={styles.graphCount}>{value.nodes.length}{uiText(" 个节点 · ")}{value.edges.length}{uiText(" 条连接")}</span>
      </div>
      {!readOnly && depth === 0 && <Button className={ui.button} type="button" disabled={!resource || locked}
        title={!resource ? uiText("保存草稿后检查") : undefined} onClick={() => void validate()}><IconCheck size={16}/>{uiText(checking ? "正在检查…" : "检查结构")}</Button>}
    </div>
    <div className={styles.layout}><div className={styles.graphArea}>
      <WorkflowGraphCanvas graph={value} fitKey={fitKey} onAdd={locked ? undefined : add} canAdd={canAdd}
        onCopy={locked ? undefined : copy} onDelete={locked ? undefined : requestDelete} onArrange={locked ? undefined : () => void arrange()}
        onGroup={locked ? undefined : (ids) => dispatch({type: "group", ids, name: uiText("分组")})}
        onUngroup={locked ? undefined : (id) => dispatch({type: "ungroup", id})}
        inspectorOpen={Boolean((node || group) && !assistantOpen)}
        onReconnect={locked ? undefined : (id, source, target, branch) => dispatch({type: "reconnect", id, source, target, branch})}
        onDeleteEdge={locked ? undefined : (id) => {
          dispatch({type: "deleteEdge", id});
          setSelectedEdge("");
        }}
        selected={selected} selection={selection} onSelection={(ids) => {
          if (fieldsValid()) {
            setSelection(ids);
            if (ids.length !== 1) {
              setSelected("");
            }
          }
        }} onSelect={select} selectedEdge={selectedEdge} onSelectEdge={(id) => {
          if (fieldsValid()) {
            setSelectedEdge(id);
          }
        }} invalidNodes={current?.errors.flatMap((error) => error.nodeId ? [error.nodeId] : [])}
        onConnect={locked ? undefined : (source, target, branch) => dispatch({type: "connect", source, target, branch})}
        onMove={locked ? undefined : (positions) => dispatch({type: "move", positions})}/>
      {(current || error) && <aside className={styles.canvasFeedback} aria-label={uiText("画布提示")}>
        <Button className={`icon-button ${styles.dismissFeedback}`} type="button" aria-label={uiText("关闭画布提示")} onClick={() => {
          setValidation(null);
          setError("");
        }}><IconX size={16}/></Button>
        {current?.valid && <p className={styles.validation} role="status"><IconCheck size={16}/>{uiText("当前流程校验通过。")}</p>}
        {error && <p className={ui.error} role="alert">{localizeUiMessage(error, uiText)}</p>}
        {current && !current.valid && <ul className={styles.errors} aria-label={uiText("流程中需要修正的问题")}>
          {current.errors.map((issue, index) => <li key={index}><Button type="button" onClick={() => {
            const id = issue.nodeId ?? value.edges.find((edge) => edge.edgeId === issue.edgeId)?.source;
            if (id) {
              select(id);
            }
          }}>{issue.nodeId ? `${value.nodes.find((node) => node.nodeId === issue.nodeId)?.name ?? uiText("节点")}：` : ""}{issue.message}</Button></li>)}
        </ul>}
      </aside>}
    </div>
      {node && <div className={`${panels.floating} ${panels.inspector} ${styles.configuration}`} ref={panel} hidden={assistantOpen} inert={assistantOpen}>
        <div className={styles.heading}><h3>{localizeCatalog(nodeNames, uiText)[node.type]}</h3><Button
          className="icon-button" type="button" aria-label={uiText("关闭节点配置")} onClick={() => select("")}><IconX
          size={18}/></Button></div>
        <WorkflowNodeFields key={node.nodeId} enterprise={enterprise} graph={value} node={node}
                            onOpenLoop={() => {
                              if (fieldsValid()) {
                                onOpenLoop(node.nodeId);
                              }
                            }}
                            permissions={permissions} onChange={changeNode} readOnly={locked}/>
        {node.type === "loop" && readOnly && <Button className={ui.button} type="button" onClick={() => onOpenLoop(node.nodeId)}>{uiText("查看循环内的步骤")}</Button>}
        {!locked && !["start", "end"].includes(node.type) &&
          <div className={ui.actions}><Button className={ui.button} type="button" onClick={() => copy([node.nodeId])}><IconCopy size={16}/>{uiText("复制节点")}</Button>
          <Button className={ui.danger} type="button" onClick={() => requestDelete([node.nodeId])}><IconTrash
            size={16}/>{uiText("删除节点")}</Button></div>}
      </div>}
      {group && <div className={`${panels.floating} ${panels.inspector} ${styles.configuration}`} hidden={assistantOpen} inert={assistantOpen}>
        <div className={styles.heading}><h3><IconStack size={18}/>{uiText("分组")}</h3><Button type="button" className="icon-button" aria-label={uiText("关闭分组配置")} onClick={() => select("")}><IconX size={18}/></Button></div>
        <label className={ui.field}><span>{uiText("分组名称")}</span><Input value={group.name} maxLength={50} required disabled={locked}
          onChange={(event) => dispatch({type: "renameGroup", id: group.groupId, name: event.target.value}, `group:${group.groupId}`)}/></label>
        <p className={ui.description}>{uiText("{0} 个节点", [group.nodeIds.length])}</p>
        {!locked && <Button type="button" className={ui.button} onClick={() => dispatch({type: "ungroup", id: group.groupId})}><IconStack size={16}/>{uiText("取消分组")}</Button>}
      </div>}
    </div>
    {deleting.length > 0 && <Dialog title={deleting.length === 1 ? uiText("删除“{0}”？", [value.nodes.find((node) => node.nodeId === deleting[0])?.name]) : uiText("删除所选的 {0} 个节点？", [deleting.length])}
      onClose={() => setDeleting([])}><p className={ui.description}>{uiText("同时移除 ")}{value.edges.filter((edge) => deleting.includes(edge.source) || deleting.includes(edge.target)).length}{uiText(" 条连接。")}</p>
      <DialogActions className={ui.footer}><Button className={ui.button} type="button" onClick={() => setDeleting([])}>{uiText("保留节点")}</Button>
        <Button className={ui.danger} type="button" onClick={() => {
          dispatch({type: "delete", ids: deleting});
          setDeleting([]);
        }}>{uiText("删除节点及连接")}</Button></DialogActions>
    </Dialog>}
  </section>;
}
