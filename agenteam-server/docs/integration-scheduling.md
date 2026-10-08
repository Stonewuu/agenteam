# 企业渠道与定时操作开发说明（0.1.1）

原定时计划必须启动智能体，原通知只保存在站内，无法按用户绑定的企业账号发送。本次把“何时执行”“执行什么”“通过哪个渠道发给谁”分开处理：调度器保存本次固定参数，操作处理器创建业务记录和后台工作，渠道适配器负责官方接口差异。通知无需模型，旧智能体计划继续使用原执行器。

页面与部署操作见[企业消息配置说明](../../docs/deployment/enterprise-messaging.md)。本文描述最终实现；早期方案里的接口草案以这里和实际契约为准。

## 1. 实现位置与职责

以下源码均位于 `src/main/java/com/stonewu/agenteam/`，继续按职责层和业务模块分包。

| 位置/类 | 职责 |
| --- | --- |
| `service/integration/IntegrationProviderRegistry` | 注册实际渠道，发现应用校验、身份授权和发送能力；重复代码启动失败 |
| `IntegrationProvider`、`ChannelIdentityProvider`、`ChannelMessageSender` | 分别定义应用配置及凭证、身份授权、个人消息渲染及发送 |
| `service/integration/provider/{wecom,feishu}` | 两个平台的固定地址、字段、编码、错误判定及限流规则 |
| `IntegrationManagementPolicy/Service`、`IntegrationCheckService` | 企业管理员与超级管理员入口、加密配置、事务外校验及版本检查 |
| `ChannelAuthorizationService/Policy`、`ChannelOauthStore`、`ChannelBindingService` | 一次性授权、浏览器与本地账号关联、明确确认绑定、历史身份撤销 |
| `service/auth/ChannelSessionGuard`、`configuration/auth/ChannelSessionRequestFilter` | 每次请求检查外部会话及企业范围，全局操作要求本地重新认证 |
| `service/notification/NotificationWriteService`、`ChannelNotificationRouting` | 同一事务内保存站内通知、固定渠道请求和后续工作；自动偏好与显式定时选择分别处理 |
| `ChannelDeliveryWorker/Transactions/Store/Policy` | 领取工作、在事务外请求平台、按当前租约保存结果、发送前复核资格及恢复 |
| `IntegrationTokenService`、`ChannelRateLimiter` | 加密凭证缓存、合并并发刷新、共享限流 |
| `service/schedule/ScheduleActionHandler/Registry` | 按正式操作代码及参数版本选择处理器 |
| `service/schedule/action/AgentRunActionHandler` | 使用原智能体提交、配额和生命周期 |
| `NotificationSendActionHandler`、`NotificationScheduleResultService/DetailService` | 逐人创建通知、保留未写通知的阻止结果、汇总及查询 |
| `ScheduleTriggerService`、`ScheduleActionWorker/Transactions/Store` | 到期或手动触发、保存固定快照、持久准备工作、最后检查租约并提交 |
| `ScheduleOccurrenceCancellation`、`ScheduledNotificationRetryService` | 停止当前操作、人工重试时重新占用原发生记录 |

接口边界使用普通 `Map`（键值映射）和响应记录。业务内部仍使用 Jackson 2 的 `JsonNode`（结构化数据节点）；Spring Boot 4 的请求转换使用 Jackson 3，不能把两者的节点对象直接混用，否则可能产生错误的字段序列化。

## 2. 实际数据库结构

字段类型、默认值、外键、索引和检查约束以三个不可改写的迁移文件为准：

- [接入与绑定](../src/main/resources/db/schema/V0.1.0.1__新增企业渠道与用户绑定.sql)。
- [通知渠道记录](../src/main/resources/db/schema/V0.1.1.1__新增通知渠道发送记录.sql)。
- [通用定时操作](../src/main/resources/db/schema/V0.1.1.2__扩展定时操作及通知接收人.sql)。

新增八张表。除偏好表使用复合主键外，主键均为 `varchar(100)`；业务关联带 `enterprise_id`（企业编号），不能仅按外部用户编号跨企业关联。

| 表 | 关键字段及类型 | 必须保持的约束 |
| --- | --- | --- |
| `enterprise_integration` | `provider_code varchar(32)`、`external_tenant_id/external_app_id varchar(191)`、三项能力开关、`config_json json`、`revision/credential_revision bigint` | 全局公开登录键唯一；平台、区域、外部企业、应用和删除标识唯一；启用前必须验证企业身份；平台标识区分大小写 |
| `enterprise_integration_secret` | `connection_id`、`secret_name varchar(32)`、`encrypted_value json`、`revision bigint` | 企业、应用和秘密用途唯一；密文绑定企业、接入及用途；查询响应不返回密钥 |
| `user_channel_binding` | 本地用户、外部身份类型与编号、`status`、接收/登录开关、`revision` | 未解绑的本地用户及外部身份分别在同一接入中唯一；停用仍占唯一位置；解绑后新建记录，不修改历史身份列 |
| `channel_oauth_session` | 绑定/登录用途、发起用户与会话版本、`state_hash/browser_nonce_hash/confirmation_token_hash char(64)`、短期加密材料、过期时间 | 摘要唯一；绑定必须有本地发起者；五分钟总有效期、确认最多一分钟；一次消费且完成后清除加密材料 |
| `user_channel_preference` | 企业、用户、接入、`category varchar(32)`、`enabled`、`revision` | 四列复合主键；缺少记录表示未选择外部自动通知；只接受已注册业务类别 |
| `scheduled_notification_target` | 计划、接收用户、可空接入、生成列 `channel_key` | 企业、计划、接收人和渠道唯一；空接入对应站内；成员、计划、接入均有企业范围外键 |
| `notification_delivery` | 通知、接收人、接入、固定绑定/凭据版本、请求编号与内容摘要、状态、次数、有效期、当前工作、修订版本 | 每通知每接入唯一；请求编号唯一；待发送必须有绑定、固定内容和工作；被接受必须有平台接受时间 |
| `notification_delivery_attempt` | 发送、递增次数、工作及租约版本、结果、实际网络状态、开始/结束时间 | 每发送每次数唯一；追加保存每次尝试；开始状态没有结束时间，其余状态必须有结束时间 |

现有四张表的扩展：

| 表 | 改动 |
| --- | --- |
| `scheduled_task` | 新增 `action_type varchar(64)`、`action_schema_version int`、`action_config_json json`；智能体三列允许为空但由约束限定只能用于 `agent.run`；通知正文在配置列、接收人在目标表 |
| `scheduled_occurrence` | 新增操作代码、参数版本、原计划修改版本、固定参数、汇总结果、快照来源、准备工作关联和错误摘要；新增部分失败及未知状态；同一计划只能有一条活动发生记录的约束保留 |
| `notification` | 增加来源发生记录和发起者；每发生记录每接收人唯一；新增带接收人的复合唯一键供发送记录引用 |
| `background_job` | 新增 `scheduled_action`（准备定时操作）和 `channel_delivery`（发送外部通知）类型及按类型领取的索引；复用原租约和恢复机制 |

历史智能体发生记录使用 `snapshot_origin=legacy_unavailable`，固定参数及计划版本为空。新发生记录使用 `captured`，必须保存实际触发时的参数及版本。禁止使用当前计划内容填造旧历史。

普通读写通过 MyBatis-Plus（通用数据库访问组件），简单关联通过 MyBatis-Plus-Join（关联查询组件）；带锁读取、并发冲突及原子更新才放入 `src/main/resources/mapper/{module}` 中的 XML（数据库语句映射文件）。测试准备数据遵守相同规则。

## 3. 授权与通知边界

应用校验、授权码交换、访问凭证刷新及消息网络调用都在数据库事务外完成。接入修改、用户绑定和发送结果保存重新核对企业、身份及修订版本；管理员校验期间配置改变，旧校验结果不能启用新配置。

OAuth（平台授权协议）使用一次性随机状态与发起浏览器关联；数据库保存摘要，飞书 PKCE（防止授权码被其他客户端冒用的校验机制）原文加密暂存。回调先一次性占用授权记录，再请求平台；绑定还需原浏览器和原本地账号明确确认。登录只接受已经绑定的身份，不自动建号。

用户访问凭证只用于此次取得身份，不保存为长期发送凭证。后续通知用企业应用凭证，Redis 缓存也加密，缓存键包含凭据版本。发送前再检查当前应用、密钥版本、原绑定及其版本、接收开关、成员与发起者权限；改绑不会导致旧通知改投。

六项新权限是 `integration.view/manage/test`（查看、配置及测试接入）、`notification.send.enterprise`（向其他企业成员发送通知）、`notification.delivery.view/retry`（查看及重试企业发送记录）。迁移仅补给内置企业管理员，不自动扩大自定义角色。企业接入管理同时要求企业管理员身份；超级管理员通过独立系统管理接口操作，不伪造成员。

外部登录只有对应企业范围，最长一小时、空闲三十分钟；本地绑定和权限撤销在下次请求检查。平台成员在上游被停用的即时事件订阅不在本期，下一次授权由平台拒绝；不能把本地检查称为上游即时同步。

## 4. 执行、重试与取消

1. 调度事务锁定企业和计划，校验当前所有者权限，保存本次固定参数和 `scheduled_action` 工作，再推进下一次时间。
2. 准备工作先锁企业、计划和发生记录。处理器只创建数据库记录或后续持久工作，禁止在该事务里调用网络。统一在最后锁定并验证工作租约；已经被其他进程接管则整笔事务回滚。
3. 通知逐人保存站内内容，记录失效接收人，再批量排入渠道工作。智能体仍检查原配额、并发和固定版本；通知不占模型名额。
4. 渠道工作先获取凭证及限流额度，开始前复查状态，再在事务外发请求。保存结果时验证本次工作和租约版本，旧进程不能覆盖新结果。
5. 后续扫描汇总该次原接收人、站内结果、尚未创建通知的阻止对象和全部渠道。仍有活动工作时保持执行中，终态才释放计划活动位置。

新生成的自动通知工作保存 `channelRoutingVersion=1`（该排队事件采用的外部渠道规则版本）。升级前的工作没有此字段，消费后仍只生成站内通知；不能仅根据事件是否过期来决定是否按新偏好转发旧队列。新事件仍须满足当前用户偏好、成员和绑定等条件，版本标记本身不授予发送权限。

自动发送最多五次实际请求；等待限流不计为网络尝试。工作租约六十秒、每十五秒续期。平台可重试错误采用递增等待，令牌过期只允许一次刷新后重试。飞书固定请求编号、企业微信固定完整请求用于平台去重；去重能力有有效期，代码将不确定结果的自动重试限制在首次发送后五十分钟内，通知本身仍受一小时发送有效期限制。

不能承诺网络故障下绝对只送一次。连接中断、结果保存前退出等情况保留“未知”的事实；最终未知结果允许满足当前权限、身份、期限条件的人工重试，且要求确认可能重复。最多三次人工重试，每次只增加一次实际请求额度。已成功的渠道和站内通知不会重新执行。

停止本次保存停止选择并阻止未开始的工作；网络中的请求等待真实返回。平台已经接受的结果保留，临时失败不能在停止后再次自动重试，未知不能写成确定取消。人工重试定时通知先锁原计划和发生记录；若有另一轮正在执行或计划已删除则拒绝，避免同一计划产生两条活动记录。

## 5. 接口与工具

[OpenAPI（接口描述规范）契约](../src/test/resources/contracts/openapi.json)定义实际字段、请求头和响应。统一前缀 `/api/v1`；修改沿用请求防伪、重复请求识别及 `If-Match`（期望修改版本）机制。主要入口如下：

| 相对路径 | 用途 |
| --- | --- |
| `/enterprises/{enterpriseId}/integration-providers`、`/integrations` | 可用平台字段及接入管理；系统管理使用 `/system/enterprises/{enterpriseId}/...` |
| `/integrations/{connectionId}/check`、`/status`、`/rotate-secret` | 上表企业前缀下的校验、启停、更换密钥 |
| `/integrations/{connectionId}/recipients`、`/test-messages`、`/deliveries` | 同一企业前缀下的测试对象、固定测试通知和发送列表 |
| `/enterprises/{enterpriseId}/me/channels/...` | 本人绑定及发起授权；具体写入路径以契约为准 |
| `/auth/channel-login/{key}` | 公开企业登录信息和发起登录 |
| `/auth/channel-callbacks/{connectionId}` | 平台重定向入口，返回不含授权码的本站地址 |
| `/auth/channel-authorization`、`/cancel` | 读取待确认身份、取消当前短期授权 |
| `/enterprises/{enterpriseId}/schedule-actions`、`/schedule-recipients` | 当前可选操作、分页接收人与实际可用渠道 |
| `/enterprises/{enterpriseId}/schedules/{scheduleId}/occurrences/{occurrenceId}` | 本次固定内容与逐人发送结果；追加 `/cancel` 停止当前一轮 |
| `/enterprises/{enterpriseId}/notification-deliveries/{deliveryId}`、`/retry` | 实际尝试历史与单渠道人工重试 |

创建通知计划的正式请求示例（日期不适用时仍须显式提交 `null`）：

```json
{
  "name": "工作日提醒",
  "frequency": "weekly",
  "localDate": null,
  "localTime": "09:00",
  "weekdays": [1, 2, 3, 4, 5],
  "monthDay": null,
  "timezone": "Asia/Shanghai",
  "enabled": true,
  "maxRetries": 0,
  "action": {
    "type": "notification.send",
    "schemaVersion": 1,
    "config": {
      "title": "工作提醒",
      "body": "请查看今天需要处理的事项。",
      "recipients": [
        {"userId": "本企业成员编号", "connectionIds": ["企业接入编号"]}
      ]
    }
  }
}
```

标题最长一百字、正文最长五百字；空渠道数组只写站内通知。接收人位于 `action.config.recipients`，不是 `action.recipients`。通知 `maxRetries` 固定零，实际渠道重试有独立规则。旧智能体平铺参数继续可用，但禁止与新 `action` 格式同时提交。

手动触发返回的发生记录最初可以没有 `runId`（智能体执行编号），因为后台尚未完成准备；客户端继续查询真实状态。新增 `schedule_actions`、`schedule_recipients`、`schedule_occurrence_get`、`schedule_action_create/update` 和 `schedule_cancel_occurrence` 内置工具，原智能体工具的参数及结构摘要保留。写操作仍按当前用户权限和现有工具确认流程处理。

契约更新脚本 `node scripts/update-integration-contract.cjs` 只维护本功能的结构与路径，避免重排其他契约；改接口时同步脚本、契约和实际请求测试。

## 6. 后续扩展方法

### 增加消息渠道

1. 新增 `service/integration/provider/<正式渠道代码>` 下的 Spring 组件，实现 `IntegrationProvider`，声明表单字段、配置校验、固定接口及应用身份检查。
2. 能做身份授权时实现 `ChannelIdentityProvider`，能发个人消息时实现 `ChannelMessageSender`。能力独立判断，不要求所有平台具备全部能力。没有可靠去重保证时保留默认的零去重时长，未知请求不得借用其他平台的重试规则。
3. 在 `mapper/integration` 将官方响应映射为共同结果，明确区分接受、可重试拒绝、永久拒绝、令牌失效及未知；固定渲染后的请求，不在发送器内部自动重试。
4. 补官方文档对应样本、请求编码、身份范围、大小及频率边界、异常和恢复测试。新身份类型、地区或安装方式若需要不同约束，新增迁移和严格校验后再开放，不能直接接受客户端任意值。

当前个人通知依赖“企业应用中的已绑定成员”。以后增加邮件地址、群机器人或其他接收目标，还需要明确目标的验证和授权方式；不可伪造现有平台成员编号来复用。本期未提供任意 Webhook、脚本或表达式执行入口。

### 增加定时操作

1. 在 `service/schedule/action` 实现 `ScheduleActionHandler` 并注册为 Spring 组件，使用唯一操作代码和明确的参数版本。
2. 实现当前权限、参数结构、保存校验、纯读取快照、公开配置及提交。`snapshot` 不依赖仍然有效的当前账号，失效对象也要保留为该次明确的阻止结果。
3. `submit/cancel` 只能执行数据库内工作或创建后续持久工作；外部请求由独立工作器执行，不能延长通用事务。需要后续汇总时实现 `reconcile`，返回最终结果前保留计划活动位置。
4. 页面可用类型来自目录，但新增复杂操作仍需相应编辑和详情组件；未知类型不能退化成智能体操作后保存。增加模型可调用工具时遵循原权限与确认要求。
5. 补重复触发、权限变化、旧租约、进程退出、取消和旧参数版本测试；数据约束改变另加迁移。调度器、时间计算及现有渠道发送不应出现按新操作代码判断的分支。

## 7. 验证入口与边界

- 平台协议：`ChannelProviderProtocolTest`，官方样本和来源见[协议记录](../src/test/resources/contracts/integration/README.md)。模拟在网络边界接收正式适配器的请求。
- 配置、授权、隔离：`IntegrationManagementApiTest`、`ChannelAuthorizationApiTest`、`ChannelDeliveryAccessTest` 及外部会话专项。
- 通知可靠性：`ChannelDeliveryWorkerTest`、`ScheduledChannelNotificationTest`，覆盖双平台单独重试、未知结果、接收人变化及停止后的恢复。
- 定时业务：`ScheduledNotificationManagementApiTest`、`ScheduledNotificationExecutionTest`、原 `ScheduleTriggerApiTest/RevocationApiTest`、`ScheduleNotificationRolloutTest`。
- 升级：`IntegrationSchemaMigrationTest`，真实 MySQL 中验证空库、带旧计划/发生记录升级、重复启动和约束。
- 容量：`ScheduleCapacityTest`，同分钟一千条到期计划、两个调度线程和两个工作对象，共享同一 Java 进程与隔离数据库。输出 `target/schedule-capacity-result.json`；此测试没有向真实平台发送，也不代替独立主机和生产资源验证。
- 界面：计划表单和外部登录前端测试、类型检查、静态检查、生产构建，以及隔离模拟平台上的桌面和手机浏览器操作。

真实平台回调、应用审批、可见范围及成员实际收到消息，由用户按要求在开发完成后配置并确认；测试账号和模拟平台的通过结果不能写成真实平台已经联调通过。
