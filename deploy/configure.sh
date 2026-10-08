#!/bin/sh
set -eu

script_directory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
mode=${1:-single}
destination=${2:-"$script_directory/.env"}
case "$mode" in
    single) public_url=http://localhost:8088 ;;
    split) public_url=http://localhost:3000 ;;
    *)
        printf '%s\n' '使用方法：sh configure.sh [single|split] [配置文件路径]' >&2
        exit 1
        ;;
esac
if [ -e "$destination" ]; then
    printf '%s\n' '配置文件已经存在，请直接编辑它；初始化脚本不会覆盖已有密码。' >&2
    exit 1
fi

configuration=$(tr -d '\r' < "$script_directory/.env.example")
configuration=$(printf '%s\n' "$configuration" | sed "s/^DEPLOY_MODE=single$/DEPLOY_MODE=$mode/;s|^PUBLIC_URL=.*$|PUBLIC_URL=$public_url|")
for key in DB_PASSWORD DB_ROOT_PASSWORD REDIS_PASSWORD SANDBOX_TOKEN SETUP_CREDENTIAL; do
    value=$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')
    configuration=$(printf '%s\n' "$configuration" | sed "s/^$key=$/$key=$value/")
done
umask 077
set -C
printf '%s\n' "$configuration" > "$destination"
printf '%s\n' "已生成配置：$destination" '请检查顶部的端口与访问地址，然后在 deploy 目录执行 docker compose up -d --build。' '初始化凭据为配置文件中的 SETUP_CREDENTIAL，不会输出到终端。'
