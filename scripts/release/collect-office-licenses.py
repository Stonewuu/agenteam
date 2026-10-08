"""在实际办公镜像内读取系统和 Python 原声明；不连接网络，不代替源代码分发义务核对。"""

import hashlib
import importlib.metadata
import json
import re
import subprocess
from pathlib import Path


def notice(path, name):
    raw = path.read_bytes()
    return {"name": name, "sha256": hashlib.sha256(raw).hexdigest(), "content": raw.decode("utf-8", errors="replace")}


def python_packages():
    result = []
    for distribution in importlib.metadata.distributions():
        attributions = []
        for name in distribution.files or []:
            parts = Path(str(name)).parts
            if not any(re.match(r"^(licen[cs]e|copying|notice|copyright|authors)(?:[._-].*)?$", part, re.I)
                       or part.lower() in ("licenses", "licences", "notices") for part in parts):
                continue
            path = Path(distribution.locate_file(name))
            if path.is_file() and path.stat().st_size <= 4 * 1024 * 1024:
                attributions.append(notice(path, str(name)))
        metadata = distribution.metadata
        result.append({"name": metadata.get("Name"), "version": distribution.version,
                       "license": metadata.get("License-Expression") or metadata.get("License"),
                       "licenseClassifiers": [item for item in metadata.get_all("Classifier", []) if item.startswith("License ::")],
                       "projectUrls": metadata.get_all("Project-URL", []), "homepage": metadata.get("Home-page"),
                       "attributions": attributions})
    return sorted(result, key=lambda value: value["name"].lower())


def system_packages():
    command = ["dpkg-query", "-W", "-f=${binary:Package}\t${Version}\t${source:Package}\t${source:Version}\t${Homepage}\n"]
    output = subprocess.run(command, check=True, capture_output=True, text=True).stdout
    packages = []
    for line in output.splitlines():
        name, version, source, source_version, homepage = line.split("\t", 4)
        plain = name.split(":", 1)[0]
        copyright_file = Path("/usr/share/doc") / plain / "copyright"
        if not copyright_file.is_file():
            copyright_file = Path("/usr/share/doc") / name / "copyright"
        packages.append({"name": name, "version": version, "sourcePackage": source,
                         "sourceVersion": source_version, "homepage": homepage,
                         "attributions": [notice(copyright_file, str(copyright_file))] if copyright_file.is_file() else []})
    common = [notice(path, str(path)) for path in sorted(Path("/usr/share/common-licenses").glob("*")) if path.is_file()]
    return packages, common


system, common_licenses = system_packages()
python = python_packages()
input_files = {name: hashlib.sha256((Path("/opt/agenteam") / name).read_bytes().replace(b"\r\n", b"\n")).hexdigest()
               for name in ("requirements.txt", "requirements.lock") if (Path("/opt/agenteam") / name).is_file()}
print(json.dumps({"formatVersion": 1,
                  "scope": "实际办公镜像的已安装系统包、Python 包及原许可文件；Node 和复制的运行环境单独核对，GPL/LGPL 对应源码安排仍需完成",
                  "inputFiles": input_files, "systemPackages": system, "pythonPackages": python, "systemCommonLicenses": common_licenses,
                  "systemWithoutAttributions": [entry["name"] for entry in system if not entry["attributions"]],
                  "pythonWithoutAttributions": [entry["name"] for entry in python if not entry["attributions"]]},
                 ensure_ascii=False, indent=2))
