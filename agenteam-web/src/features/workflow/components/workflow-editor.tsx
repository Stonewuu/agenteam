"use client";

import {localizeUiMessage} from "@/lib/i18n/ui-message";

import {useT} from "@/lib/i18n/locale-provider";
import {localizeCatalog} from "@/lib/i18n/translate";

import {Button} from "@/components/ui/button";

import {Select} from "@/components/ui/select";
import {Popover, PopoverContent, PopoverTitle, PopoverTrigger} from "@/components/ui/shadcn/popover";
import {IconCheck, IconPlus, IconTrash, IconX} from "@/components/ui/icons";


import {useRef, useState} from "react";
import {Dialog, DialogActions} from "@/components/ui/dialog";
import {ApiMutation, errorMessage} from "@/lib/http/api-client";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import type {ResourceSummary, WorkflowConfig} from "@/features/resource/types/resource";
import type {NodeType, WorkflowGraph, WorkflowNode, WorkflowValidation} from "../types/workflow";
import {
  arrangeGraph,
  branches,
  branchNames,
  connectionError,
  edge,
  insertNode,
  nodeNames,
  removeNode
} from "../lib/workflow-graph";
import {WorkflowGraphCanvas, workflowIcons} from "./workflow-graph-canvas";
import {WorkflowNodeFields} from "./workflow-node-fields";
import {WorkflowConnectionsEditor} from "./workflow-connections-editor";
import ui from "@/components/ui/surface.module.css";
import styles from "./workflow.module.css";

type Addable = Exclude<NodeType, "start" | "end" | "join">;

export function WorkflowEditor({enterprise, value, onChange, permissions, readOnly, resource, busy = false}: {
  enterprise: string;
  value: WorkflowConfig;
  onChange: (value: WorkflowConfig) => void;
  permissions: string[];
  readOnly: boolean;
  resource?: ResourceSummary;
  busy?: boolean;
}) {
  const uiText = useT();
  const [selected, setSelected] = useState(""), [adding, setAdding] = useState(false), [deleting, setDeleting] = useState<string | null>(null);
  const [selectedEdge, setSelectedEdge] = useState("");
  const [validation, setValidation] = useState<{
    config: string;
    result: WorkflowValidation
  } | null>(null), [checking, setChecking] = useState(false), [error, setError] = useState("");
  const mutation = useRef(new ApiMutation()), panel = useRef<HTMLDivElement>(null);
  const node = value.nodes.find((node) => node.nodeId === selected), serialized = JSON.stringify(value),
    current = validation?.config === serialized ? validation.result : null;
  const connection = value.edges.find((item) => item.edgeId === selectedEdge);
  const locked = readOnly || busy || checking;
  const graphChange = (graph: WorkflowGraph) => onChange({...value, ...graph});
  const fieldsValid = () => Array.from(panel.current?.querySelectorAll<HTMLTextAreaElement>("textarea[data-workflow-json]") ?? []).every((field) => field.reportValidity());
  const select = (id: string) => {
    if (fieldsValid()) {
      setSelected(id);
    }
  };
  const changeNode = (next: WorkflowNode) => onChange({
    ...value,
    nodes: value.nodes.map((item) => item.nodeId === next.nodeId ? next : item)
  });

  async function validate() {
    if (!resource || !fieldsValid() || checking) {
      return;
    }
    setChecking(true);
    setError("");
    try {
      const result = await mutation.current.run<WorkflowValidation>(organizationPath(enterprise, `/workflows/${encodeURIComponent(resource.id)}/validate`), {
        method: "POST",
        body: value
      });
      setValidation({config: serialized, result});
      const first = result.errors.find((error) => error.nodeId || error.edgeId);
      if (first) {
        setSelected(first.nodeId ?? value.edges.find((edge) => edge.edgeId === first.edgeId)?.source ?? selected);
      }
    } catch (failed) {
      console.error("校验工作流失败", {resourceId: resource.id}, failed);
      setError(errorMessage(failed));
    } finally {
      setChecking(false);
    }
  }

  return <section className={styles.editor} aria-label={uiText("工作流编辑")}>
    <div className={styles.toolbar}>
      <div className={ui.actions}>
        {!readOnly && <><Popover open={adding} onOpenChange={setAdding}><PopoverTrigger className={ui.primary}
                                                                                        disabled={locked || value.nodes.length >= 50}><IconPlus
          size={16}/>{uiText("添加节点")}</PopoverTrigger><PopoverContent align="start"
                                                                          className={styles.nodeLibrary}><PopoverTitle>{uiText("选择节点")}</PopoverTitle>
          {(["agent", "skill", "tool", "condition", "transform", "approval", "parallel"] as Addable[]).map((type) => {
            const Icon = workflowIcons[type];
            return <Button className={styles.libraryItem} type="button" key={type}
                           disabled={value.nodes.length + (type === "parallel" ? 4 : 1) > 50} onClick={() => {
              if (!fieldsValid()) {
                return;
              }
              const next = insertNode(value, type, node?.nodeId ?? "");
              graphChange(next.graph);
              setSelected(next.selected);
              setSelectedEdge("");
              setAdding(false);
            }}><Icon size={19}/>{type === "parallel" ? uiText("并行与汇合") : localizeCatalog(nodeNames, uiText)[type]}
            </Button>;
          })}
        </PopoverContent></Popover><Button className={ui.button} type="button" disabled={locked}
                                           onClick={() => graphChange(arrangeGraph(value))}>{uiText("整理布局")}</Button></>}
        <span
          className={styles.graphCount}>{value.nodes.length}{uiText(" 个节点 · ")}{value.edges.length}{uiText(" 条连接")}</span>
      </div>
      <div className={ui.actions}>
        {!readOnly && <Button className={ui.button} type="button" disabled={!resource || locked}
                              title={!resource ? uiText("保存草稿后检查") : undefined}
                              onClick={() => void validate()}><IconCheck
          size={16}/>{checking ? uiText("正在检查…") : uiText("检查结构")}</Button>}
      </div>
    </div>
    <div className={`${styles.layout} ${node || connection ? styles.withPanel : ""}`}><WorkflowGraphCanvas graph={value}
                                                                                                           selected={node?.nodeId}
                                                                                                           onSelect={select}
                                                                                                           selectedEdge={selectedEdge}
                                                                                                           onSelectEdge={setSelectedEdge}
                                                                                                           invalidNodes={current?.errors.flatMap((error) => error.nodeId ? [error.nodeId] : [])}
                                                                                                           onConnect={locked ? undefined : (source, target, branch) => {
                                                                                                             const candidate = edge(source, target, branch);
                                                                                                             const issue = value.edges.length >= 100 ? uiText("流程最多包含 100 条连接。") : connectionError(value, candidate);
                                                                                                             setError(issue);
                                                                                                             if (!issue) {
                                                                                                               graphChange({
                                                                                                                 ...value,
                                                                                                                 edges: [...value.edges, candidate]
                                                                                                               });
                                                                                                             }
                                                                                                           }}
                                                                                                           onMove={locked ? undefined : (id, position) => {
                                                                                                             const moving = value.nodes.find((node) => node.nodeId === id);
                                                                                                             if (moving) {
                                                                                                               changeNode({
                                                                                                                 ...moving,
                                                                                                                 position
                                                                                                               });
                                                                                                             }
                                                                                                           }}/>
      {node && <div className={`${styles.panel} ${styles.configuration}`} ref={panel}>
        <div className={styles.heading}><h3>{localizeCatalog(nodeNames, uiText)[node.type]}</h3><Button
          className="icon-button" type="button" aria-label={uiText("关闭节点配置")} onClick={() => select("")}><IconX
          size={18}/></Button></div>
        <WorkflowNodeFields key={node.nodeId} enterprise={enterprise} graph={value} node={node}
                            permissions={permissions} onChange={changeNode} readOnly={locked}
                            onPair={(id) => {
                              const pair = value.nodes.find((node) => node.nodeId === id);
                              if (!pair) {
                                return;
                              }
                              onChange({
                                ...value,
                                nodes: value.nodes.map((item) => item.nodeId === node.nodeId ? {
                                    ...item,
                                    config: {
                                      ...item.config,
                                      [node.type === "parallel" ? "joinNodeId" : "parallelNodeId"]: id
                                    }
                                  }
                                  : item.nodeId === id ? {
                                    ...item,
                                    config: {
                                      ...item.config,
                                      [pair.type === "parallel" ? "joinNodeId" : "parallelNodeId"]: node.nodeId
                                    }
                                  } : item)
                              });
                            }}/>
        <WorkflowConnectionsEditor key={`connections:${node.nodeId}`} graph={value} node={node} onChange={graphChange}
                                   onSelect={select} readOnly={locked}/>
        {!locked && !["start", "end"].includes(node.type) &&
          <Button className={ui.danger} type="button" onClick={() => setDeleting(node.nodeId)}><IconTrash
            size={16}/>{uiText("删除节点")}</Button>}
      </div>}
      {connection && <div className={`${styles.panel} ${styles.configuration}`}>
        <div className={styles.heading}><h3>{uiText("连接设置")}</h3><Button type="button" className="icon-button"
                                                                             aria-label={uiText("关闭连接设置")}
                                                                             onClick={() => setSelectedEdge("")}><IconX
          size={18}/></Button></div>
        <p>{value.nodes.find((item) => item.nodeId === connection.source)?.name} → {value.nodes.find((item) => item.nodeId === connection.target)?.name}</p>
        <label className={ui.field}><span>{uiText("分支")}</span><Select value={connection.branch} disabled={locked}
                                                                         onChange={(event) => {
                                                                           const candidate = {
                                                                             ...connection,
                                                                             branch: event.target.value as typeof connection.branch
                                                                           };
                                                                           const issue = connectionError(value, candidate, connection.edgeId);
                                                                           setError(issue);
                                                                           if (!issue) {
                                                                             graphChange({
                                                                               ...value,
                                                                               edges: value.edges.map((item) => item.edgeId === connection.edgeId ? candidate : item)
                                                                             });
                                                                           }
                                                                         }}>{branches(value.nodes.find((item) => item.nodeId === connection.source)!.type).map((branch) =>
          <option value={branch} key={branch}>{localizeCatalog(branchNames, uiText)[branch]}</option>)}</Select></label>
        {!locked && <Button type="button" className={ui.danger} onClick={() => {
          graphChange({...value, edges: value.edges.filter((item) => item.edgeId !== connection.edgeId)});
          setSelectedEdge("");
        }}><IconTrash size={16}/>{uiText("删除连接")}</Button>}
      </div>}
    </div>
    {current?.valid && <p className={styles.validation}>{uiText("当前流程校验通过。")}</p>}
    {error && <p className={ui.error} role="alert">{localizeUiMessage(error ?? "", uiText)}</p>}
    {current && !current.valid &&
      <ul className={styles.errors} aria-label={uiText("流程中需要修正的问题")}>{current.errors.map((error, index) =>
        <li key={index}><Button type="button" onClick={() => {
          const id = error.nodeId ?? value.edges.find((edge) => edge.edgeId === error.edgeId)?.source;
          if (id) {
            select(id);
          }
          panel.current?.scrollIntoView({block: "nearest"});
        }}>{error.nodeId ? `${value.nodes.find((node) => node.nodeId === error.nodeId)?.name ?? uiText("节点")}：` : ""}{error.message}</Button>
        </li>)}</ul>}
    {deleting && <Dialog title={uiText("删除“{0}”？", [value.nodes.find((node) => node.nodeId === deleting)?.name])}
                         onClose={() => setDeleting(null)}><p
      className={ui.description}>{uiText("同时移除 ")}{value.edges.filter((edge) => edge.source === deleting || edge.target === deleting).length}{uiText(" 条连接。")}</p>
      <DialogActions className={ui.footer}><Button className={ui.button} type="button"
                                                   onClick={() => setDeleting(null)}>{uiText("保留节点")}</Button><Button
        className={ui.danger} type="button" onClick={() => {
        graphChange(removeNode(value, deleting));
        setDeleting(null);
        setSelected(value.nodes.find((node) => node.type === "start")!.nodeId);
      }}>{uiText("删除节点及连接")}</Button></DialogActions>
    </Dialog>}
  </section>;
}
