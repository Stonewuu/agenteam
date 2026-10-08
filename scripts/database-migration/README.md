# 合并初始化脚本后的已有数据库升级

新的 `V0__init.sql` 与旧迁移记录的校验值不同，直接更新应用可能无法启动。已有数据库结构与新 V0 一致时，可以保留全部业务数据，归档旧迁移记录，再显式建立版本 0 起点。空数据库仍由应用正常执行 V0。

`FlywayMaintenance.java` 是发布维护入口，不随应用启动自动执行。它使用应用包中的 Flyway（数据库迁移脚本执行与版本记录工具）依赖，仅提供 `baseline`、`validate`、`migrate` 三种操作。`baseline` 固定建立版本 0 起点；后续 `V0.1.0.1__简短更改说明.sql` 等版本仍可按序执行。

发布前必须完成：

1. 保存数据库、部署配置、应用密钥与持久文件备份，并实际恢复到隔离数据库。
2. 比较线上业务表和新 V0 初始化结果的字段、索引、外键及检查约束，确认一致。
3. 在隔离数据库归档旧 `flyway_schema_history`，调用维护入口的 `baseline` 和 `migrate`，确认业务表内容未改变、重复执行无新增迁移，且下一版本可正常执行。
4. 停止应用写入后重新备份，在正式数据库执行同样操作；旧历史保存在独立归档数据库中，不删除业务表。
5. 校验数据和原密钥，更新应用，再次验证迁移检查、服务状态和业务入口。

维护入口通过 `MIGRATION_DB_URL`、`MIGRATION_DB_USER`、`MIGRATION_DB_PASSWORD` 接收专用连接配置；执行 `baseline` 还需显式设置 `MIGRATION_CONFIRM=baseline-0`。默认脚本位置为应用包的 `classpath:db/schema`，演练可用 `MIGRATION_LOCATIONS` 指定隔离目录。凭据使用权限受限的临时环境文件，不进入源码、日志或镜像。

使用 Java 21 和该发布包的依赖编译入口，将编译结果挂载到应用镜像的 `/maintenance`，通过 Spring Boot（应用启动框架）的 `PropertiesLauncher` 执行：

```sh
java -Dloader.path=/maintenance -Dloader.main=FlywayMaintenance \
  -cp /app/agenteam.jar org.springframework.boot.loader.launch.PropertiesLauncher validate
```

应用继续保留 `baseline-on-migrate=false`、`clean-disabled=true`、`validate-on-migrate=true`。回退时先停止应用，归档本次新迁移记录并恢复原迁移历史，再使用原镜像和配置启动；如果发布后又执行了业务结构变更，必须另行制定匹配的回退步骤。

依据：[Flyway 重新建立迁移起点](https://documentation.red-gate.com/flyway/learn-more-about-flyway/rebaselining)、[baseline 命令](https://documentation.red-gate.com/flyway/reference/commands/baseline)。
