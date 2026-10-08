"""在服务器生成只允许当前账号读取的部署配置，不覆盖已有密码。"""

import argparse
import os
import re
import secrets
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description="根据当前版本的镜像清单生成部署配置")
    parser.add_argument("--domain", required=True)
    parser.add_argument("--output", type=Path)
    arguments = parser.parse_args()
    domain = arguments.domain.lower()
    if not re.fullmatch(r"[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?", domain) or "." not in domain:
        raise ValueError("访问域名格式不正确")
    directory = Path(__file__).resolve().parent
    output = arguments.output or directory / ".env"
    if output.exists():
        raise FileExistsError("部署配置已经存在，请保留原有密码并直接编辑配置")

    configuration = {}
    for line in (directory / "release.env").read_text(encoding="utf-8-sig").splitlines():
        if line and not line.startswith("#"):
            key, value = line.split("=", 1)
            if not re.fullmatch(r"[A-Z_]+", key) or "\n" in value or "\r" in value:
                raise ValueError("发布文件中存在无效配置")
            configuration[key] = value
    configuration.update({
        "COMPOSE_PROJECT_NAME": "agenteam",
        "COMPOSE_PATH_SEPARATOR": ",",
        "COMPOSE_FILE": "compose.yaml,compose.registry.yaml,compose.single.yaml",
        "PUBLIC_DOMAIN": domain,
        "PUBLIC_URL": "https://" + domain,
        "SPRING_PROFILES_ACTIVE": "container",
        "NETWORK_PREFIX": "172.29.44",
        "TRUSTED_EDGE_PROXIES": "172.29.44.1/32",
        "BIND_ADDRESS": "127.0.0.1",
        "HTTP_PORT": "8088",
        "BACKEND_MEMORY": "1g",
        "MYSQL_MEMORY": "640m",
        "FRONTEND_MEMORY": "256m",
        "EXECUTOR_MEMORY": "256m",
        "SANDBOX_MEMORY_MB": "768",
        "SANDBOX_WORKSPACE_MB": "128",
        "SANDBOX_TEMPORARY_MB": "128",
        "SANDBOX_MAXIMUM_CONCURRENT": "2",
        "SANDBOX_MAXIMUM_RUNNING_CONTAINERS": "2",
        "SANDBOX_IDLE_SECONDS": "900",
        "SANDBOX_IDLE_SCAN_SECONDS": "30",
        "SANDBOX_USAGE_STALE_SECONDS": "60",
        "SANDBOX_STOP_SECONDS": "10",
        "SANDBOX_TIMEOUT_SECONDS": "1800",
        "MAIL_HOST": "",
        "MAIL_PORT": "587",
        "MAIL_USERNAME": "",
        "MAIL_PASSWORD": "",
        "MAIL_FROM": "",
    })
    for key in ("DB_PASSWORD", "DB_ROOT_PASSWORD", "REDIS_PASSWORD", "SANDBOX_TOKEN", "SETUP_CREDENTIAL"):
        configuration[key] = secrets.token_hex(32)
    nginx_configuration = output.parent / "agenteam.nginx.conf"
    if nginx_configuration.exists():
        raise FileExistsError("Nginx 配置已经存在，请检查后再初始化")
    nginx_content = (directory / "nginx-host.conf.template").read_text(encoding="utf-8")
    nginx_content = nginx_content.replace("__PUBLIC_DOMAIN__", domain)
    os.umask(0o077)
    with output.open("x", encoding="utf-8", newline="\n") as destination:
        destination.write("# 实际部署密码只保存在服务器，不得提交或写入镜像。\n")
        destination.writelines(f"{key}={value}\n" for key, value in configuration.items())
    with nginx_configuration.open("x", encoding="utf-8", newline="\n") as destination:
        destination.write(nginx_content)
    print(f"已生成部署配置：{output}")
    print("初始化凭据位于配置文件的 SETUP_CREDENTIAL；邮件服务尚未配置。")


if __name__ == "__main__":
    main()
