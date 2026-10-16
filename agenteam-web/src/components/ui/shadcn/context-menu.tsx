"use client";

import {ContextMenu as Primitive} from "@base-ui/react/context-menu";
import {cn} from "cn";
import {DropdownMenuItem, DropdownMenuGroup, DropdownMenuLabel, DropdownMenuSeparator} from "./dropdown-menu";

export const ContextMenu = Primitive.Root;
export const ContextMenuTrigger = Primitive.Trigger;
export const ContextMenuItem = DropdownMenuItem;
export const ContextMenuGroup = DropdownMenuGroup;
export const ContextMenuLabel = DropdownMenuLabel;
export const ContextMenuSeparator = DropdownMenuSeparator;

export function ContextMenuContent({className, ...props}: Primitive.Popup.Props) {
  return <Primitive.Portal><Primitive.Positioner className="agenteam-positioner isolate z-50 outline-none" collisionPadding={12}>
    <Primitive.Popup className={cn("agenteam-glass min-w-56 max-h-(--available-height) overflow-y-auto rounded-xl p-2 text-popover-foreground shadow-md ring-1 ring-foreground/10 outline-none", className)} {...props}/>
  </Primitive.Positioner></Primitive.Portal>;
}
