#!/bin/sh
set -eu

# 读取 Docker 实际挂载来源，兼容 Linux、Docker Desktop 和自定义数据目录。
# 不把容器内路径误当成 Docker 主机路径，也不假定 Docker 数据目录的位置。
mount_source() {
    destination="$1"
    docker inspect --type=container --format="{{range .Mounts}}{{if eq .Destination \"$destination\"}}{{.Source}}{{end}}{{end}}" "$AGENTEAM_EXECUTOR_CONTAINER"
}

AGENTEAM_SANDBOX_HOST_MOUNT_ROOT="$(mount_source /var/lib/agenteam-executor)"
AGENTEAM_USER_WORKSPACES_HOST_ROOT="$(mount_source /var/lib/agenteam-projects)"
if [ -z "$AGENTEAM_SANDBOX_HOST_MOUNT_ROOT" ] || [ -z "$AGENTEAM_USER_WORKSPACES_HOST_ROOT" ]; then
    printf '%s\n' '无法识别执行目录或用户目录的持久挂载，请检查 Compose 卷配置。' >&2
    exit 1
fi
export AGENTEAM_SANDBOX_HOST_MOUNT_ROOT AGENTEAM_USER_WORKSPACES_HOST_ROOT

# 与业务后端使用相同文件所有者，避免生成的锁和控制目录使后端无法继续写入或清理。
# 仅加入 Docker 套接字的实际用户组，不修改套接字权限。
docker_group=$(stat -c '%g' /var/run/docker.sock)
case "$docker_group" in
    ''|*[!0-9]*)
        printf '%s\n' '无法读取 Docker 管理接口的用户组，请检查套接字挂载。' >&2
        exit 1
        ;;
esac
exec setpriv --reuid=10001 --regid=10001 --groups="$docker_group" --inh-caps=-all --ambient-caps=-all \
    java -jar /app/agenteam.jar --sandbox-server
