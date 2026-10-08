const fs = require("node:fs");
const path = "src/test/resources/contracts/openapi.json";
let source = fs.readFileSync(path, "utf8");
const document = JSON.parse(source);
const definitions = {};
const changedPaths = {};
const text = (min = 0, max = 8192) => ({type: "string", minLength: min, maxLength: max});
const id = text(1, 100);
const boolean = {type: "boolean"};
const integer = (min = 0, max) => ({type: "integer", minimum: min, ...(max == null ? {} : {maximum: max})});
const nullable = schema => ({anyOf: [schema, {type: "null"}]});
const object = (properties, required = Object.keys(properties)) => ({type: "object", properties, required, additionalProperties: false});
const ref = name => ({$ref: `#/components/schemas/${name}`});
const array = items => ({type: "array", items});
const date = {type: "string", format: "date-time"};
const revision = {type: "string", pattern: "^[1-9][0-9]*$"};
const enumeration = (...values) => ({type: "string", enum: values});
const configuration = object({dailyMessageLimit: integer(1, 100000000)}, []);

// 只修改本次增加的对象，避免重排原契约中的其他路径与数字状态码。
function replaceObject(name, value, indent, parent) {
  const token = `\n${indent}${JSON.stringify(name)}: {`;
  let start = source.indexOf(token);
  const encoded = JSON.stringify(value, null, 2).replaceAll("\n", "\n" + indent);
  if (start < 0) {
    const anchor = `"${parent}": {`;
    const at = source.indexOf(anchor) + anchor.length;
    if (at < anchor.length) {
      throw new Error("接口契约中缺少父对象：" + parent);
    }
    source = source.slice(0, at) + `\n${indent}${JSON.stringify(name)}: ${encoded},` + source.slice(at);
    return;
  }
  start += token.length - 1;
  let depth = 0, inside = false, escaped = false, end = start;
  for (; end < source.length; end++) {
    const character = source[end];
    if (inside) {
      if (escaped) {
        escaped = false;
      } else if (character === "\\") {
        escaped = true;
      } else if (character === '"') {
        inside = false;
      }
    } else if (character === '"') {
      inside = true;
    } else if (character === "{") {
      depth++;
    } else if (character === "}" && --depth === 0) {
      break;
    }
  }
  source = source.slice(0, start) + encoded + source.slice(end + 1);
}

definitions.Integration = object({id, enterpriseId: id, providerCode: text(1, 32), providerName: text(1, 80), name: text(1, 80),
  externalTenantId: nullable(text(1, 191)), externalAppId: text(1, 191), status: enumeration("draft", "enabled", "disabled", "deleted"),
  bindingEnabled: boolean, loginEnabled: boolean, messagingEnabled: boolean, secretConfigured: boolean,
  callbackUrl: text(1, 4096), loginUrl: text(1, 4096), lastCheckStatus: enumeration("not_checked", "passed", "failed"),
  lastCheckedAt: nullable(date), lastCheckError: nullable(text()), revision, createdAt: date, updatedAt: date, configuration});
definitions.IntegrationCreate = object({providerCode: text(1, 32), name: text(1, 80), externalTenantId: nullable(text(1, 191)),
  externalAppId: text(1, 191), secret: text(1, 8192), bindingEnabled: nullable(boolean), loginEnabled: nullable(boolean),
  messagingEnabled: nullable(boolean), configuration: nullable(configuration)}, ["providerCode", "name", "externalAppId", "secret"]);
definitions.IntegrationUpdate = object({name: nullable(text(1, 80)), bindingEnabled: nullable(boolean), loginEnabled: nullable(boolean),
  messagingEnabled: nullable(boolean), configuration: nullable(configuration)}, []);
definitions.IntegrationSecret = object({secret: text(1, 8192)});
definitions.IntegrationStatus = object({status: enumeration("enabled", "disabled")});
definitions.IntegrationProvider = object({code: text(1, 32), name: text(1, 80), identitySupported: boolean, messagesSupported: boolean,
  fields: array(object({name: text(1, 100), label: text(1, 100), type: enumeration("text", "number"), required: boolean,
    minimum: nullable(integer()), maximum: nullable(integer()), defaultValue: {}, hint: nullable(text())}))});
definitions.ChannelAuthorizationStart = object({embeddedClient: nullable(boolean)}, []);
definitions.ChannelAuthorizationUrl = object({authorizationUrl: text(1, 8192)});
definitions.ChannelLoginInfo = object({name: text(1, 80), providerName: text(1, 80), providerCode: text(1, 32)});
definitions.ChannelAuthorizationReview = object({authorizationId: id, enterpriseId: id, connectionName: text(1, 80), providerName: text(1, 80),
  username: text(1, 64), localDisplayName: text(), externalDisplayName: nullable(text()), externalSubjectId: text(1, 191), expiresAt: date, loginAvailable: boolean});
definitions.ChannelAuthorizationCancel = object({authorizationId: id});
definitions.ChannelBinding = object({id: nullable(id), connectionId: id, connectionName: text(1, 80), providerCode: text(1, 32), providerName: text(1, 80),
  status: enumeration("unbound", "active", "disabled", "revoked"), displayName: nullable(text()), receiveEnabled: boolean, externalLoginEnabled: boolean,
  revision: nullable(revision), bindingAvailable: boolean, loginAvailable: boolean, messagingAvailable: boolean});
definitions.ChannelBindingUpdate = object({receiveEnabled: boolean, externalLoginEnabled: boolean});
definitions.ChannelBindingConfirm = object({authorizationId: id, ...definitions.ChannelBindingUpdate.properties});
definitions.ChannelPreference = object({connectionId: id, connectionName: text(1, 80), providerName: text(1, 80), canEnable: boolean,
  categories: array(object({category: text(1, 32), name: text(1, 80), enabled: boolean, revision}))});
definitions.ChannelPreferenceWrite = object({connectionId: id, category: text(1, 32), enabled: boolean});
definitions.NotificationRecipientOption = object({id, name: text()});
definitions.IntegrationTestMessage = object({recipientUserId: id});
definitions.ChannelDeliveryRetry = object({confirmMayDuplicate: nullable(boolean)}, []);
definitions.ChannelDelivery = object({id, notificationId: id, recipientName: nullable(text()), connectionId: id, connectionName: text(1, 80), providerName: text(1, 80),
  status: enumeration("pending", "sending", "retry_wait", "accepted", "failed", "blocked", "cancelled", "unknown", "expired"),
  attemptCount: integer(), manualRetryCount: integer(0, 3), nextAttemptAt: nullable(date), expiresAt: date, acceptedAt: nullable(date),
  errorSummary: nullable(text()), canRetry: boolean, duplicateConfirmationRequired: boolean, revision, createdAt: date});
definitions.ChannelDeliveryDetail = object({delivery: ref("ChannelDelivery"), attempts: array(object({number: integer(1),
  outcome: enumeration("started", "accepted", "retryable_failure", "permanent_failure", "unknown"), httpStatus: nullable(integer(100, 599)),
  providerCode: nullable(text()), summary: nullable(text()), startedAt: date, finishedAt: nullable(date)}))});
definitions.ScheduleActionOption = object({type: text(1, 64), name: text(1, 80), schemaVersions: array(integer(1)), usesAgent: boolean, configSchema: {type: "object"}});
definitions.ScheduleRecipientOption = object({userId: id, name: text(), channels: array(object({connectionId: id, name: text(1, 80), providerCode: text(1, 32), providerName: text(1, 80)}))});
definitions.ScheduleRecipientResult = object({userId: id, name: text(), inAppStatus: enumeration("pending", "delivered", "blocked", "cancelled", "failed", "missed", "skipped"),
  reason: nullable(text()), channels: array(ref("ChannelDelivery"))});
definitions.ScheduleOccurrenceDetail = object({occurrence: ref("ScheduleOccurrence"), recipients: array(ref("ScheduleRecipientResult"))});

const page = name => object({items: array(ref(name)), nextCursor: nullable(text(1, 2048)), hasMore: boolean});
const headers = document.paths["/enterprises/{enterpriseId}/schedules/{scheduleId}"].put.parameters.filter(item => item.in === "header");
const queries = [{name: "cursor", in: "query", required: false, schema: text(1, 2048)}, {name: "limit", in: "query", required: false, schema: integer(1, 100)}];
const errorResponse = {description: "请求、权限、版本或业务条件不符合要求。", content: {"application/json": {schema: ref("ErrorResponse")}}};
function operation(path, method, name, summary, data, options = {}) {
  const parameters = [...path.matchAll(/\{(.*?)\}/g)].map(match => ({name: match[1], in: "path", required: true, schema: id}));
  if (method !== "get") {
    parameters.push(...headers.filter(item => item.name === "X-CSRF-Token" ? true
      : item.name === "If-Match" ? Boolean(options.revision) : options.idempotent !== false));
  }
  if (options.page) {
    parameters.push(...queries);
  }
  if (options.search) {
    parameters.push({name: options.search, in: "query", required: false, schema: text(0, 100)});
  }
  const management = path.startsWith("/system/");
  const result = {operationId: name, summary, description: options.description ?? (management
    ? "要求当前本地会话具有全局超级管理员身份，不要求加入路径企业。响应不返回应用密钥或授权令牌。"
    : "重新检查当前身份与路径企业。应用密钥仅在新增接入或更换密钥的请求中提交，响应不返回密钥或授权令牌。"),
    "x-permissions": management ? [] : options.permissions ?? [], parameters, security: options.public ? [] : [{sessionCookie: []}], responses: {
      [options.status ?? 200]: {description: "已保存或已读取当前结果。平台接受通知不代表成员已读。", content: {"application/json": {
        schema: object({data, meta: ref("ResponseMeta")})}}},
      ...Object.fromEntries([400, 401, 403, 404, 409, 422, 428, 429, 500, 503].map(code => [code, errorResponse]))}};
  if (options.body) {
    result.requestBody = {required: true, content: {"application/json": {schema: options.body}}};
  }
  const target = changedPaths[path] ?? structuredClone(document.paths[path] ?? {});
  target[method] = result;
  changedPaths[path] = target;
}

for (const system of [false, true]) {
  const prefix = system ? "System" : "Enterprise";
  const base = `${system ? "/system" : ""}/enterprises/{enterpriseId}`;
  const directory = base + "/integrations";
  const item = directory + "/{connectionId}";
  operation(base + "/integration-providers", "get", `list${prefix}IntegrationProviders`, "读取可用渠道和管理表单字段", array(ref("IntegrationProvider")), {permissions: ["integration.view"]});
  operation(directory, "get", `list${prefix}Integrations`, "查询企业接入", page("Integration"), {page: true, permissions: ["integration.view"]});
  operation(directory, "post", `create${prefix}Integration`, "新增企业自建应用接入", ref("Integration"), {status: 201, body: ref("IntegrationCreate"), permissions: ["integration.manage"]});
  operation(item, "get", `get${prefix}Integration`, "读取脱敏配置", ref("Integration"), {permissions: ["integration.view"]});
  operation(item, "patch", `update${prefix}Integration`, "更新接入名称和能力选择", ref("Integration"), {body: ref("IntegrationUpdate"), revision: true, permissions: ["integration.manage"]});
  operation(item, "delete", `delete${prefix}Integration`, "删除已停用且无活动发送的接入", object({}), {revision: true, permissions: ["integration.manage"]});
  operation(item + "/status", "patch", `set${prefix}IntegrationStatus`, "启用或停用接入", ref("Integration"), {body: ref("IntegrationStatus"), revision: true, permissions: ["integration.manage"]});
  operation(item + "/rotate-secret", "post", `rotate${prefix}IntegrationSecret`, "更换应用密钥", ref("Integration"), {body: ref("IntegrationSecret"), revision: true, permissions: ["integration.manage"]});
  operation(item + "/check", "post", `check${prefix}Integration`, "请求平台校验应用身份", ref("Integration"), {revision: true, permissions: ["integration.test"]});
  operation(item + "/recipients", "get", `list${prefix}IntegrationRecipients`, "查询可接收测试通知的绑定成员", page("NotificationRecipientOption"), {page: true, search: "search", permissions: ["integration.test"]});
  operation(item + "/test-messages", "post", `test${prefix}Integration`, "向明确成员排入固定内容的测试通知", ref("ChannelDeliveryDetail"), {status: 201, body: ref("IntegrationTestMessage"), revision: true, permissions: ["integration.test"]});
  operation(item + "/deliveries", "get", `list${prefix}IntegrationDeliveries`, "查询脱敏发送记录", page("ChannelDelivery"), {page: true, permissions: ["notification.delivery.view"]});
  operation(base + "/notification-deliveries/{deliveryId}", "get", `get${prefix}ChannelDelivery`, "查询发送结果与实际尝试", ref("ChannelDeliveryDetail"));
  operation(base + "/notification-deliveries/{deliveryId}/retry", "post", `retry${prefix}ChannelDelivery`, "人工重试选定的失败或未知发送", ref("ChannelDeliveryDetail"), {body: ref("ChannelDeliveryRetry"), revision: true,
    description: "仅原有权限仍有效的发起人或发送运维人员可操作；沿用原内容、绑定和凭据版本。未知结果需确认重复风险，每条最多人工重试三次。"});
}
const enterprise = "/enterprises/{enterpriseId}";
operation(enterprise + "/me/channels", "get", "listOwnChannelBindings", "查询本人企业账号绑定", array(ref("ChannelBinding")));
operation(enterprise + "/me/channels/{connectionId}/authorize", "post", "authorizeOwnChannelBinding", "发起本人绑定授权", ref("ChannelAuthorizationUrl"), {body: ref("ChannelAuthorizationStart"), idempotent: false});
operation(enterprise + "/me/channels/confirm", "post", "confirmOwnChannelBinding", "确认当前浏览器取得的明确身份", ref("ChannelBinding"), {status: 201, body: ref("ChannelBindingConfirm")});
operation(enterprise + "/me/channels/{bindingId}", "patch", "updateOwnChannelBinding", "更新本人接收与登录选择", ref("ChannelBinding"), {body: ref("ChannelBindingUpdate"), revision: true});
operation(enterprise + "/me/channels/{bindingId}", "delete", "revokeOwnChannelBinding", "解除本人绑定并停止等待中的发送", object({}), {revision: true});
operation(enterprise + "/me/channel-preferences", "get", "listOwnChannelPreferences", "查询本人自动通知偏好", array(ref("ChannelPreference")));
operation(enterprise + "/me/channel-preferences", "put", "setOwnChannelPreference", "保存一个渠道一个类别的接收选择", array(ref("ChannelPreference")), {body: ref("ChannelPreferenceWrite"), revision: true});
operation("/auth/channel-login/{key}", "get", "getChannelLoginInfo", "查询公开企业登录入口", ref("ChannelLoginInfo"), {public: true});
operation("/auth/channel-login/{key}", "post", "startChannelLogin", "发起仅当前企业有效的外部登录", ref("ChannelAuthorizationUrl"), {public: true, idempotent: false, body: ref("ChannelAuthorizationStart"),
  description: "无需先登录，但须先取得 /auth/csrf 返回的防伪令牌，并携带同一匿名会话 Cookie 和 X-CSRF-Token。授权流程绑定发起浏览器；已经登录时须先退出。"});
operation("/auth/channel-authorization", "get", "reviewChannelAuthorization", "读取原浏览器内的待确认身份", ref("ChannelAuthorizationReview"));
operation("/auth/channel-authorization/cancel", "post", "cancelChannelAuthorization", "取消明确的待确认授权", object({cancelled: boolean}), {body: ref("ChannelAuthorizationCancel"), idempotent: false});
operation("/auth/reauthenticate", "post", "reauthenticateLocally", "验证本地密码并重新取得当前身份", ref("CurrentUser"), {body: object({password: text(1, 256)}), idempotent: false});
changedPaths["/auth/channel-callbacks/{connectionId}"] = {get: {operationId: "handleChannelCallback", summary: "处理一次性平台回调并跳转到本站结果页", security: [],
  parameters: [{name: "connectionId", in: "path", required: true, schema: id}, ...["state", "code"].map(name => ({name, in: "query", required: false, schema: text()}))],
  responses: {303: {description: "跳转后地址不含授权码。", headers: {Location: {schema: text(1, 4096)}}}}}};
operation(enterprise + "/notifications/{notificationId}", "get", "getOwnNotification", "读取本人站内通知正文", ref("Notification"), {permissions: ["notification.view"]});
operation(enterprise + "/notifications/{notificationId}/deliveries", "get", "listOwnNotificationDeliveries", "查询本人通知的外部发送结果", page("ChannelDelivery"), {page: true});
operation(enterprise + "/schedule-actions", "get", "listScheduleActions", "读取本人可使用的操作及参数结构", array(ref("ScheduleActionOption")), {permissions: ["schedule.manage"]});
operation(enterprise + "/schedule-recipients", "get", "listScheduleRecipients", "查询当前发送范围内的成员和可用渠道", page("ScheduleRecipientOption"), {page: true, search: "query", permissions: ["schedule.manage"]});
operation(enterprise + "/schedules/{scheduleId}/occurrences/{occurrenceId}", "get", "getOwnScheduleOccurrence", "读取本次固定内容和逐人结果", ref("ScheduleOccurrenceDetail"), {permissions: ["schedule.view"]});
operation(enterprise + "/schedules/{scheduleId}/occurrences/{occurrenceId}/cancel", "post", "cancelOwnScheduleOccurrence", "停止本次尚未开始的工作", ref("ScheduleOccurrence"), {permissions: ["schedule.manage"],
  description: "保留已送达内容和平台已接受的结果；正在请求时保持活动，等待真实结果。停止标记阻止后续自动重试。"});

replaceObject("info", {...document.info, title: "AgenTeam 项目接口", version: "0.1.1", description: "与 0.1.1 实现及测试对应的接口契约，不表示代码已经部署。企业身份来自路径并重新验证，正文仅向有权用户返回。"}, "  ");
for (const [name, value] of Object.entries(definitions)) {
  replaceObject(name, value, "      ", "schemas");
}
for (const [name, value] of Object.entries(changedPaths)) {
  replaceObject(name, value, "    ", "paths");
}
JSON.parse(source);
fs.writeFileSync(path, source);
console.log(`已同步 ${Object.keys(definitions).length} 个结构和 ${Object.keys(changedPaths).length} 个接口路径。`);
