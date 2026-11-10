"use client";

import type {RefObject} from "react";
import {Button} from "@/components/ui/button";
import {ResourceAvatar} from "@/components/ui/resource-avatar";
import {IconChevronDown, IconUsers} from "@/components/ui/icons";
import {SelectionAction, SelectionSurface} from "@/components/ui/selection-surface";
import type {ComposerSelectionContext} from "@/features/agent/lib/composer-selection";
import {useT} from "@/lib/i18n/locale-provider";
import type {RoomMember} from "../types/web-room";
import picker from "@/features/employee/components/employee-picker-button.module.css";
import styles from "./web-room.module.css";

export function WebRoomMembers({members, recipient, context, onOpen, onClose, onPick, input, disabled}: {
  members: RoomMember[]; recipient?: RoomMember; context: ComposerSelectionContext | null; input: RefObject<HTMLTextAreaElement | null>;
  onOpen: (context: ComposerSelectionContext) => void; onClose: () => void; onPick: (member: RoomMember) => void; disabled: boolean;
}) {
  const t = useT();
  const query = context?.query?.trim().toLocaleLowerCase() ?? "";
  const matches = members.filter(member => member.name.toLocaleLowerCase().includes(query));
  return <>
    <Button type="button" className={picker.trigger} disabled={disabled} aria-haspopup="dialog" aria-expanded={Boolean(context)}
      aria-label={recipient ? t("选择发言成员，当前为{0}", [recipient.name]) : t("@ 选择成员")}
      onClick={event => onOpen({anchor: event.currentTarget})}><span className={picker.content}>
        <span className={styles.mentionSign} aria-hidden="true">@</span><span className={picker.name}>{recipient?.name ?? t("选择成员")}</span><IconChevronDown size={14}/>
      </span></Button>
    {context && <SelectionSurface title={t("选择发言成员")} anchor={context.anchor} returnFocus={input} onClose={onClose}>
      <div className={styles.mentionOptions}>{matches.map(member =>
        <SelectionAction key={member.id} className={styles.mentionOption} data-selection-option
          onAction={close => close(() => onPick(member))}>
          {member.icon ? <ResourceAvatar icon={member.icon} color={member.color ?? undefined} size="small"/> : <IconUsers size={18}/>}
          <span>{member.name}</span>
        </SelectionAction>)}
        {!matches.length && <p className={styles.noMatch}>{t("没有匹配的对话成员。")}</p>}
      </div>
    </SelectionSurface>}
  </>;
}
