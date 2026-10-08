import type {PluginConfig, PluginSource, PluginToolSelection, UsableVersion} from "@/features/resource/types/resource";
import type {ToolSource} from "../types/plugin";

export function remoteSource(value: PluginConfig) {
  return value.sources.find((source) => source.type === "mcp");
}

export function collectionSource(value: ToolSource): PluginSource {
  return {id: value.resourceId, type: "builtin", versionId: value.versionId};
}

export function asUsableVersion(value: ToolSource): UsableVersion {
  return {...value, kind: "plugin"};
}

export function sourceTools(value: ToolSource): PluginToolSelection[] {
  return value.tools.map((tool) => ({id: crypto.randomUUID(), sourceId: value.resourceId, name: tool.name}));
}

export function changeTool(value: PluginConfig, sourceId: string, name: string, checked: boolean): PluginConfig {
  const existing = value.tools.find((tool) => tool.sourceId === sourceId && tool.name === name);
  return {
    ...value, tools: checked ? existing ? value.tools : [...value.tools, {id: crypto.randomUUID(), sourceId, name}]
      : value.tools.filter((tool) => tool.sourceId !== sourceId || tool.name !== name)
  };
}

export function cleanCollection(value: PluginConfig): PluginConfig {
  const used = new Set(value.tools.map((tool) => tool.sourceId));
  return {...value, sources: value.sources.filter((source) => source.type === "mcp" || used.has(source.id))};
}
