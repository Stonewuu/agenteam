"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";
import {Input} from "@/components/ui/input";
import {Checkbox} from "@/components/ui/checkbox";

import {useState} from "react";
import {IconChevronDown} from "@/components/ui/icons";
import {Popover, PopoverContent, PopoverTrigger} from "@/components/ui/shadcn/popover";
import {Pagination, QueryState} from "@/components/ui/query-state";
import {useApiPage} from "@/lib/http/use-api-query";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import type {Tag} from "../types/resource";
import ui from "@/components/ui/surface.module.css";
import styles from "./tag-filter.module.css";

export function TagFilter({enterpriseId, selected, onChange}: {
  enterpriseId: string;
  selected: string[];
  onChange: (ids: string[]) => void
}) {
  const uiText = useT();
  const [open, setOpen] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const [query, setQuery] = useState("");
  const tags = useApiPage<Tag>(organizationPath(enterpriseId, `/tags?query=${encodeURIComponent(query)}`), 0, 200, 30, loaded);
  return <Popover open={open} onOpenChange={(next) => {
    setOpen(next);
    if (next) {
      setLoaded(true);
    }
  }}>
    <PopoverTrigger className={styles.trigger}
                    aria-label={uiText("按标签筛选")}>{uiText("标签筛选")}{selected.length ? ` · ${selected.length}` : ""}<IconChevronDown
      size={16}/></PopoverTrigger>
    <PopoverContent className={`agenteam-popup ${styles.popup}`} align="start">
      <div className={styles.heading}><strong>{uiText("按标签筛选")}</strong>{selected.length > 0 &&
        <Button className={styles.clear} type="button" onClick={() => onChange([])}>{uiText("清除")}</Button>}</div>
      <Input className={ui.input} type="search" aria-label={uiText("搜索筛选标签")} placeholder={uiText("搜索标签")}
             value={query} onChange={(event) => setQuery(event.target.value)}/>
      <div className={styles.list}><QueryState {...tags} hasData={Boolean(tags.data?.items.length)}
                                               empty={query ? uiText("没有匹配的标签。") : uiText("还没有可用于筛选的标签。")}>
        {tags.data?.items.map((tag) => <label className={styles.option} key={tag.id}><Checkbox
          checked={selected.includes(tag.id)}
          disabled={!selected.includes(tag.id) && selected.length >= 10}
          onCheckedChange={(checked) => onChange(checked ? [...selected, tag.id] : selected.filter((id) => id !== tag.id))}/>{tag.name}
        </label>)}
      </QueryState></div>
      <Pagination {...tags} hasMore={tags.data?.hasMore}/>
    </PopoverContent>
  </Popover>;
}
