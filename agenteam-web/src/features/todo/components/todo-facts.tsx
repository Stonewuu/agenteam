"use client";

import {useT} from "@/lib/i18n/locale-provider";
import {localizeCatalog} from "@/lib/i18n/translate";

import Link from "next/link";
import {enterprisePath} from "@/features/auth/lib/identity-navigation";
import {type Todo, todoSourceNames, todoStatusNames} from "../types/todo";
import styles from "./todo.module.css";

export function TodoFacts({enterpriseId, todo}: { enterpriseId: string; todo: Todo }) {
  const uiText = useT();
  return <dl className={styles.facts}>
    <div>
      <dt>{uiText("负责人")}</dt>
      <dd>{todo.owner.displayName}</dd>
    </div>
    <div>
      <dt>{uiText("截止日期")}</dt>
      <dd>{todo.dueDate ?? uiText("无截止日期")}</dd>
    </div>
    <div>
      <dt>{uiText("优先级")}</dt>
      <dd>{todo.priority === "high" ? uiText("高") : uiText("普通")}</dd>
    </div>
    <div>
      <dt>{uiText("状态")}</dt>
      <dd>{localizeCatalog(todoStatusNames, uiText)[todo.status]}</dd>
    </div>
    {todo.teamId && <div>
      <dt>{uiText("所属团队")}</dt>
      <dd>{todo.teamName}</dd>
    </div>}
    <div>
      <dt>{uiText("来源")}</dt>
      <dd>{localizeCatalog(todoSourceNames, uiText)[todo.sourceType]}{todo.sourceType !== "manual" && <>
        <br/>{todo.sourceAccessible && todo.sourceConversationId
        ? <Link
          href={enterprisePath(enterpriseId, `/conversations/${encodeURIComponent(todo.sourceConversationId)}`)}>{uiText("查看来源对话")}</Link> : uiText("来源内容不可访问")}</>}</dd>
    </div>
  </dl>;
}
