"use client";

import {localizeUiMessage} from "@/lib/i18n/ui-message";

import {useT} from "@/lib/i18n/locale-provider";
import {localizeCatalog} from "@/lib/i18n/translate";

import {Button} from "@/components/ui/button";

import {Select} from "@/components/ui/select";


import {useState} from "react";
import type {Branch, WorkflowGraph, WorkflowNode} from "../types/workflow";
import {branches, branchNames, connectionError, edge} from "../lib/workflow-graph";
import ui from "@/components/ui/surface.module.css";
import styles from "./workflow.module.css";

export function WorkflowConnectionsEditor({graph, node, onChange, onSelect, readOnly}: {
  graph: WorkflowGraph;
  node: WorkflowNode;
  onChange: (graph: WorkflowGraph) => void;
  onSelect: (id: string) => void;
  readOnly: boolean
}) {
  const uiText = useT();
  const [error, setError] = useState(""), [target, setTarget] = useState(""), [branch, setBranch] = useState<Branch>(branches(node.type)[0] ?? "default");
  const outgoing = graph.edges.filter((item) => item.source === node.nodeId),
    incoming = graph.edges.filter((item) => item.target === node.nodeId);
  return <section className={styles.section}><h4>{uiText("连接")}</h4>
    {incoming.length > 0 &&
      <div className={ui.chips}>{incoming.map((item) => <Button className={ui.button} type="button" key={item.edgeId}
                                                                onClick={() => onSelect(item.source)}>{uiText("从 ")}{graph.nodes.find((node) => node.nodeId === item.source)?.name} · {localizeCatalog(branchNames, uiText)[item.branch]}</Button>)}</div>}
    {outgoing.map((item) => <div className={styles.edge} key={item.edgeId}>
      <span>{localizeCatalog(branchNames, uiText)[item.branch]}</span><Select className={ui.select} value={item.target}
                                                                              aria-label={uiText("{0}分支的后续节点", [localizeCatalog(branchNames, uiText)[item.branch]])}
                                                                              disabled={readOnly} onChange={(event) => {
      const candidate = {...item, target: event.target.value}, issue = connectionError(graph, candidate, item.edgeId);
      setError(issue);
      if (!issue) {
        onChange({...graph, edges: graph.edges.map((current) => current.edgeId === item.edgeId ? candidate : current)});
      }
    }}>{graph.nodes.map((node) => <option key={node.nodeId} value={node.nodeId}>{node.name}</option>)}</Select>
      {!readOnly && <Button className={ui.danger} type="button"
                            aria-label={uiText("删除{0}连接", [localizeCatalog(branchNames, uiText)[item.branch]])}
                            onClick={() => {
                              onChange({
                                ...graph,
                                edges: graph.edges.filter((current) => current.edgeId !== item.edgeId)
                              });
                              setError("");
                            }}>×</Button>}
    </div>)}
    {!readOnly && node.type !== "end" && <div className={styles.fields}>
      <label className={ui.field}><span>{uiText("添加后续连接")}</span><Select className={ui.select}
                                                                               aria-label={uiText("新连接的分支")}
                                                                               value={branch}
                                                                               onChange={(event) => setBranch(event.target.value as Branch)}>{branches(node.type).map((branch) =>
        <option value={branch} key={branch}>{localizeCatalog(branchNames, uiText)[branch]}</option>)}</Select></label>
      <Select className={ui.select} aria-label={uiText("新连接的后续节点")} value={target}
              onChange={(event) => setTarget(event.target.value)}>
        <option value="">{uiText("请选择后续节点")}</option>
        {graph.nodes.map((node) => <option key={node.nodeId} value={node.nodeId}>{node.name}</option>)}</Select>
      <Button className={ui.button} type="button" disabled={!target || graph.edges.length >= 100} onClick={() => {
        const candidate = edge(node.nodeId, target, branch), issue = connectionError(graph, candidate);
        setError(issue);
        if (!issue) {
          onChange({...graph, edges: [...graph.edges, candidate]});
          setTarget("");
        }
      }}>{uiText("添加连接")}</Button>
    </div>}
    {error && <p className={ui.error} role="alert">{localizeUiMessage(error ?? "", uiText)}</p>}
  </section>;
}
