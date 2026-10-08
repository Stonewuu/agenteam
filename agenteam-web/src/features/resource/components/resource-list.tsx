"use client";

import {useT} from "@/lib/i18n/locale-provider";
import {localizeCatalog} from "@/lib/i18n/translate";

import {toast} from "@/components/ui/toast";

import {Button} from "@/components/ui/button";

import {PageHeader} from "@/components/ui/page-header";

import {Select} from "@/components/ui/select";

import Link from "next/link";
import {usePathname, useRouter, useSearchParams} from "next/navigation";
import {useState} from "react";

import {SearchInput} from "@/components/ui/search-input";
import {IconAdjustments, IconLayoutGrid, IconList, IconPlus, IconRefresh} from "@/components/ui/icons";
import {Popover, PopoverContent, PopoverTrigger} from "@/components/ui/shadcn/popover";
import {ResourceCollection} from "./resource-collection";

import {resourcePage, resourceSubtypes, resourceTypes} from "../lib/resource-display";
import {resourceListCopy} from "../lib/resource-list-copy";
import type {ResourceKind} from "../types/resource";

import {TagFilter} from "./tag-filter";

import {SkillImportDialog} from "@/features/skill/components/skill-import-dialog";
import {BuiltinPluginCatalog} from "@/features/plugin/components/builtin-plugin-catalog";
import ui from "@/components/ui/surface.module.css";
import styles from "./resource.module.css";

export function ResourceList({enterpriseId, kind, permissions}: {
  enterpriseId: string;
  kind: ResourceKind;
  permissions: string[]
}) {
  const uiText = useT();
  const params = useSearchParams();
  const pathname = usePathname();
  const router = useRouter();
  const [importing, setImporting] = useState(false);
  const [builtins, setBuiltins] = useState(false);

  const view = params.get("view") === "list" ? "list" : "grid";
  const type = localizeCatalog(resourceTypes, uiText).find((value) => value.kind === kind)!;
  const copy = resourceListCopy(kind, uiText);
  const canView = permissions.includes(`${kind}.view`);
  const canCreate = permissions.includes(`${kind}.create`);
  const [refresh, setRefresh] = useState(0);
  const [tags, setTags] = useState<string[]>([]);
  const change = (key: string, value: string) => {
    const next = new URLSearchParams(params.toString());
    if (value) {
      next.set(key, value);
    } else {
      next.delete(key);
    }
    window.history.replaceState(null, "", `${pathname}${next.size ? `?${next}` : ""}`);
  };
  const filters = new URLSearchParams({kind});
  for (const key of ["query", "status", "source", "subtype", "sort"]) {
    const value = params.get(key);
    if (value) {
      filters.set(key, value);
    }
  }
  if (tags.length) {
    filters.set("tagIds", tags.join(","));
  }
  return <>
    <PageHeader title={type.label} description={({
      agent: uiText("把工作经验变成可持续使用的能力。"),
      skill: uiText("把常用方法整理为可重复使用的技能。"),
      plugin: uiText("连接工作中需要的工具与服务。"),
      workflow: uiText("将多个工作步骤编排为可执行的流程。"),
      knowledge: uiText("集中整理资料，让每个回答有据可依。"),
      data: uiText("连接业务数据，为任务提供所需的信息。")
    })[kind]} actions={<>
      {canView && <Button className="icon-button" aria-label={copy.refresh}
                          onClick={() => setRefresh((value) => value + 1)}><IconRefresh size={18}/></Button>}
      {kind === "skill" && canCreate && permissions.includes("skill.import") &&
        <Button className={ui.button} onClick={() => setImporting(true)}>{uiText("导入技能")}</Button>}
      {kind === "plugin" && canView &&
        <Button className={ui.button} onClick={() => setBuiltins(true)}>{uiText("内置插件")}</Button>}
      {canCreate &&
        <Link className={ui.primary} href={resourcePage(enterpriseId, kind, "new")}><IconPlus size={16}/>{copy.create}
        </Link>}</>}/>
    {canView && <>
      <div className={styles.toolbar}>
        <SearchInput className={styles.search} aria-label={copy.search} placeholder={copy.search}
                     value={params.get("query") ?? ""} onChange={(event) => change("query", event.target.value)}/>
        <Select className={ui.select} aria-label={uiText("状态")} value={params.get("status") ?? ""}
                onChange={(event) => change("status", event.target.value)}>
          <option value="">{uiText("全部状态")}</option>
          <option value="draft">{uiText("草稿")}</option>
          <option value="published">{uiText("已发布")}</option>
          {kind === "agent" && <option value="unlisted">{uiText("已下架")}</option>}
          <option value="disabled">{uiText("已停用")}</option>
          <option value="deleted">{uiText("已删除")}</option>
        </Select>
        <Select className={ui.select} aria-label={uiText("来源")} value={params.get("source") ?? ""}
                onChange={(event) => change("source", event.target.value)}>
          <option value="">{uiText("全部来源")}</option>
          <option value="created">{uiText("自主创建")}</option>
          <option value="imported">{uiText("文件导入")}</option>
          <option value="builtin">{uiText("内置模板")}</option>
        </Select>
        <TagFilter enterpriseId={enterpriseId} selected={tags} onChange={setTags}/>
        <Popover><PopoverTrigger className="icon-button" aria-label={uiText("更多筛选")}><IconAdjustments
          size={19}/></PopoverTrigger><PopoverContent align="end" className="agenteam-popup resource-filters">
          <div className={ui.form}>      {localizeCatalog(resourceSubtypes, uiText)[kind] &&
            <Select className={ui.select} aria-label={uiText("类型")} value={params.get("subtype") ?? ""}
                    onChange={(event) => change("subtype", event.target.value)}>
              <option value="">{uiText("全部类型")}</option>
              {localizeCatalog(resourceSubtypes, uiText)[kind]!.map(([value, name]) => <option value={value}
                                                                                               key={value}>{name}</option>)}
            </Select>}
            <Select className={ui.select} aria-label={uiText("排序方式")} value={params.get("sort") ?? "updated_desc"}
                    onChange={(event) => change("sort", event.target.value)}>
              <option value="updated_desc">{uiText("最近修改")}</option>
              <option value="created_desc">{uiText("最近创建")}</option>
              <option value="name_asc">{uiText("名称顺序")}</option>
            </Select>
          </div>
        </PopoverContent></Popover>

        <div className={styles.viewToggle} data-view={view} role="group" aria-label={uiText("显示方式")}><Button
          type="button" aria-label={uiText("卡片视图")} aria-pressed={view === "grid"}
          onClick={() => change("view", "")}><IconLayoutGrid size={18}/></Button><Button type="button"
                                                                                         aria-label={uiText("列表视图")}
                                                                                         aria-pressed={view === "list"}
                                                                                         onClick={() => change("view", "list")}><IconList
          size={18}/></Button></div>
        {(filters.size > 1 || tags.length > 0) && <Button className={ui.button} onClick={() => {
          window.history.replaceState(null, "", pathname);
          setTags([]);
        }}>{uiText("清除筛选")}</Button>}
      </div>
      <ResourceCollection view={view} enterpriseId={enterpriseId} kind={kind} filters={filters.toString()}
                          refresh={refresh} onChanged={() => setRefresh((value) => value + 1)}
                          filtered={filters.size > 1}/>
    </>}
    {!canView && <div
      className={ui.empty}>{uiText("当前账号可以创建")}{type.label}{uiText("，不能查看")}{type.label}{uiText("列表。")}</div>}
    {builtins &&
      <BuiltinPluginCatalog enterpriseId={enterpriseId} canCreate={canCreate} onClose={() => setBuiltins(false)}
                            onChoose={(plugin) => router.push(`${resourcePage(enterpriseId, "plugin", "new")}?builtin=${encodeURIComponent(plugin.code)}&section=connection`)}/>}
    {importing &&
      <SkillImportDialog enterpriseId={enterpriseId} permissions={permissions} onClose={() => setImporting(false)}
                         onImported={(value) => {
                           setImporting(false);
                           setRefresh((current) => current + 1);
                           toast.success(uiText("技能草稿已创建。"));
                           if (canView) {
                             router.push(resourcePage(enterpriseId, "skill", value.resource.id));
                           }
                         }}/>}
  </>;
}
