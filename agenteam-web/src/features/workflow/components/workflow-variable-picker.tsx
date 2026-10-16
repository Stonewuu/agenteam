"use client";

import {useState} from "react";
import {Button} from "@/components/ui/button";
import {Input} from "@/components/ui/input";
import {useControlDisabled} from "@/components/ui/fieldset";
import {Popover, PopoverContent, PopoverTitle, PopoverTrigger} from "@/components/ui/shadcn/popover";
import {IconChevronDown, IconSearch} from "@/components/ui/icons";
import {useT} from "@/lib/i18n/locale-provider";
import type {VariableOption} from "../lib/workflow-parameters";
import ui from "@/components/ui/surface.module.css";
import form from "./workflow-parameters.module.css";

export function WorkflowVariablePicker({label, value = "", variables, onChange, compact = false}: {
  label: string; value?: string; variables: VariableOption[]; onChange: (path: string) => void; compact?: boolean
}) {
  const text = useT(), disabled = useControlDisabled();
  const [open, setOpen] = useState(false), [query, setQuery] = useState("");
  const selected = variables.find((item) => item.path === value);
  const filtered = variables.filter((item) => `${item.label} ${item.path}`.toLowerCase().includes(query.trim().toLowerCase()));
  const groups = [...new Set(filtered.map((item) => item.group ?? text("可用数据")))];
  return <Popover open={open} onOpenChange={(next) => {
    setOpen(next);
    if (next) {
      setQuery("");
    }
  }}><PopoverTrigger render={<Button type="button" className={compact ? form.textAction : `${ui.button} ${form.reference}`} disabled={disabled}
    aria-label={label}/>}>{selected?.label || value || label}<IconChevronDown size={14}/></PopoverTrigger>
    <PopoverContent align="start" className={form.picker} onKeyDown={(event) => event.stopPropagation()}>
      <PopoverTitle>{text("选择数据")}</PopoverTitle>
      <div className={form.search}><IconSearch size={16}/><Input autoFocus aria-label={text("搜索输入或节点结果")} placeholder={text("搜索输入或节点结果")}
        value={query} onChange={(event) => setQuery(event.target.value)}/></div>
      <div className={form.options}>
        {groups.map((group) => <div key={group} role="group" aria-label={group}>
          <div className={form.optionGroup}>{group}</div>
          {filtered.filter((item) => (item.group ?? text("可用数据")) === group).map((item) => <Button type="button" key={item.path}
            className={form.option} aria-pressed={item.path === value} onClick={() => {
              onChange(item.path);
              setOpen(false);
            }}>{item.label}</Button>)}
        </div>)}
        {!filtered.length && <p className={ui.description}>{text("没有匹配的数据")}</p>}
      </div>
    </PopoverContent>
  </Popover>;
}
