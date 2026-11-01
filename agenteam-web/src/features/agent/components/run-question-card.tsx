"use client";

import {useId, useRef, useState} from "react";
import {Button} from "@/components/ui/button";
import {Textarea} from "@/components/ui/textarea";
import {RadioGroup, RadioGroupItem} from "@/components/ui/radio-group";
import {AnimatedHeight} from "@/components/ui/animated-height";
import {IconCheck, IconClock, IconMessages, IconPencil, IconPlus, IconRefresh, IconSend, IconSparkles} from "@/components/ui/icons";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import {useT} from "@/lib/i18n/locale-provider";
import type {RunQuestion} from "../types/execution";
import {questionAnswer, customQuestionChoice} from "../lib/question-answer";
import ui from "@/components/ui/surface.module.css";
import styles from "./run-question.module.css";

type QuestionCardProps = {enterprise: string; question: RunQuestion; onChanged?: () => void};

export function RunQuestionCard(props: QuestionCardProps) {
  return <QuestionCard key={props.enterprise + ":" + props.question.id} {...props}/>;
}

function QuestionCard({enterprise, question, onChanged}: QuestionCardProps) {
  const t = useT();
  const id = useId();
  const action = useFormAction();
  const [choice, setChoice] = useState<string | null>(null);
  const [customText, setCustomText] = useState("");
  const [note, setNote] = useState("");
  const [noteOpen, setNoteOpen] = useState(false);
  const [answered, setAnswered] = useState<RunQuestion | null>(null);
  const input = useRef<HTMLTextAreaElement>(null);
  const result = useRef<HTMLDivElement>(null);
  const current = answered && BigInt(answered.revision) > BigInt(question.revision) ? answered : question;
  const pending = current.status === "pending";
  const hasChoices = current.options.length > 0;
  const custom = !hasChoices || choice === customQuestionChoice;
  const selected = current.options.find((option) => option.id === choice);
  const showInput = custom || Boolean(selected && noteOpen);
  const body = questionAnswer(current, {choice, customText, note, noteOpen});
  const submit = () => {
    if (!body || action.busy) {
      return;
    }
    void action.execute(async () => {
      try {
        const saved = await action.mutation.run<RunQuestion>(organizationPath(enterprise,
          "/questions/" + encodeURIComponent(current.id) + "/answer"), {method: "POST", revision: current.revision, body});
        setAnswered(saved);
        requestAnimationFrame(() => result.current?.focus({preventScroll: true}));
      } finally {
        onChanged?.();
      }
    }, "");
  };

  return <section className={styles.card} aria-labelledby={id + "-title"} data-state={current.status} tabIndex={-1}
                  data-question-pending={pending ? "true" : undefined} aria-busy={action.busy}>
    <AnimatedHeight preserveControlShadows>{pending ? <form className={styles.form} onSubmit={(event) => {
      event.preventDefault();
      submit();
    }}>
      <header className={styles.header}>
        <span className={styles.headerIcon}><IconMessages size={21}/></span>
        <div className={styles.heading}><span className={styles.eyebrow}>{t(hasChoices ? "需要你的选择" : "需要你的回答")}</span>
          <h3 id={id + "-title"} className={styles.question}>{current.question}</h3></div>
      </header>

      {hasChoices && <RadioGroup value={choice ?? ""} name={id + "-choice"} disabled={action.busy}
        aria-labelledby={id + "-title"} className={styles.choices} onValueChange={(value) => {
          if (typeof value === "string") {
            setChoice(value);
            action.clearFieldError("optionId");
            action.clearFieldError("text");
          }
        }}>
        <div className={styles.optionGrid} data-paired={current.options.length === 2 || current.options.length === 4 || undefined}>
          {current.options.map((option) => <label key={option.id} htmlFor={id + "-" + option.id} className={styles.option}
              data-selected={choice === option.id || undefined} data-recommended={option.recommended || undefined}>
            <RadioGroupItem id={id + "-" + option.id} value={option.id} className={styles.radio}
              aria-labelledby={id + "-" + option.id + "-label"}
              aria-describedby={option.description ? id + "-" + option.id + "-description" : undefined}/>
            <span className={styles.optionCopy}><span className={styles.optionTitle} id={id + "-" + option.id + "-label"}>
              <span>{option.label}</span>{option.recommended && <span className={styles.recommended}><IconSparkles size={12}/>{t("推荐")}</span>}
            </span>{option.description && <span id={id + "-" + option.id + "-description"} className={styles.description}>{option.description}</span>}</span>
          </label>)}
        </div>
        <label className={styles.customChoice} data-selected={custom || undefined} htmlFor={id + "-custom"}>
          <RadioGroupItem id={id + "-custom"} value={customQuestionChoice} className={styles.radio} aria-labelledby={id + "-custom-label"}/>
          <span id={id + "-custom-label"}>{t("自己填写")}</span><span className={styles.customHint}>{t("按你的想法回答")}</span><IconPencil size={15}/>
        </label>
      </RadioGroup>}

      <AnimatedHeight preserveControlShadows>{showInput && <div className={styles.inputArea}>
        <label className={styles.inputLabel} htmlFor={id + "-answer"}>{t(custom ? "你的回答" : "补充说明（可选）")}</label>
        <Textarea ref={input} id={id + "-answer"} name="text" maxLength={8000} rows={3} className={styles.textarea}
          value={custom ? customText : note} disabled={action.busy}
          placeholder={t(custom ? "写下你的想法或具体要求…" : "还有需要助手注意的细节吗？")} onChange={(event) => {
            if (custom) {
              setCustomText(event.target.value);
            } else {
              setNote(event.target.value);
            }
            action.clearFieldError("text");
          }} onKeyDown={(event) => {
            if (event.key === "Enter" && (event.ctrlKey || event.metaKey) && !event.nativeEvent.isComposing && event.keyCode !== 229) {
              event.preventDefault();
              submit();
            }
          }}/>
      </div>}</AnimatedHeight>

      <MutationFeedback action={action} onReload={onChanged}/>
      <footer className={styles.footer}>
        {selected && <Button type="button" className={styles.noteButton} disabled={action.busy} aria-expanded={noteOpen}
          aria-controls={showInput ? id + "-answer" : undefined} onClick={() => {
            setNoteOpen(!noteOpen);
            if (!noteOpen) {
              requestAnimationFrame(() => input.current?.focus({preventScroll: true}));
            }
          }}>{noteOpen ? <IconPencil size={15}/> : <IconPlus size={15}/>}{t(noteOpen ? "取消补充" : "补充说明")}</Button>}
        {!selected && <span className={styles.footerHint}>{t(hasChoices && !custom ? "选一项，或自己填写" : "提交后继续当前任务")}</span>}
        <Button type="submit" className={ui.primary + " " + styles.submit} disabled={action.busy || !body}>
          {action.busy ? <IconRefresh size={16} className={styles.spinner}/> : <IconSend size={16}/>}
          {t(action.busy ? "正在发送…" : "发送回答")}
        </Button>
      </footer>
    </form> : <div className={styles.result} ref={result} tabIndex={-1}>
      <span className={styles.resultIcon}>{current.status === "answered" ? <IconCheck size={20}/> : <IconClock size={20}/>}</span>
      <div className={styles.resultCopy}><span className={styles.resultStatus} role="status">{t(current.status === "answered" ? "已回答" : current.status === "expired" ? "提问已过期" : "提问已结束")}</span>
        <h3 id={id + "-title"} className={styles.resultQuestion}>{current.question}</h3>
        {current.answer && <p className={styles.answer}>{current.answer}</p>}
      </div>
    </div>}</AnimatedHeight>
  </section>;
}
