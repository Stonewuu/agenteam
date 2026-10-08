"use client";

import {useT} from "@/lib/i18n/locale-provider";
import {localizeCatalog} from "@/lib/i18n/translate";

import {Button} from "@/components/ui/button";
import {Checkbox} from "@/components/ui/checkbox";
import {CollectionTable, type CollectionView} from "@/components/ui/collection-view";
import {IconCalendar, IconChevronRight} from "@/components/ui/icons";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import {type Todo, todoStatusNames} from "../types/todo";
import styles from "./todo-collection.module.css";

export function TodoCollection({view, todos, showOwner, enterpriseId, onOpen, onChanged}: {
  view: CollectionView;
  todos: Todo[];
  showOwner: boolean;
  enterpriseId: string;
  onOpen: (id: string) => void;
  onChanged: (message?: string) => void;
}) {
  const uiText = useT();
  const action = useFormAction();

  function complete(todo: Todo) {
    const completed = todo.status === "completed";
    if (action.busy || !todo.allowedActions.includes(completed ? "reopen" : "complete")) {
      return;
    }
    void action.execute(async () => {
      await action.mutation.run(organizationPath(enterpriseId, `/todos/${encodeURIComponent(todo.id)}/status`), {
        method: "PATCH", revision: todo.revision, body: {status: completed ? "pending" : "completed", reason: ""},
      });
      onChanged(completed ? uiText("待办已重新打开。") : uiText("待办已完成。"));
    }, "");
  }

  function completion(todo: Todo) {
    const completed = todo.status === "completed";
    return <Checkbox className={styles.completeButton} checked={completed}
                     aria-label={`${completed ? uiText("重新打开") : uiText("完成")}${todo.title}`}
                     disabled={action.busy || !todo.allowedActions.includes(completed ? "reopen" : "complete")}
                     onCheckedChange={() => complete(todo)}/>;
  }

  function title(todo: Todo) {
    return <Button type="button" className={styles.title} onClick={() => onOpen(todo.id)}><strong>{todo.title}</strong></Button>;
  }

  function details(todo: Todo) {
    return <Button type="button" className="icon-button" aria-label={uiText("查看{0}的详情", [todo.title])}
                   onClick={() => onOpen(todo.id)}><IconChevronRight size={18}/></Button>;
  }

  return <>
    {view === "grid" ? <div className={styles.grid}>{todos.map((todo) => <article className={styles.card} key={todo.id}
                                                                                  data-completed={todo.status === "completed"}>
      <header className={styles.heading}>{completion(todo)}{title(todo)}</header>
      <div className={styles.statusLine}><span className={styles.status}
                                               data-state={todo.status}>{localizeCatalog(todoStatusNames, uiText)[todo.status]}</span><TodoPriority
        todo={todo}/></div>
      {todo.description && <p className={styles.description}>{todo.description}</p>}
      {showOwner && <p className={styles.owner}>{todo.owner.displayName}{todo.teamName && ` · ${todo.teamName}`}</p>}
      <footer className={styles.footer}><TodoDueDate todo={todo}/>{details(todo)}</footer>
    </article>)}</div> : <CollectionTable label={uiText("待办列表")} className={styles.table}>
      <thead>
      <tr>
        <th scope="col" className={styles.completeColumn}>{uiText("完成")}</th>
        <th scope="col">{uiText("待办事项")}</th>
        <th scope="col">{uiText("状态")}</th>
        <th scope="col">{uiText("优先级")}</th>
        {showOwner && <th scope="col">{uiText("负责人 / 团队")}</th>}
        <th scope="col">{uiText("截止日期")}</th>
        <th scope="col">{uiText("操作")}</th>
      </tr>
      </thead>
      <tbody>{todos.map((todo) => <tr key={todo.id} data-completed={todo.status === "completed"}>
        <td>{completion(todo)}</td>
        <td className={styles.nameCell}>{title(todo)}{todo.description &&
          <p className={styles.description}>{todo.description}</p>}</td>
        <td><span className={styles.status}
                  data-state={todo.status}>{localizeCatalog(todoStatusNames, uiText)[todo.status]}</span></td>
        <td><TodoPriority todo={todo}/></td>
        {showOwner &&
          <td>{todo.owner.displayName}{todo.teamName && <small className={styles.team}>{todo.teamName}</small>}</td>}
        <td><TodoDueDate todo={todo}/></td>
        <td>{details(todo)}</td>
      </tr>)}</tbody>
    </CollectionTable>}
    {action.error && <MutationFeedback action={action} onReload={() => onChanged()}/>}
  </>;
}

function TodoPriority({todo}: { todo: Todo }) {
  const uiText = useT();
  return <span
    className={`badge ${todo.priority === "high" ? "amber" : "neutral"}`}>{todo.priority === "high" ? uiText("高优先级") : uiText("普通")}</span>;
}

function TodoDueDate({todo}: { todo: Todo }) {
  const uiText = useT();
  return <time className={styles.date} dateTime={todo.dueDate ?? undefined}><IconCalendar
    size={14}/>{todo.dueDate ?? uiText("无截止日期")}</time>;
}
