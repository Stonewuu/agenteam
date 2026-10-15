import type {ChatBot, ChatGroup} from "../types/chat";

export function botSettings(bot: ChatBot, enabled: boolean) {
  return {expectedRevision: bot.revision, connectionId: bot.connectionId, agentId: bot.agentId,
    versionPolicy: bot.versionPolicy, pinnedVersionId: bot.versionPolicy === "pinned" ? bot.agentVersionId : null,
    roleName: bot.roleName, roleDescription: bot.roleDescription, transport: bot.transport,
    status: enabled ? "enabled" : "disabled", chatEnabled: enabled};
}

export function pausedGroupSettings(group: ChatGroup) {
  return {expectedRevision: group.revision, status: "disabled", ingressMode: group.ingressMode,
    ingressBotId: group.ingressBotId, defaultBotId: group.defaultBotId, triggerMode: group.triggerMode,
    resultPolicy: group.resultPolicy, allowedBotIds: group.bots.filter((bot) => bot.collaborationEnabled).map((bot) => bot.botId), limits: group.limits};
}
