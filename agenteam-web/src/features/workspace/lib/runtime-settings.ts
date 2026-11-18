import {IconPlayerPlay, IconRobot, IconCollaboration, IconTerminal, IconArchive} from "@/components/ui/icons";
import type {RuntimeSection} from "../types/runtime-settings";

export const runtimeFields: Record<string, {label: string; hint: string}> = {
  workerConcurrent: {label: "同时执行任务数", hint: "每个后端实例同时执行的任务数量。"},
  queueTimeoutSeconds: {label: "最长排队时间（秒）", hint: "超过时间仍未开始的任务将停止等待。"},
  enterpriseActiveRuns: {label: "每个企业的活动任务上限", hint: "包括排队、执行、等待确认或回答及取消中的任务。"},
  userActiveRuns: {label: "每位用户的活动任务上限", hint: "按用户在同一企业中的任务数量计算。"},
  conversationQueuedMessages: {label: "每个对话的等待消息上限", hint: "当前任务执行期间，可追加的消息数量。"},
  modelIdleSeconds: {label: "默认模型无输出超时（秒）", hint: "模型请求未单独设置等待时间时使用。"},
  modelInputMb: {label: "模型输入容量（MB）", hint: "填 0 按后端可用内存自动确定。"},
  toolInlineKb: {label: "直接返回模型的内容（KB）", hint: "超过此大小的部分由模型按需继续读取。"},
  toolLargeInlineKb: {label: "长上下文模型的返回内容（KB）", hint: "适用于上下文长度不少于一百万的模型。"},
  toolPreviewKb: {label: "历史工具结果摘要（KB）", hint: "模型上下文中较早工具结果保留的摘要大小。"},
  toolReadKb: {label: "每次读取内容（KB）", hint: "每次读取文件内容的大小上限。"},
  toolReadLines: {label: "每次读取行数", hint: "与读取内容大小共同限制单次读取。"},
  toolBatchMultiplier: {label: "批量工具结果倍数", hint: "批量返回内容上限相对于单次返回上限的倍数。"},
  toolFileMb: {label: "完整工具结果上限（MB）", hint: "单次工具调用完整结果的大小上限。"},
  collaborationWorks: {label: "默认工作数量", hint: "新建协作任务默认允许的工作数量。"},
  collaborationHandoffs: {label: "默认交接次数", hint: "填 0 不允许继续交接工作。"},
  collaborationRuns: {label: "默认执行次数", hint: "一项协作任务默认允许发起的执行次数。"},
  collaborationParallel: {label: "默认同时工作数量", hint: "同一协作任务同时推进的工作数量。"},
  collaborationDeadlineHours: {label: "默认任务时限（小时）", hint: "用于新建协作任务，已有任务保留创建时的时限。"},
  collaborationContextCharacters: {label: "共享上下文字符数", hint: "每次执行可带入的协作内容长度。"},
  collaborationContextMessages: {label: "共享上下文消息数", hint: "每次执行可带入的近期协作消息数量。"},
  sandboxMemoryMb: {label: "每个环境的内存（MB）", hint: "每个运行环境可使用的内存上限。"},
  sandboxCpus: {label: "每个环境的处理器核数", hint: "每个运行环境可使用的处理器核数。"},
  sandboxProcesses: {label: "每个环境的进程数", hint: "每个运行环境可同时启动的进程数量。"},
  sandboxTemporaryMb: {label: "临时目录容量（MB）", hint: "运行环境中临时目录的容量上限。"},
  sandboxTimeoutSeconds: {label: "命令执行超时（秒）", hint: "单次命令或文件操作的最长执行时间。"},
  sandboxConcurrent: {label: "每站命令并发数", hint: "多个站点共用服务器时，还受服务器总并发上限约束。"},
  sandboxRunning: {label: "同时运行环境数", hint: "达到上限后，需停止部分环境或等待空闲回收。"},
  sandboxIdleSeconds: {label: "环境空闲回收时间（秒）", hint: "空闲环境达到此时间后停止，工作文件保留。"},
  commandRetentionDays: {label: "命令输出保留天数", hint: "执行中或仍被活动任务使用的输出会继续保留。"},
  commandMaximumEntries: {label: "每位用户的命令输出数量", hint: "与工作空间设置中的命令输出容量共同生效。"},
  workspaceRetentionDays: {label: "临时工作文件保留天数", hint: "清理未使用的临时执行文件，持久项目文件保留。"},
  authRetentionDays: {label: "已结束授权记录保留天数", hint: "已结束的渠道授权过程记录。"},
  payloadRetentionDays: {label: "通知发送内容保留天数", hint: "已过期通知的发送内容。"},
  attemptRetentionDays: {label: "通知发送尝试保留天数", hint: "已结束的通知发送尝试记录。"},
  resultRetentionDays: {label: "通知发送结果保留天数", hint: "已过期通知的最终发送结果，至少保留 180 天。"},
};

export const runtimeSections: {id: RuntimeSection; label: string; description: string; note: string;
  icon: typeof IconPlayerPlay; groups: {title: string; keys: string[]}[]}[] = [
  {id: "execution", label: "任务执行", description: "调整任务并发、活动任务数量和排队时间。", note: "并发数降低后，正在执行的任务继续完成；排队时间对当前等待中的任务也生效。", icon: IconPlayerPlay,
    groups: [{"title":"并发与排队","keys":["workerConcurrent","queueTimeoutSeconds","enterpriseActiveRuns","userActiveRuns","conversationQueuedMessages"]}]
  },
  {id: "model", label: "模型与工具", description: "设置模型等待时间和工具结果的内容大小。", note: "保存后用于后续模型请求和工具操作。", icon: IconRobot,
    groups: [{"title":"模型请求","keys":["modelIdleSeconds","modelInputMb"]},{"title":"工具结果","keys":["toolInlineKb","toolLargeInlineKb","toolPreviewKb","toolReadKb","toolReadLines","toolBatchMultiplier","toolFileMb"]}]
  },
  {id: "collaboration", label: "协作任务", description: "设置新建协作任务的默认限制和共享上下文大小。", note: "任务默认值用于新建任务，共享上下文设置用于后续执行。", icon: IconCollaboration,
    groups: [{"title":"新任务默认值","keys":["collaborationWorks","collaborationHandoffs","collaborationRuns","collaborationParallel","collaborationDeadlineHours"]},{"title":"共享上下文","keys":["collaborationContextCharacters","collaborationContextMessages"]}]
  },
  {id: "environment", label: "运行环境", description: "分配运行环境资源，管理命令并发和空闲回收。", note: "内存、处理器、进程数和临时目录容量在新建环境时生效。已有环境需在「我的环境」停止并重新连接。", icon: IconTerminal,
    groups: [{"title":"环境资源","keys":["sandboxMemoryMb","sandboxCpus","sandboxProcesses","sandboxTemporaryMb"]},{"title":"执行与回收","keys":["sandboxTimeoutSeconds","sandboxConcurrent","sandboxRunning","sandboxIdleSeconds"]}]
  },
  {id: "retention", label: "记录保留", description: "管理临时文件、命令输出和通知记录的保留时间。", note: "缩短保留时间后，超期记录会在后续清理中删除，删除后无法恢复。", icon: IconArchive,
    groups: [{"title":"文件与命令输出","keys":["commandRetentionDays","commandMaximumEntries","workspaceRetentionDays"]},{"title":"授权与通知记录","keys":["authRetentionDays","payloadRetentionDays","attemptRetentionDays","resultRetentionDays"]}]
  },
];
