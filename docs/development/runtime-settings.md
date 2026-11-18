# 运行设置

系统超级管理员可以在“管理端 → 平台管理 → 运行设置”调整任务执行、模型与工具、协作任务、运行环境和记录保留，共 36 项。设置保存后持续生效，应用重启后保留。工作文件容量、单文件大小、文件数量和命令输出容量仍在“工作空间设置”维护。

每个分类独立保存和取消修改。切换分类会保留草稿，离开页面前会提示未保存的修改。其他管理员已经保存新设置时，页面要求重新加载，避免覆盖新内容。

所有运行限制和工作空间容量都支持“不限”，接口与保存值统一为 `-1`，不再设置 32、64 等业务最大值。关闭“不限”可恢复本次编辑的原数值。数值仍需满足字段最小值和整数类型范围；达到机器资源容量时，执行结果由操作系统和运行环境决定。

`0` 保留已有含义：模型输入容量为自动计算，协作交接次数为禁止交接。智能体旧配置中的步骤数和执行时间 `0` 在读取时按不限处理，新保存使用 `-1`。价格规则的输入上界使用 `-1`，仍能读取旧空值。内部计时、数据库日期和 Docker 参数在调用时转换为各自支持的不限表示。

## 生效时机

- 任务并发数在下一轮任务检查时应用。提高上限可领取更多任务；降低上限不取消已经执行的任务，待活动数量降到上限以下后再领取。并发数以单个后端实例计算，同一实例的用户和企业共用执行名额。
- 企业和用户活动任务数量在提交时检查，活动任务包括排队、执行中、等待确认、等待回答和取消中的任务。同会话等待消息数量在追加消息时检查。
- 排队超时也适用于已经等待的任务。保存更短的时限后，超过新时限的排队任务可能在下一轮检查时失败。
- 模型与工具设置在后续请求或操作中读取。模型输入容量为 0 时沿用按后端内存计算的容量；请求单独指定的等待时间继续优先。
- 协作任务的工作数量、交接次数、执行次数、并行数量和时限作为新任务默认值；创建时已保存的任务限制不变。上下文大小在后续执行时读取。
- 环境内存、处理器、进程数和临时目录容量用于新建环境，已有环境需停止并重新连接。降低运行环境数量不主动停止正在使用的环境。配置共享执行目录时，同主机服务按所设并发数量领取名额，不再固定为一个名额。
- 保留时间在后续清理时读取。缩短保留时间会清理超期记录，删除后无法恢复；活动任务仍在使用的输出和持久项目文件遵循原有保留规则。

## 配置字段

未保存某个项目时，继续使用部署配置。下表列出后台字段与部署属性的对应关系，单位与界面一致。

| 分类 | 设置 | 接口字段 | 部署属性 |
| --- | --- | --- | --- |
| 任务执行 | 同时执行任务数 | `workerConcurrent` | `execution.worker.maximum-concurrent` |
| 任务执行 | 最长排队时间（秒） | `queueTimeoutSeconds` | `execution.queue-timeout-seconds` |
| 任务执行 | 每个企业的活动任务上限 | `enterpriseActiveRuns` | `execution.enterprise-active-runs` |
| 任务执行 | 每位用户的活动任务上限 | `userActiveRuns` | `execution.user-active-runs` |
| 任务执行 | 每个对话的等待消息上限 | `conversationQueuedMessages` | `execution.conversation-queued-messages` |
| 模型与工具 | 默认模型无输出超时（秒） | `modelIdleSeconds` | `execution.model.idle-timeout` |
| 模型与工具 | 模型输入容量（MB） | `modelInputMb` | `execution.model.maximum-input-bytes` |
| 模型与工具 | 直接返回模型的内容（KB） | `toolInlineKb` | `tools.results.inline-kb` |
| 模型与工具 | 长上下文模型的返回内容（KB） | `toolLargeInlineKb` | `tools.results.large-context-inline-kb` |
| 模型与工具 | 历史工具结果摘要（KB） | `toolPreviewKb` | `tools.results.preview-kb` |
| 模型与工具 | 每次读取内容（KB） | `toolReadKb` | `tools.results.read-kb` |
| 模型与工具 | 每次读取行数 | `toolReadLines` | `tools.results.read-lines` |
| 模型与工具 | 批量工具结果倍数 | `toolBatchMultiplier` | `tools.results.batch-multiplier` |
| 模型与工具 | 完整工具结果上限（MB） | `toolFileMb` | `tools.results.max-file-mb` |
| 协作任务 | 默认工作数量 | `collaborationWorks` | `collaboration.max-works` |
| 协作任务 | 默认交接次数 | `collaborationHandoffs` | `collaboration.max-handoffs` |
| 协作任务 | 默认执行次数 | `collaborationRuns` | `collaboration.max-runs` |
| 协作任务 | 默认同时工作数量 | `collaborationParallel` | `collaboration.max-parallel` |
| 协作任务 | 默认任务时限（小时） | `collaborationDeadlineHours` | `collaboration.deadline-hours` |
| 协作任务 | 共享上下文字符数 | `collaborationContextCharacters` | `collaboration.max-context-characters` |
| 协作任务 | 共享上下文消息数 | `collaborationContextMessages` | `collaboration.max-context-messages` |
| 运行环境 | 每个环境的内存（MB） | `sandboxMemoryMb` | `execution.sandbox.memory-mb` |
| 运行环境 | 每个环境的处理器核数 | `sandboxCpus` | `execution.sandbox.cpus` |
| 运行环境 | 每个环境的进程数 | `sandboxProcesses` | `execution.sandbox.process-limit` |
| 运行环境 | 临时目录容量（MB） | `sandboxTemporaryMb` | `execution.sandbox.temporary-mb` |
| 运行环境 | 命令执行超时（秒） | `sandboxTimeoutSeconds` | `execution.sandbox.timeout-seconds` |
| 运行环境 | 每站命令并发数 | `sandboxConcurrent` | `execution.sandbox.maximum-concurrent` |
| 运行环境 | 同时运行环境数 | `sandboxRunning` | `execution.sandbox.maximum-running-containers` |
| 运行环境 | 环境空闲回收时间（秒） | `sandboxIdleSeconds` | `execution.sandbox.idle-seconds` |
| 记录保留 | 命令输出保留天数 | `commandRetentionDays` | `execution.command-results.retention-days` |
| 记录保留 | 每位用户的命令输出数量 | `commandMaximumEntries` | `execution.command-results.maximum-entries` |
| 记录保留 | 临时工作文件保留天数 | `workspaceRetentionDays` | `execution.workspace.retention-days` |
| 记录保留 | 已结束授权记录保留天数 | `authRetentionDays` | `agenteam.integration.retention.oauth-days` |
| 记录保留 | 通知发送内容保留天数 | `payloadRetentionDays` | `agenteam.integration.retention.payload-days` |
| 记录保留 | 通知发送尝试保留天数 | `attemptRetentionDays` | `agenteam.integration.retention.attempt-days` |
| 记录保留 | 通知发送结果保留天数 | `resultRetentionDays` | `agenteam.integration.retention.result-days` |

`modelIdleSeconds` 使用秒，部署属性接受原有持续时间格式；`modelInputMb` 使用 MB（每单位 1048576 字节），部署属性使用字节。工具的 KB 按 1024 字节计算。

## 保存与部署

运行设置保存在用户工作空间根目录的 `.platform-settings/runtime-settings.json`。后端和独立执行服务必须继续挂载同一工作空间根目录；该设置目录不挂载到用户环境。备份工作空间根目录时同时保留此文件。管理员只保存改动字段，未保存字段继续沿用各服务的部署初始值。

`GET /api/v1/system/runtime-settings` 返回当前值、字段范围和 `revision`（本次读取的修改版本）。`PUT /api/v1/system/runtime-settings/{section}` 仅接收指定分类的改动字段，正文为 `{"values":{"workerConcurrent":4}}`。保存时通过 `If-Match` 请求头提交读取时的修改版本；旧版本返回 409，新值不会覆盖其他管理员已经保存的内容。未登录返回 401，无系统管理权限返回 403，字段无效返回 422。

数据库和缓存地址、存储路径及存储后端、密钥、外部服务凭据、内网访问范围、底层调度间隔及恢复模式开关仍由部署维护。这些配置涉及服务连接、数据迁移或独立主机范围，不能仅通过保存此页面完成变更。
