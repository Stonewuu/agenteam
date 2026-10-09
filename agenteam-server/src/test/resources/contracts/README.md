# 社区接口与旧结构测试附件

`openapi.json` 是 AgenTeam 当前接口的描述文件，响应测试直接读取它，以保持文档与实际接口一致。

`schema-target.sql`、`data-model.json`、`permission-seed.sql` 与 `historical-permissions.json` 属于拆分前完整目标结构的测试快照。它们验证旧表、字段、关联和权限种子，不能当作当前发行版启用了所有历史能力的说明。`TargetSchemaContractTest` 将旧结构和旧权限快照逐项核对；生产数据库使用运行目录中的正式迁移脚本。

`integration/` 中的企业微信和飞书协议样本用于接口适配验证，保留真实适配器发出请求后的模拟响应和异常验证。
