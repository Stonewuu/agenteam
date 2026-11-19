# 使用 Docker 镜像部署 AgenTeam

AgenTeam 提供配套的 Docker 镜像、服务配置与初始化工具。从 Docker Hub 获取项目镜像，即可在自己的服务器上部署工作空间。

项目在正式 Release（版本发布页面）发布后自动构建镜像，成功后提供对应版本和 `latest`（最新正式版标签）。维护者配置见[自动镜像发布说明](https://github.com/Stonewuu/agenteam/blob/main/docs/development/image-publication.md)。

## 从仓库根目录启动

在装有 Docker 和 Docker Compose 的主机上运行以下命令。正式镜像提供 `linux/amd64`（64 位 x86 Linux）版本。

```sh
git clone https://github.com/Stonewuu/agenteam.git
cd agenteam
docker compose up -d
docker compose ps
```

网关 `nginx` 健康后打开 [http://localhost:8088](http://localhost:8088)。执行下面的命令读取首次设置凭据，按网页提示创建管理员和企业。

```sh
docker compose exec backend cat /run/agenteam-secrets/setup/AGENTEAM_SETUP_CREDENTIAL
```

首次启动会生成每套部署自己的密码和凭据，并存入 Docker 命名卷。后续启动复用同一套数据和凭据。需要自定义时，将根目录的 [配置示例](../../.env.example) 复制为 `.env`。例如设置 `HTTP_PORT=8090` 后，本机入口为 `http://localhost:8090`。向其他机器提供访问时，设置实际的 `PUBLIC_URL` 并配置 HTTPS 反向代理。默认只向本机开放网页端口。

## 可选部署方式

根目录的 `docker-compose.yaml` 包含完整服务。需要其他部署方式时，在它后面加载对应的附加配置。

| 用途 | 仓库中的文件 |
| --- | --- |
| 从本机源码构建前后端和沙盒 | `deploy/compose/source.yaml` |
| 在宿主机开发时连接数据库和缓存 | `deploy/compose/development.yaml` |
| 分别开放网页和接口端口 | `deploy/compose/split.yaml` |
| 让另一套站点使用当前数据库 | `deploy/compose/shared-mysql.yaml` |
| 连接已有共享数据库 | `deploy/compose/external-mysql.yaml` |
| 4 GB 主机上的低并发部署 | `deploy/compose/low-memory.yaml` |

如需从源码构建，在仓库根目录运行以下命令。

```sh
docker compose -f docker-compose.yaml -f deploy/compose/source.yaml up -d --build
```

原来使用多个 Compose 文件的源码部署沿用 `deploy/.env`、项目名和卷。更新仓库后，在 `deploy/.env` 中将单端口源码部署的 `COMPOSE_FILE` 改为 `../docker-compose.yaml,compose/source.yaml`，双端口再追加 `,compose/split.yaml`。继续从 `deploy/` 执行原来的启动命令。其他附加文件在 `deploy/.env` 中也写成 `compose/文件名.yaml`。已从旧部署包安装的站点继续使用随包配置。首次迁移时保留原数据库密码和服务凭据；已有数据库账号继续使用原密码。

## 镜像清单

| 用途 | 镜像 |
| --- | --- |
| 后端 | `docker.io/stonewuu/agenteam-backend:latest` |
| 前端 | `docker.io/stonewuu/agenteam-frontend:latest` |
| 命令执行服务 | `docker.io/stonewuu/agenteam-sandbox-server:latest` |
| 办公沙箱 | `docker.io/stonewuu/agenteam-sandbox-office:latest` |
| 部署文件 | `docker.io/stonewuu/agenteam-deployment:latest` |

部署包包含本版本的 Compose（多容器服务配置）文件、入口代理配置和初始化工具。使用部署包即可完成服务器安装。

## 使用独立部署包配置域名

在 Linux 服务器准备 Docker、Docker Compose、Python 3，以及指向该服务器的域名。以下命令在空的部署工作目录执行：

```sh
deployment_container=$(docker create docker.io/stonewuu/agenteam-deployment:latest)
docker cp "$deployment_container":/deployment ./agenteam-install
docker rm "$deployment_container"
cd agenteam-install
python3 configure-server.py --domain app.example.com
```

把 `app.example.com` 换成实际域名。工具创建 `.env` 和 `agenteam.nginx.conf`，不会覆盖现有文件；数据库密码、服务凭据和 `SETUP_CREDENTIAL`（首次初始化凭据）可在本地配置中查看。

同一服务器安装多套独立部署时，每套使用不同目录，并在首次启动前分别设置 `COMPOSE_PROJECT_NAME`（服务及数据卷名称前缀）、`HTTP_PORT`（本机入口端口）和 `NETWORK_PREFIX`（容器网络地址前缀）。同步调整 `TRUSTED_EDGE_PROXIES`（可信入口代理地址）及 Nginx 转发端口，避免复用另一套部署的数据卷、容器名称或网络。升级现有部署时保留原名称与数据卷关系，不要通过改名前缀来“升级”。

## 配置入口并启动

1. 为实际域名准备 HTTPS（加密网页连接）证书，按生成的 `agenteam.nginx.conf` 配置服务器的 Nginx（入口代理）。容器服务默认只监听本机 `127.0.0.1:8088`，由这个入口接收外部请求。
2. 核对 `.env` 中的 `PUBLIC_URL`、监听端口、可信代理及资源配置。配置邮件服务后，找回密码、邮箱验证和邮件邀请才能实际发送；启用 `container,production` 环境时补齐其要求的邮件与代理配置。
3. 保持数据库、应用和文件使用同一套持久卷，执行配置检查及启动：

```sh
docker compose config --quiet
docker compose up -d --no-build --wait --wait-timeout 300
```

访问实际域名，使用 `.env` 中的初始化凭据创建管理员和初始企业，再配置模型并开始使用。企业微信、飞书的应用及绑定步骤见 [企业消息说明](enterprise-messaging.md)。

## 数据库与连接驱动

默认数据库服务仍是官方 MySQL 8.4。后端使用 MariaDB Connector/J 3.5.10（Java 数据库连接驱动）连接 MySQL；驱动名称不表示需要把数据库换成 MariaDB。现有数据库地址、库名、账号和业务数据保持原来的对应关系。

自行配置后端时，`AGENTEAM_DB_URL` 使用以下格式，并通过独立环境变量提供账号和密码：

```text
jdbc:mariadb://数据库主机:3306/数据库名?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&preserveInstants=true
```

`UTC` 表示协调世界时，三个时间参数用于保持数据库会话与应用时间点的转换一致。旧配置中的 `connectionTimeZone=%2B00:00` 要改为 `connectionTimeZone=UTC`。若暂时保留 `jdbc:mysql:` 前缀，必须在地址参数中加上 `permitMysqlScheme=true`；新配置优先使用上面的格式。自行指定过驱动类的配置改为 `org.mariadb.jdbc.Driver`。

随包的私有容器网络使用 `sslMode=trust` 加密连接，但不验证服务器证书。连接其他网络中的数据库时，使用 `sslMode=verify-full` 并配置可信证书及匹配的主机名，配置方法见 [MariaDB 驱动加密连接说明](https://mariadb.com/docs/connectors/mariadb-connector-j/using-tls-ssl-with-mariadb-java-connector)。应用中的外部只读数据源在生产环境仍强制校验服务器身份。

同机两套部署共用 MySQL 时，提供数据库的一套加载 `deploy/compose/shared-mysql.yaml`，使用该库的一套加载 `deploy/compose/external-mysql.yaml`。每套应用使用独立库和专用账号。账号和网络配置见部署包根目录 README.md 的“共用一个 MySQL 实例”。

## 更新到最新版

根目录启动默认跟随最新正式版。使用固定版本部署包时，如需跟随最新版本，可将 `.env` 中以下五个镜像设为 `latest`（最新正式版标签），移除这些引用末尾原有的 `@sha256:...` 内容摘要；其他配置保持原值。此设置只需做一次：

```dotenv
APP_IMAGE=docker.io/stonewuu/agenteam-backend:latest
FRONTEND_IMAGE=docker.io/stonewuu/agenteam-frontend:latest
EXECUTOR_IMAGE=docker.io/stonewuu/agenteam-sandbox-server:latest
SANDBOX_IMAGE=docker.io/stonewuu/agenteam-sandbox-office:latest
DEPLOYMENT_IMAGE=docker.io/stonewuu/agenteam-deployment:latest
```

之后日常升级只需在部署目录执行：

```sh
docker compose pull
docker compose up -d
```

确认 `pull` 成功后再执行 `up`。镜像由项目发布流程构建，无需在服务器编译；Compose 会替换有变化的容器并保留持久数据，数据库迁移随应用启动自动执行。正常升级不需要重新初始化、生成密码或手工修改版本号。

部署文件镜像不属于常驻服务。只有发布说明明确要求调整部署结构或处理不兼容变更时，才需要按该版本说明操作。需要固定版本时，可以将业务镜像的 `latest` 换为同一正式版本号。

## 内存与异常任务

默认由主机和各服务管理内存。MySQL、Redis、前端、后端、网关和命令执行服务均未设置容器内存上限。需要单独限制某项服务时，可在 `.env` 设置 `MYSQL_MEMORY`、`BACKEND_MEMORY`、`FRONTEND_MEMORY` 或 `EXECUTOR_MEMORY`，例如 `MYSQL_MEMORY=2g`。运行工具命令的用户沙盒有独立的资源配额。4 GB 主机可按[低并发部署说明](web-browsing.md)启用低内存配置。

模型输入会在生成请求副本前检查内存预算；超出预算时任务明确失败，已保存的内容保持可读。旧工具结果通过引用重新读取完整内容，最近一轮结果保留原文。明确设置的任务时限继续生效；没有设置时限时，单次模型请求默认最多等待十五分钟没有输出，整项任务设为不限时也不会关闭这个保护。

后端和执行服务镜像在 Java 堆耗尽时退出，Compose 的 `restart: unless-stopped`（非人工停止时自动重启）负责重新启动。后端自动重启后，执行服务会重新连接其网络空间，无需手工重启整个部署。应用按已保存的执行状态处理过期任务；正在请求模型的任务结束为执行中断，不自动重复发送请求。此前输出与工具记录保留。若容器反复因内存不足退出，需要按实际负载增加内存或降低并发，不能仅反复重启。

需要按负载调整时，可给后端传入 `EXECUTION_WORKER_MAXIMUM_CONCURRENT`（同时领取的任务数，默认 10），`EXECUTION_MODEL_IDLE_TIMEOUT`（未配置任务时限时的模型无输出等待上限，默认 `PT15M`，即十五分钟）和 `EXECUTION_MODEL_MAXIMUM_INPUT_BYTES`（模型输入编码预算，默认按最大堆的三十二分之一计算，范围为 1 到 32 MiB）。这些设置不删除历史或完整工具输出。

## 数据备份

数据库、Redis 持久数据、业务文件、配置和保存密码的五个凭据卷应一同备份并定期验证恢复，普通镜像更新不要求重复完整恢复演练。停止服务用 `docker compose stop`；`docker compose down -v` 会删除数据卷，不用于普通升级。

## 查看项目版权信息

项目版权声明保存在 `licenses/agenteam/NOTICE`，版权名称为 stonewu。

解出的部署包在 `licenses/agenteam/LICENSE` 保存项目许可。后端、前端、执行器和办公镜像内的对应目录为 `/usr/share/licenses/agenteam/agenteam/`。可以使用 `docker cp` 从已创建的容器取得文件，不需要启动应用或连接数据库。

代码和依赖原有版权信息继续保留。固定版本号标签和完整版本清单继续提供，便于核对部署内容和安排更新。

## 基础组件更新

部署配置默认使用 `nginx:1-alpine`、`mysql:8`、`redis:7-alpine`，分别跟随当前大版本的更新。已有部署同步修改 `.env` 中的 `GATEWAY_IMAGE`、`MYSQL_IMAGE`、`REDIS_IMAGE`，并移除这三项旧的 `@sha256:...` 内容摘要后，`docker compose pull` 会拉取对应大版本的新镜像；执行 `docker compose up -d` 后才应用更新。

基础组件同样通过上述 `pull` 和 `up -d` 更新。跨大版本或存在不兼容数据变更时，按该版本说明安排迁移和恢复验证；普通兼容更新沿用日常备份策略。应用镜像的 `latest` 与基础组件的大版本标签分别管理，不使用数据库的 `latest` 自动跨大版本升级。
