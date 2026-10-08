#!/bin/sh
set -eu

case "$PUBLIC_URL" in
    http://*) AGENTEAM_PUBLIC_SCHEME=http ;;
    https://*) AGENTEAM_PUBLIC_SCHEME=https ;;
    *)
        printf '%s\n' 'PUBLIC_URL 必须是以 http:// 或 https:// 开头的访问地址。' >&2
        exit 1
        ;;
esac
# 只替换部署变量，保留 Nginx 请求变量和原始模板，使重启仍能重新生成配置。
sed "s/\${AGENTEAM_PUBLIC_SCHEME}/$AGENTEAM_PUBLIC_SCHEME/g" /etc/nginx/agenteam.conf.template > /etc/nginx/conf.d/default.conf

: > /etc/nginx/agenteam-realip.conf
for address in ${TRUSTED_EDGE_PROXIES:-}; do
    case "$address" in
        *[!0-9a-fA-F:./]*)
            printf '%s\n' 'TRUSTED_EDGE_PROXIES 只能填写实际代理的地址或网段。' >&2
            exit 1
            ;;
    esac
    printf 'set_real_ip_from %s;\n' "$address" >> /etc/nginx/agenteam-realip.conf
done
printf 'real_ip_header X-Forwarded-For;\nreal_ip_recursive on;\n' >> /etc/nginx/agenteam-realip.conf

# 直接运行传入的 Nginx 命令，使容器的停止信号交给实际服务进程。
exec "$@"
