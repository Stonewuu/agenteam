"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {useRef} from "react";
import {Button} from "@/components/ui/button";
import {IconBook2, IconPaperclip, IconPlus, IconSparkles} from "@/components/ui/icons";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger
} from "@/components/ui/shadcn/dropdown-menu";
import {FileUploadPicker} from "@/features/file/components/file-upload-picker";
import type {useFileUploads} from "@/features/file/hooks/use-file-uploads";
import type {ComposerSelectionContext} from "../lib/composer-selection";
import {ComposerTransition} from "./composer-transition";
import styles from "./composer-extra-tools.module.css";

/** 首页与对话统一使用加号菜单，文件选择器始终保持挂载。 */
export function ComposerExtraTools({
                                     files,
                                     attachmentsEnabled,
                                     canChooseSkills,
                                     canChooseDocuments,
                                     skillsLoading,
                                     loading = false,
                                     disabled,
                                     onChooseSkills,
                                     onChooseDocuments
                                   }: {
  files: ReturnType<typeof useFileUploads>;
  attachmentsEnabled: boolean;
  canChooseSkills: boolean;
  canChooseDocuments: boolean;
  skillsLoading: boolean;
  loading?: boolean;
  disabled: boolean;
  onChooseSkills: (context?: ComposerSelectionContext) => void;
  onChooseDocuments: (context?: ComposerSelectionContext) => void;
}) {
  const uiText = useT();
  const attachmentInput = useRef<HTMLInputElement>(null);
  const moreTools = useRef<HTMLButtonElement>(null);
  if (!attachmentsEnabled && !canChooseSkills && !canChooseDocuments && !loading) {
    return null;
  }
  return <ComposerTransition part="tools"><span className={styles.tools} data-composer-part="tools">
    {attachmentsEnabled &&
      <span hidden><FileUploadPicker inputRef={attachmentInput} uploads={files} purpose="attachment" disabled={disabled}
                                     compact iconOnly showFiles={false} label={uiText("添加附件")}/></span>}
    <DropdownMenu>
      <DropdownMenuTrigger
        render={<Button ref={moreTools} type="button" className="icon-button" aria-label={uiText("添加内容")}
                        title={uiText("添加附件、技能或知识库资料")} disabled={disabled || loading}><IconPlus size={20}/></Button>}/>
      <DropdownMenuContent className={styles.menu} side="top" align="start" sideOffset={8}>
        {attachmentsEnabled && <DropdownMenuItem onClick={() => attachmentInput.current?.click()}><IconPaperclip
          size={18}/>{uiText("添加附件")}</DropdownMenuItem>}
        {canChooseSkills &&
          <DropdownMenuItem disabled={skillsLoading} onClick={() => onChooseSkills({anchor: moreTools})}><IconSparkles
            size={18}/>{skillsLoading ? uiText("正在读取技能") : uiText("选择技能")}</DropdownMenuItem>}
        {canChooseDocuments && <DropdownMenuItem onClick={() => onChooseDocuments({anchor: moreTools})}><IconBook2
          size={18}/>{uiText("知识库")}</DropdownMenuItem>}
      </DropdownMenuContent>
    </DropdownMenu>
  </span></ComposerTransition>;
}
