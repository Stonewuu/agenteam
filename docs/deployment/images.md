# 使用社区镜像部署

社区版从 Docker Hub 的 `stonewuu` 账号取得镜像，首次初始化后只保留一个企业。旧完整版本及已经升级过的商业数据库应按商业迁移流程处理，不能直接换成社区镜像启动。

本页对应准备中的 0.2.0。正式安装前，以该版本的完整发布记录及镜像可取得性为准；当前本机开发镜像不代表已经公开发布。已验证的镜像平台为 `linux/amd64`（64 位 x86 Linux）。

## 镜像清单

| 用途 | 镜像 |
| --- | --- |
| 后端 | `docker.io/stonewuu/agenteam-backend:0.2.0` |
| 前端 | `docker.io/stonewuu/agenteam-frontend:0.2.0` |
| 命令执行服务 | `docker.io/stonewuu/agenteam-sandbox-server:0.2.0` |
| 办公沙箱 | `docker.io/stonewuu/agenteam-sandbox-office:0.2.0` |
| 部署文件 | `docker.io/stonewuu/agenteam-deployment:0.2.0` |

部署包包含本版本的 Compose（多容器服务配置）文件、入口代理配置和初始化工具。服务器运行这些镜像不需要克隆源码，也不需要商业仓库凭据。

## 提取部署文件

在 Linux 服务器准备 Docker、Docker Compose、Python 3，以及指向该服务器的域名。以下命令在空的部署工作目录执行：

```sh
deployment_container=$(docker create docker.io/stonewuu/agenteam-deployment:0.2.0)
docker cp "$deployment_container":/deployment ./agenteam-0.2.0
docker rm "$deployment_container"
cd agenteam-0.2.0
python3 configure-server.py --domain app.example.com
```

把 `app.example.com` 换成实际域名。工具创建 `.env` 和 `agenteam.nginx.conf`，不会覆盖现有文件；数据库密码、服务凭据和 `SETUP_CREDENTIAL`（首次初始化凭据）只保存在本地配置中。

同一服务器安装多套独立部署时，每套使用不同目录，并在首次启动前分别设置 `COMPOSE_PROJECT_NAME`（服务及数据卷名称前缀）、`HTTP_PORT`（本机入口端口）和 `NETWORK_PREFIX`（容器网络地址前缀）。同步调整 `TRUSTED_EDGE_PROXIES`（可信入口代理地址）及 Nginx 转发端口，避免复用另一套部署的数据卷、容器名称或网络。升级现有部署时保留原名称与数据卷关系，不要通过改名前缀来“升级”。

## 配置入口并启动

1. 为实际域名准备 HTTPS（加密网页连接）证书，按生成的 `agenteam.nginx.conf` 配置服务器的 Nginx（入口代理）。容器服务默认只监听本机 `127.0.0.1:8088`，由这个入口接收外部请求。
2. 核对 `.env` 中的 `PUBLIC_URL`、监听端口、可信代理及资源限制。配置邮件服务后，找回密码、邮箱验证和邮件邀请才能实际发送；启用 `container,production` 环境时补齐其要求的邮件与代理配置。
3. 保持数据库、应用和文件使用同一套持久卷，执行配置检查及启动：

```sh
docker compose config --quiet
docker compose up -d --no-build --wait --wait-timeout 300
```

访问实际域名，使用 `.env` 中的初始化凭据创建管理员和初始企业，再配置模型并开始使用。企业微信、飞书的应用及绑定步骤见 [企业消息说明](enterprise-messaging.md)，它们不需要商业激活。

## 数据库与连接驱动

默认数据库服务仍是官方 MySQL 8.4。后端使用 MariaDB Connector/J 3.5.10（Java 数据库连接驱动）连接 MySQL；驱动名称不表示需要把数据库换成 MariaDB。现有数据库地址、库名、账号和业务数据保持原来的对应关系。

自行配置后端时，`AGENTEAM_DB_URL` 使用以下格式，并通过独立环境变量提供账号和密码：

```text
jdbc:mariadb://数据库主机:3306/数据库名?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&preserveInstants=true
```

`UTC` 表示协调世界时，三个时间参数用于保持数据库会话与应用时间点的转换一致。旧配置中的 `connectionTimeZone=%2B00:00` 要改为 `connectionTimeZone=UTC`。若暂时保留 `jdbc:mysql:` 前缀，必须在地址参数中加上 `permitMysqlScheme=true`；新配置优先使用上面的格式。自行指定过驱动类的配置改为 `org.mariadb.jdbc.Driver`。

随包的私有容器网络使用 `sslMode=trust` 加密连接，但不验证服务器证书。连接其他网络中的数据库时，使用 `sslMode=verify-full` 并配置可信证书及匹配的主机名，配置方法见 [MariaDB 驱动加密连接说明](https://mariadb.com/docs/connectors/mariadb-connector-j/using-tls-ssl-with-mariadb-java-connector)。应用中的外部只读数据源在生产环境仍强制校验服务器身份。

同机两套部署共用 MySQL 时，使用随包的 `compose.shared-mysql.yaml` 与 `compose.external-mysql.yaml`，步骤见部署包根目录 README.md 的“共用一个 MySQL 实例”。每套应用使用独立库和专用账号；连接共享实例的一套不再启动自己的 MySQL 容器。

## 更新与备份

停止服务用 `docker compose stop`。需要保留数据时，不要执行 `docker compose down -v`，其中 `-v` 会删除数据卷。

更新前保存数据库、Redis 持久数据、应用与用户文件、工作目录、配置和加密密钥，并保留原镜像内容摘要。下载新版本部署包时使用新的空目录，将原运行配置和数据卷关系逐项带入；不要重新生成密码或对旧数据库重新初始化。

本产品五类镜像使用同一个明确版本。升级商业版时保留原部署编号、初始企业、账号、绑定与密钥，并按商业迁移说明操作。已有商业库不能直接降级为社区库。

## 查看软件许可与第三方声明

项目版权声明保存在 `licenses/agenteam/NOTICE`，版权名称为 stonewu。

解出的部署包在 `licenses/agenteam/LICENSE` 保存公共项目许可，`licenses/third-party/README.md` 提供第三方原文索引。后端、前端、执行器和办公镜像内的对应目录为 `/usr/share/licenses/agenteam/`。可以使用 `docker cp` 从已创建的容器取得文件；查看声明不需要启动应用或连接数据库。

正式发布后，从 [0.2.0 第三方源码索引](https://github.com/Stonewuu/agenteam/releases/download/v0.2.0/third-party-sources.json) 取得本版本附件清单。索引列出对应镜像、下载地址、字节数及 SHA-256（文件内容摘要）；下载后使用 `sha256sum <附件文件>` 核对摘要再保存。附件包含相关第三方原源码、原始补丁及构建资料，各组件继续适用其原有许可。

以上地址是本版本的发布目标，当前尚未发布。正式安装前，同时核对 [两版完成记录](https://github.com/Stonewuu/agenteam/releases/download/v0.2.0/release-batch.json) 中列出的镜像及源码附件；只有下载入口可用且内容一致，才是完整交付。
