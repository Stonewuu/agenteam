#!/bin/sh
set -eu
umask 077

# 凭据各自保存在命名卷中，服务只挂载自己需要的卷。
write_secret() (
    file=$1
    configured=$2
    if [ -s "$file" ]; then
        if [ -z "$configured" ] || [ "$(cat "$file")" = "$configured" ]; then
            exit 0
        fi
    fi
    if [ -e "$file" ] && [ ! -s "$file" ] && [ -z "$configured" ]; then
        echo "Empty credential file: $file. Restore it or supply the original value." >&2
        exit 1
    fi
    temp=$(mktemp "${file%/*}/.credential.XXXXXX")
    trap 'rm -f "$temp"' EXIT
    if [ -n "$configured" ]; then
        printf '%s' "$configured" > "$temp"
    else
        random=$(od -An -v -N32 -tx1 /dev/urandom | tr -d ' \n')
        [ "${#random}" -eq 64 ]
        printf '%s' "$random" > "$temp"
    fi
    # MySQL、Redis 和后端在各自容器内的账号不同，消费方只读挂载。
    chmod 0444 "$temp"
    if [ -n "$configured" ]; then
        mv -f "$temp" "$file"
    else
        # 两次初始化同时运行时，只有第一个生成的值生效。
        ln "$temp" "$file" || [ -s "$file" ]
    fi
)

keys=/run/agenteam-secrets
if [ -d /existing-mysql/mysql ]; then
    if { [ -z "${DB_PASSWORD:-}" ] && [ ! -s "$keys/database/AGENTEAM_DB_PASSWORD" ]; } ||
       { [ -z "${DB_ROOT_PASSWORD:-}" ] && [ ! -s "$keys/mysql-root/MYSQL_ROOT_PASSWORD" ]; }; then
        echo 'Existing MySQL data requires the original database credentials. Supply the original .env or restore the credential volumes.' >&2
        exit 1
    fi
fi
write_secret "$keys/database/AGENTEAM_DB_PASSWORD" "${DB_PASSWORD:-}"
write_secret "$keys/mysql-root/MYSQL_ROOT_PASSWORD" "${DB_ROOT_PASSWORD:-}"
write_secret "$keys/cache/AGENTEAM_REDIS_PASSWORD" "${REDIS_PASSWORD:-}"
write_secret "$keys/executor/AGENTEAM_SANDBOX_REMOTE_TOKEN" "${SANDBOX_TOKEN:-}"
if [ ! -s "$keys/setup/AGENTEAM_SETUP_CREDENTIAL" ] && [ -z "${SETUP_CREDENTIAL:-}" ]; then
    echo 'After the backend starts, read the setup credential with docker compose exec backend cat /run/agenteam-secrets/setup/AGENTEAM_SETUP_CREDENTIAL'
fi
write_secret "$keys/setup/AGENTEAM_SETUP_CREDENTIAL" "${SETUP_CREDENTIAL:-}"
chown 10001:10001 /app-data /user-workspaces /executor-data "$@"
