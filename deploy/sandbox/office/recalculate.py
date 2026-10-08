"""使用系统 Python 的 LibreOffice 接口重算表格，禁用宏和外部链接更新。"""

import subprocess
import sys
import tempfile
import time
import uuid
from pathlib import Path

import uno
from com.sun.star.beans import PropertyValue


def property_value(name, value):
    entry = PropertyValue()
    entry.Name = name
    entry.Value = value
    return entry


def main(source, target):
    source, target = Path(source).resolve(), Path(target).resolve()
    if source == target:
        raise ValueError("重新计算必须另存文件，不能覆盖输入原件")
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists():
        raise ValueError("目标文件已经存在，请使用新文件名")
    with tempfile.TemporaryDirectory(prefix="agenteam-calc-") as profile:
        pipe = "agenteam_" + uuid.uuid4().hex
        process = subprocess.Popen(["soffice", "-env:UserInstallation=" + Path(profile).as_uri(), "--headless", "--norestore",
                                    f"--accept=pipe,name={pipe};urp;StarOffice.ComponentContext"],
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        document = None
        try:
            context = uno.getComponentContext()
            resolver = context.ServiceManager.createInstanceWithContext("com.sun.star.bridge.UnoUrlResolver", context)
            deadline = time.monotonic() + 30
            while True:
                try:
                    remote = resolver.resolve(f"uno:pipe,name={pipe};urp;StarOffice.ComponentContext")
                    break
                except Exception:
                    if time.monotonic() >= deadline or process.poll() is not None:
                        raise RuntimeError("表格计算程序未能启动")
                    time.sleep(0.1)
            desktop = remote.ServiceManager.createInstanceWithContext("com.sun.star.frame.Desktop", remote)
            options = (property_value("Hidden", True), property_value("ReadOnly", False),
                       property_value("MacroExecutionMode", 0), property_value("UpdateDocMode", 0))
            document = desktop.loadComponentFromURL(source.as_uri(), "_blank", 0, options)
            if document is None:
                raise ValueError("表格无法打开")
            document.calculateAll()
            document.storeAsURL(target.as_uri(), (property_value("FilterName", "Calc MS Excel 2007 XML"),
                                                 property_value("Overwrite", False)))
        finally:
            try:
                if document is not None:
                    document.close(True)
            finally:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
