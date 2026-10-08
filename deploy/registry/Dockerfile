# 先用发布脚本准备明确输入；只保存非敏感文件，实际密码在服务器生成。
FROM scratch
COPY compose.yaml compose.registry.yaml compose.single.yaml /deployment/
COPY compose.shared-mysql.yaml compose.external-mysql.yaml /deployment/
COPY registry/configure-server.py registry/release.env registry/nginx-host.conf.template /deployment/
COPY nginx/15-agenteam-config.sh nginx/default.conf.template nginx/api.conf /deployment/nginx/
COPY registry/README.md /deployment/README.md
COPY docs/ /deployment/docs/
COPY licenses/ /deployment/licenses/
CMD ["true"]
