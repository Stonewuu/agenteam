"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";

import {useState} from "react";
import type {ResourceSummary} from "../types/resource";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger
} from "@/components/ui/shadcn/dropdown-menu";
import {IconCopy, IconDots, IconUpload} from "@/components/ui/icons";
import {ResourceAccessDialog} from "./resource-access-dialog";
import {ResourceLifecycleDialog} from "./resource-lifecycle-dialog";
import {ListingDialog, ResourcePublishDialog} from "./resource-publish-dialog";
import {ResourceCopyDialog} from "./resource-copy-dialog";
import ui from "@/components/ui/surface.module.css";

export function ResourceActions({
                                  enterpriseId,
                                  resource,
                                  onChanged,
                                  dirty = false,
                                  compact = false,
                                  quickCopy = false
                                }: {
  enterpriseId: string;
  resource: ResourceSummary;
  onChanged: () => void;
  dirty?: boolean;
  compact?: boolean;
  quickCopy?: boolean;
}) {
  const uiText = useT();
  const [operation, setOperation] = useState<string | null>(null);
  const available = resource.allowedActions.filter((action) => ["publish", "copy", "grants", "transfer", "listing", "status", "delete", "restore"].includes(action));
  const labels: Record<string, string> = {
    publish: uiText("发布"),
    copy: uiText("复制"),
    grants: uiText("管理使用范围"),
    transfer: uiText("转交"),
    listing: uiText("广场与雇佣"),
    status: resource.status === "disabled" ? uiText("启用") : uiText("停用"),
    delete: uiText("删除"),
    restore: uiText("恢复")
  };
  const common = {enterpriseId, resource, onChanged, onClose: () => setOperation(null)};
  return <>
    <div className={ui.actions}>
      {!compact && available.includes("publish") &&
        <Button className={ui.primary} type="button" disabled={dirty} title={dirty ? uiText("请先保存草稿") : undefined}
                onClick={() => setOperation("publish")}><IconUpload size={16}/>{uiText("发布")}</Button>}
      {quickCopy && available.includes("copy") &&
        <Button className="icon-button" type="button" aria-label={uiText("复制{0}", [resource.name])}
                onClick={() => setOperation("copy")}><IconCopy size={17}/></Button>}
      {available.length > 0 &&
        <DropdownMenu><DropdownMenuTrigger className="icon-button" aria-label={uiText("{0}的更多操作", [resource.name])}
                                           disabled={dirty}><IconDots size={19}/></DropdownMenuTrigger>
          <DropdownMenuContent align="end"
                               className="agenteam-menu agenteam-popup">{available.filter((value) => compact || value !== "publish").map((value) =>
            <DropdownMenuItem key={value} variant={value === "delete" ? "destructive" : "default"}
                              onClick={() => setOperation(value)}>{labels[value]}</DropdownMenuItem>)}</DropdownMenuContent>
        </DropdownMenu>}
    </div>
    {(operation === "grants" || operation === "transfer") && <ResourceAccessDialog {...common} mode={operation}/>}
    {(operation === "status" || operation === "delete" || operation === "restore") &&
      <ResourceLifecycleDialog {...common} operation={operation}/>}
    {operation === "publish" && <ResourcePublishDialog {...common} />}
    {operation === "listing" && <ListingDialog {...common} />}
    {operation === "copy" && <ResourceCopyDialog {...common} />}
  </>;
}
