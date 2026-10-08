"""验证结构检查实际读取了正文，不仅检查扩展名和压缩包。"""

import json
import subprocess
import tempfile
from pathlib import Path
from zipfile import ZipFile


def verify():
    for kind, main in (("docx", "word/document.xml"), ("pptx", "ppt/presentation.xml")):
        source = Path("/workspace/outputs/sample." + kind)
        checked = subprocess.run(["agenteam-office", "check", str(source)], check=True, capture_output=True, text=True)
        assert json.loads(checked.stdout)["valid"] is True
        with tempfile.TemporaryDirectory(prefix="office-invalid-") as directory:
            invalid = Path(directory) / source.name
            with ZipFile(source) as original, ZipFile(invalid, "w") as target:
                for entry in original.infolist():
                    target.writestr(entry, b"<broken" if entry.filename == main else original.read(entry))
            rejected = subprocess.run(["agenteam-office", "check", str(invalid)], capture_output=True)
            assert rejected.returncode != 0, "正文损坏的文件不能通过结构检查"


if __name__ == "__main__":
    verify()
