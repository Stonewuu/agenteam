# AgenTeam 部署文件

本目录从当前版本的部署镜像提取，不包含实际数据库密码、客户许可证或签发私钥。先阅读 [本版本安装说明](docs/deployment/images.md)，再在服务器运行 `python3 configure-server.py --domain 实际域名` 创建首次配置。

安装程序不会覆盖已有密码。升级旧部署前，按维护说明保存数据库、文件、配置和原镜像；保留原服务名称与数据卷关系。同机运行多套独立部署时，首次启动前修改各自的项目名、端口及网络配置。

[企业微信与飞书说明](docs/deployment/enterprise-messaging.md)适用于两个版本。商业部署包另附 `docs/licensing/` 中的激活和维护说明。

`licenses/agenteam/LICENSE` 只适用于公共 AgenTeam 源代码。商业扩展及第三方组件分别保留其原许可；不能把公共许可理解为对所有打包内容统一授权。正式交付以该版本的许可资料及整批发布记录为准。

## 两套部署共用一个 MySQL 实例

可以由原部署继续运行 MySQL，另一套部署只连接它，从而减少一个数据库容器的内存开销。两套应用必须使用不同数据库和专用账号，Redis（会话与实时数据缓存）、应用密钥、文件卷、容器项目名、端口和应用网段仍各自独立。以下步骤需要 Docker Compose 2.24.4 或更新版本；只适用于同一 Docker 服务上的两套部署。

1. 先备份原数据库及部署配置，保留原部署的 `COMPOSE_PROJECT_NAME`、数据库名、账号、密码和数据卷。创建专用内部网络：`docker network create --internal agenteam-shared-database`。如果该网络已经存在，先确认它属于这两套部署并保持内部网络，不重复创建。
2. 在提供 MySQL 的原部署 `.env` 中增加 `SHARED_DATABASE_NETWORK=agenteam-shared-database`，在现有 `COMPOSE_FILE` 末尾追加 `,compose.shared-mysql.yaml`。检查配置后，在维护窗口执行 `docker compose up -d --no-build --wait mysql`。此操作可能重建数据库容器以加入网络，但继续使用原数据卷；不能使用删除卷的命令。
3. 使用数据库管理员连接该实例，为新部署创建空库和独立账号。以下是数据库语句示例，执行前替换密码，且不要把实际密码放入终端历史或日志：

```sql
CREATE DATABASE agenteam_community CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'agenteam_community'@'%' IDENTIFIED BY '替换为新部署配置中的随机DB_PASSWORD';
GRANT ALL PRIVILEGES ON agenteam_community.* TO 'agenteam_community'@'%';
```

只授予新库范围内的权限，不授予全局权限或转授权权限。原商业账号同样只访问原业务库；在独立验证中确认两个账号不能读取对方数据库。上述语句仅用于新库，不对旧库重新初始化。

4. 在新部署的空目录生成配置，再调整以下项目。`DB_PASSWORD` 必须与上一步新账号密码一致，其他密钥继续使用新生成的值。

```dotenv
COMPOSE_PROJECT_NAME=agenteam-community
COMPOSE_FILE=compose.yaml,compose.registry.yaml,compose.single.yaml,compose.external-mysql.yaml
COMPOSE_PATH_SEPARATOR=,
HTTP_PORT=8089
NETWORK_PREFIX=172.29.45
TRUSTED_EDGE_PROXIES=172.29.45.1/32
SHARED_DATABASE_NETWORK=agenteam-shared-database
SHARED_MYSQL_HOST=agenteam-shared-mysql
SHARED_MYSQL_PORT=3306
SHARED_MYSQL_DATABASE=agenteam_community
SHARED_MYSQL_USERNAME=agenteam_community
```

域名使用本套部署的实际域名，Nginx（网页入口代理）转发到本套 `HTTP_PORT`。确认示例网段未被占用；原部署的名称、端口和网段不要为了套用示例而改变。不要为新部署启用 `bundled-database` 配置组，它会再次启动一套 MySQL。

5. 分别运行 `docker compose config --quiet` 检查两套配置。在新部署运行 `docker compose config --services`，结果应不含 `mysql`；确认原 MySQL 可以连接后再启动新部署。新应用不再通过本套容器的健康检查等待数据库，连接失败时应先检查共享实例、网络、账号和库名。

共享数据库实例维护或故障会同时影响两套应用，维护窗口需要一起安排。停止新应用时不删除专用网络或原 MySQL 数据卷；备份、恢复仍按各自数据库和各自文件执行。
