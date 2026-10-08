"use client";

import {localizeUiMessage} from "@/lib/i18n/ui-message";

import {useT} from "@/lib/i18n/locale-provider";

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger
} from "@/components/ui/shadcn/dropdown-menu";
import {IconArchive, IconDots, IconPencil, IconStar, IconTrash} from "@/components/ui/icons";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {conversationPath} from "../api/conversation-api";
import type {Conversation, ConversationUpdate} from "../types/execution";
import ui from "@/components/ui/surface.module.css";

export function ConversationActions({enterprise, conversation, onManage, onChanged}: {
  enterprise: string;
  conversation: Conversation;
  onManage: (operation: "rename" | "delete") => void;
  onChanged: () => void
}) {
  const uiText = useT();
  const action = useFormAction();
  const update = (body: ConversationUpdate) => void action.execute(async () => {
    await action.mutation.run(conversationPath(enterprise, conversation.id), {
      method: "PATCH",
      revision: conversation.revision,
      body
    });
    onChanged();
  }, "");
  return <><DropdownMenu><DropdownMenuTrigger className="icon-button" disabled={action.busy}
                                              aria-label={uiText("对话操作：{0}", [conversation.title])}><IconDots
    size={18}/></DropdownMenuTrigger>
    <DropdownMenuContent className="agenteam-menu agenteam-popup" align="end">
      {conversation.status === "deleted" ?
        <DropdownMenuItem onClick={() => onManage("rename")}>{uiText("恢复对话")}</DropdownMenuItem> : <>
          <DropdownMenuItem onClick={() => onManage("rename")}><IconPencil size={17}/>{uiText("重命名")}
          </DropdownMenuItem>
          <DropdownMenuItem onClick={() => update({favorite: !conversation.favorite})}><IconStar
            size={17}/>{conversation.favorite ? uiText("取消收藏") : uiText("收藏")}</DropdownMenuItem>
          <DropdownMenuItem disabled={Boolean(conversation.activeRunId)}
                            onClick={() => update({status: conversation.status === "archived" ? "active" : "archived"})}><IconArchive
            size={17}/>{conversation.status === "archived" ? uiText("取消归档") : uiText("归档")}</DropdownMenuItem>
          <DropdownMenuSeparator/><DropdownMenuItem disabled={Boolean(conversation.activeRunId)} variant="destructive"
                                                    onClick={() => onManage("delete")}><IconTrash
          size={17}/>{uiText("删除")}</DropdownMenuItem>
        </>}
    </DropdownMenuContent>
  </DropdownMenu>{action.error &&
    <p className={ui.error} role="alert">{localizeUiMessage(action.error ?? "", uiText)}</p>}</>;
}
