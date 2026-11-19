# 先用发布脚本准备明确输入；只保存非敏感文件，实际密码在服务器生成。
FROM scratch
COPY docker-compose.yaml .env.example /deployment/
COPY compose/ /deployment/deploy/compose/
COPY container/init-storage.sh /deployment/deploy/container/
COPY agenteam-sandboxes.slice /deployment/deploy/
COPY registry/configure-server.py registry/release.env registry/nginx-host.conf.template /deployment/
COPY nginx/ /deployment/deploy/nginx/
COPY registry/README.md /deployment/README.md
COPY docs/ /deployment/docs/
COPY licenses/ /deployment/licenses/
CMD ["true"]
