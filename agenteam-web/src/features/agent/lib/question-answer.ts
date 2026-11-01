import type {RunQuestion} from "../types/execution";

export const customQuestionChoice = "custom-answer";

/** 两种填写方式各自保留草稿，只发送当前可见的选择和文字。 */
export function questionAnswer(question: RunQuestion, draft: {
  choice: string | null; customText: string; note: string; noteOpen: boolean;
}): {optionId: string | null; text: string | null} | null {
  if (question.status !== "pending") {
    return null;
  }
  const custom = question.options.length === 0 || draft.choice === customQuestionChoice;
  const selected = custom ? null : question.options.find((option) => option.id === draft.choice);
  const text = (custom ? draft.customText : draft.noteOpen && selected ? draft.note : "").trim();
  if ((!selected && !custom) || (!selected && !text)) {
    return null;
  }
  return {optionId: selected?.id ?? null, text: text || null};
}
