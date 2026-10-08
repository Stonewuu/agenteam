# 企业微信与飞书模拟协议依据

核对日期：2026-09-28。这里的账号、令牌、企业和消息编号都是测试专用值；字段名称、层级、参数编码和错误码依据下列官方文档。测试在网络边界接收真实适配器发出的请求，不把业务服务直接替换成“返回成功”。

样本的错误说明文字只用于可读性；平台文档说明错误文本可能变化，测试与实现都不依赖 `msg/errmsg/error_description` 的具体措辞。业务判定依赖文档定义的响应状态、数字错误码和必要字段。

| 模拟数据 | 官方依据 | 必须验证的差异 |
|---|---|---|
| `wecom.token` | [获取访问凭证](https://developer.work.weixin.qq.com/document/path/91039) | GET 地址参数，`access_token` 和 `expires_in` 在响应顶层；有效期取真实响应 |
| `wecom.application` | [获取应用](https://developer.work.weixin.qq.com/document/path/90227) | 应用编号是数字；必须与配置应用一致 |
| `wecom.identity`、`wecom.externalIdentity`、`wecom.invalidCode` | [获取访问用户身份](https://developer.work.weixin.qq.com/document/path/91023) | GET 携带应用令牌和授权码；成员字段是小写 `userid`；非成员身份不可用作内部成员 |
| `wecom.accepted`、接收人失败 | [发送应用消息](https://developer.work.weixin.qq.com/document/path/90236) | POST 正文、令牌在地址参数；`errcode=0` 时仍须核对无效或无许可接收人 |
| 企业微信限频/过期 | [全局错误码](https://developer.work.weixin.qq.com/document/path/90313) | 45009 限频，40014/42001 访问令牌失效，81013 全部接收人无效 |
| `feishu.token` | [自建应用访问凭证](https://open.feishu.cn/document/server-docs/authentication-management/access-token/tenant_access_token_internal) | POST 结构化正文，字段是 `expire`，不是 `expires_in` |
| `feishu.tenant` | [获取企业信息](https://open.feishu.cn/document/uAjLw4CM/ukTMukTMukTM/tenant-v2/tenant/query) | 应用访问令牌放认证头，企业标识位于 `data.tenant.tenant_key` |
| `feishu.userToken`、`feishu.expiredCode` | [当前用户令牌接口](https://open.feishu.cn/document/uAjLw4CM/ukTMukTMukTM/authentication-management/access-token/get-user-access-token-v3) | 当前地址 `/oauth/v3/token`；推荐表单编码；授权码、回调地址和校验原文一起提交 |
| `feishu.identity` | [用户信息](https://open.feishu.cn/document/uAjLw4CM/ukTMukTMukTM/reference/authen-v1/user_info/get) | 使用用户访问令牌，读取 `data.open_id` 并核对企业标识 |
| `feishu.accepted`、消息拒绝 | [发送消息](https://open.feishu.cn/document/server-docs/im-v1/message/create) | 地址参数 `receive_id_type=open_id`；`content` 是序列化后的字符串；消息编号位于 `data.message_id` |
| 飞书令牌失效 | [通用错误码](https://open.feishu.cn/document/ukTMukTMukTM/ugjM14COyUjL4ITN) | 99991663/99991665 是应用访问令牌失效，不能混用用户令牌 |
| `feishu.rateLimit` | [频控策略](https://open.feishu.cn/document/ukTMukTMukTM/uUzN04SN3QjL1cDN) | HTTP 429 或部分接口的 400，业务码 99991400；`x-ogw-ratelimit-reset` 表示等待秒数 |

授权地址另外依据[企业微信网页授权](https://developer.work.weixin.qq.com/document/path/91022)、[企业微信 Web 登录](https://developer.work.weixin.qq.com/document/path/98152)、[飞书授权码](https://open.feishu.cn/document/common-capabilities/sso/api/obtain-oauth-code)。飞书使用 PKCE（防止授权码被其他客户端冒用的校验机制）的 `S256` 方法，不申请离线访问或通讯录敏感字段。

网络断开、超时、服务端错误、非法正文和跳转是故障注入场景，不声称它们是平台固定返回。此时不伪造平台消息编号、不把未知结果记为成功，也不在底层客户端自动重试写请求。

模拟测试不能证明真实应用已经发布、成员在可见范围内或用户真实收到消息。用户要求把这些验证延后到页面配置完成后进行。
