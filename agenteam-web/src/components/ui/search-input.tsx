"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Input} from "@/components/ui/input";
import {Button} from "@/components/ui/button";

import type {InputHTMLAttributes} from "react";
import {IconSearch, IconX} from "./icons";

export function SearchInput({className = "", onClear, ...props}: InputHTMLAttributes<HTMLInputElement> & {
  onClear?: () => void
}) {
  const uiText = useT();
  return <span className={`search-input ${className}`} data-custom-clear={Boolean(onClear)}>
    <IconSearch size={18}/><Input type="search" {...props} />
    {props.value && onClear &&
      <Button type="button" className="icon-button" aria-label={uiText("清除搜索")} onClick={(event) => {
        event.currentTarget.parentElement?.querySelector("input")?.focus();
        onClear();
      }}><IconX size={16}/></Button>}
  </span>;
}
