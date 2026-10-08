import {localizeCatalog, type Translator} from "@/lib/i18n/translate";
import type {ResourceKind} from "../types/resource";

// 使用完整短句，让各语言分别处理单复数、词序和大小写，不拼接标题名称。
const messages = {
  agent: {
    create: "创建智能体",
    search: "搜索智能体",
    refresh: "刷新智能体",
    empty: "还没有智能体，可以先创建一个。",
    noMatches: "没有匹配的智能体，请调整筛选条件。"
  },
  skill: {
    create: "创建技能",
    search: "搜索技能",
    refresh: "刷新技能",
    empty: "还没有技能，可以先创建一个。",
    noMatches: "没有匹配的技能，请调整筛选条件。"
  },
  plugin: {
    create: "创建插件",
    search: "搜索插件",
    refresh: "刷新插件",
    empty: "还没有插件，可以先创建一个。",
    noMatches: "没有匹配的插件，请调整筛选条件。"
  },
  workflow: {
    create: "创建工作流",
    search: "搜索工作流",
    refresh: "刷新工作流",
    empty: "还没有工作流，可以先创建一个。",
    noMatches: "没有匹配的工作流，请调整筛选条件。"
  },
  knowledge: {
    create: "创建知识库",
    search: "搜索知识库",
    refresh: "刷新知识库",
    empty: "还没有知识库，可以先创建一个。",
    noMatches: "没有匹配的知识库，请调整筛选条件。"
  },
  data: {
    create: "创建数据源",
    search: "搜索数据源",
    refresh: "刷新数据源",
    empty: "还没有数据源，可以先创建一个。",
    noMatches: "没有匹配的数据源，请调整筛选条件。"
  },
} satisfies Record<ResourceKind, Record<"create" | "search" | "refresh" | "empty" | "noMatches", string>>;

export function resourceListCopy(kind: ResourceKind, t: Translator) {
  return localizeCatalog(messages[kind], t);
}
