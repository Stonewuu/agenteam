"use client";

import {useEffect, useState} from "react";
import {apiRequest, errorMessage} from "@/lib/http/api-client";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import type {DependencyOptions, WorkflowGraph, WorkflowNode} from "../types/workflow";
import {upstreamNodeIds} from "../lib/workflow-graph";

/** 所选能力与上游输出共用一次版本读取，返回值始终按企业和版本隔离。 */
export function useWorkflowDependencies(enterprise: string, graph: WorkflowGraph, node: WorkflowNode, permissions: string[]) {
  const upstream = upstreamNodeIds(graph, node.nodeId);
  const allowed = ["workflow.create", "workflow.edit", "workflow.preview"].some((permission) => permissions.includes(permission));
  const ids = allowed ? graph.nodes.flatMap((item) => {
    if (item.nodeId !== node.nodeId && !upstream.has(item.nodeId)) {
      return [];
    }
    const plugin = item.type === "tool";
    if (!plugin && item.nodeId !== node.nodeId || !permissions.includes(plugin ? "plugin.invoke" : "agent.run")) {
      return [];
    }
    const id = item.config[plugin ? "pluginVersionId" : "agentVersionId"];
    return typeof id === "string" && id ? [id] : [];
  }) : [];
  const key = JSON.stringify([enterprise, [...new Set(ids)].sort()]);
  const [attempt, setAttempt] = useState(0);
  const [state, setState] = useState<{key: string; data: Record<string, DependencyOptions>; errors: Record<string, string>}>({key: "", data: {}, errors: {}});
  useEffect(() => {
    const [scope, versions] = JSON.parse(key) as [string, string[]], controller = new AbortController();
    void Promise.all(versions.map(async (version) => {
      try {
        const data = await apiRequest<DependencyOptions>(organizationPath(scope, `/workflows/dependency-options/${encodeURIComponent(version)}`), {signal: controller.signal});
        return {version, data, error: ""};
      } catch (error) {
        return {version, data: undefined, error: errorMessage(error)};
      }
    })).then((results) => {
      if (!controller.signal.aborted) {
        setState({key, data: Object.fromEntries(results.flatMap((result) => result.data ? [[result.version, result.data]] : [])),
          errors: Object.fromEntries(results.filter((result) => result.error).map((result) => [result.version, result.error]))});
      }
    });
    return () => controller.abort();
  }, [key, attempt]);
  const current: Pick<typeof state, "data" | "errors"> = state.key === key ? state : {data: {}, errors: {}};
  return {...current, loading: ids.length > 0 && state.key !== key, retry: () => {
    setState({key: "", data: {}, errors: {}});
    setAttempt((value) => value + 1);
  }};
}
