# 让智能体读取网页和拉取代码

仅靠直接下载网页，可能拿到页面脚本或空的页面框架。当前源码提供两种读取方式：内置 `read_url`（网页读取工具）提取静态网页正文、标题和链接；沙盒中的 `agenteam-browser`（浏览器读取命令）执行页面脚本后提取可见正文。沙盒也提供 Git（代码版本管理工具），用于拉取用户指定的代码。

## 启用环境

这项能力需要包含浏览器的新沙盒镜像。源码部署在社区仓库根目录执行：

```sh
docker build -t agenteam/sandbox-office:local deploy/sandbox
```

等待当前沙盒任务结束后重建已有命令容器，使它们使用新镜像；挂载在容器之外的项目文件仍保留。已经发布的旧镜像不会因本地修改自动获得这些能力。

默认仍禁止沙盒联网。Compose（容器组合部署工具）使用 `.env` 中的 `SANDBOX_NETWORK`，设为 `bridge` 或已创建的专用 Docker 网络即可允许该网络范围内的访问；直接运行后端时设置 `AGENTEAM_SANDBOX_NETWORK`。后端和独立执行服务必须使用一致的配置，修改后重启服务。网络访问范围由部署方配置。

沙盒进程和线程总数上限默认调整为 128，配置项为 `AGENTEAM_SANDBOX_PROCESS_LIMIT`，Compose 使用 `SANDBOX_PROCESS_LIMIT`。旧环境若明确设置为 64，Chromium 可能无法创建页面，需要同步调整后重建容器。

## 使用方式

在智能体对话中直接要求读取网址或拉取仓库即可；模型已经获得沙盒工具的使用说明。需要检查环境时，可让智能体执行：

```sh
agenteam-browser read https://example.com
agenteam-browser read https://example.com --wait-for article --max-chars 12000
git --version
git clone --depth 1 https://github.com/用户/仓库.git
```

浏览器返回实际标题、访问后的网址、正文和最多四十个链接。`--wait-for` 等待页面元素，`--wait-ms` 设置正文提取前等待时间，`--timeout-ms` 设置最多一百二十秒的读取时限。超长正文返回 `truncated`（是否仍有未读取内容）和 `nextStart`（下一段起点），通过 `--start` 按字符位置继续读取。分段读取会重新访问页面，内容变化时应重新核对。

浏览器每次使用独立会话，不保存登录资料或额外结果文件。需要登录、验证码或明确拒绝访问的网站仍可能读取失败；智能体应使用其他公开来源或说明所需访问条件。需要页面交互时，可以在沙盒使用已安装的 Node.js `playwright` 库。

## 4 GB 测试服务器

浏览器只在读取时启动，完成、超时或取消后释放进程。正文读取不加载图片、音视频、字体或额外弹窗；超长正文分段时只为当前片段建立字符数组。镜像只安装 [Playwright 官方支持的无界面浏览器程序](https://playwright.dev/docs/browsers#chromium-headless-shell)，不额外下载桌面浏览器；这减少镜像体积，运行内存仍由下面的限制管理。

Linux 服务器可以在原 `COMPOSE_FILE`（容器部署文件列表）末尾追加 `compose.low-memory.yaml`。启用前，从本部署文件目录执行：

```sh
sudo install -m 0644 agenteam-sandboxes.slice /etc/systemd/system/agenteam-sandboxes.slice
sudo systemctl daemon-reload
sudo systemctl start agenteam-sandboxes.slice
docker volume create agenteam-sandbox-capacity
```

要求 Docker 使用 systemd（Linux 服务与资源管理工具）和第二版内核资源分组。先用 `docker info --format '{{.CgroupDriver}} {{.CgroupVersion}}'` 确认输出为 `systemd 2`；其他环境需配置等效的主机资源限制后再启用此方案。

同一主机上的站点必须使用相同的共享名额卷和资源分组。配置生效后：

- 所有站点共同只执行一条沙盒命令，其他调用等待；等待也计算在原调用时限内，取消后不会继续启动浏览器。
- 每个用户仍使用独立文件目录和独立浏览器会话，共享卷只包含执行名额的锁文件；锁随执行进程退出自动释放。
- 单个沙盒上限为 512 MiB（每单位 1048576 字节），整台主机所有沙盒合计硬上限为 640 MiB，并禁止使用额外交换空间；空闲命令容器保留 30 秒，容量不足时优先回收空闲容器。
- 每个站点后端、前端、执行服务和入口分别限制为 512、160、160、32 MiB。两个站点共用一个 640 MiB 数据库、各使用一个 64 MiB 缓存服务，加上全部沙盒的 640 MiB 上限，共为 3136 MiB。启用前用 `free -m` 核对实际物理内存，并为操作系统和主机管理进程至少预留 768 MiB。缓存服务最多保存 48 MiB 数据，剩余空间供进程和持久化操作使用。

这适用于低并发测试环境，多用户的沙盒任务会排队，不能据此承诺任意并发。超出单次内存或调用时限的网页可能读取失败，错误仍返回模型选择其他方式。后台常驻服务和模型请求也需要观察实际占用；如果主机还运行其他应用，应另行计算它们的资源。

查看总限制与当前用量：

```sh
systemctl show agenteam-sandboxes.slice -p MemoryCurrent -p MemoryHigh -p MemoryMax -p MemorySwapMax
docker stats --no-stream
```

修改后先等待正在执行的任务结束，再更新后端和执行服务并回收旧命令容器；保留业务卷和原有密钥。仅降低单个容器上限而没有配置共享名额，不能限制多个站点的总并发。

## 结果与失败处理

工具正文优先直接交给模型，不再自动生成工具结果附件。完整脱敏内容保存在已有调用记录中，超大结果按需读取；旧会话的附件仍可访问。命令的 `stdoutTruncated`、`stderrTruncated` 表示标准输出和错误输出是否被截短，完整输出已经返回时无需再次读文件。

参数错误、未知工具和普通执行异常会返回模型，让模型修正或选择其他工具，最终通过文本答复结束。外部写入结果不确定时保留该工具的真实状态，并阻止同任务内的相同写入重复发送。明确取消、配置的运行上限、访问资格失效或无法保存执行状态的故障仍会停止运行。
