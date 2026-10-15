import type {ApiMutation} from "@/lib/http/api-client";
import type {ChatBot} from "../types/chat";

/** 每步成功即记录结果；启用失败重试时继续已有机器人，避免重复创建。 */
export async function saveChatBot(mutation: ApiMutation, path: string, initial: ChatBot | null,
  progress: {current: ChatBot | null}, fields: Record<string, unknown>,
  callback: {verificationToken: string; encryptKey: string} | null, onCallbackSaved: () => void) {
  let bot = progress.current ?? initial;
  if (!bot) {
    bot = await mutation.run<ChatBot>(`${path}/bots`, {method: "POST", body: fields});
    progress.current = bot;
  }
  if (callback) {
    bot = await mutation.run<ChatBot>(`${path}/bots/${bot.id}/callback-settings`, {method: "POST",
      body: {expectedRevision: bot.revision, ...callback}});
    progress.current = bot;
    onCallbackSaved();
  }
  bot = await mutation.run<ChatBot>(`${path}/bots/${bot.id}`, {method: "PATCH", body: {...fields,
    expectedRevision: bot.revision, status: initial ? initial.status : "enabled", chatEnabled: initial ? initial.chatEnabled : true}});
  progress.current = bot;
  return bot;
}
