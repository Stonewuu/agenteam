"""在用户容器内提供真实终端；标准输入断开时清理本终端的所有后代进程。"""

import base64
import ctypes
import errno
import fcntl
import json
import os
import pty
import selectors
import shutil
import signal
import struct
import sys
import termios
import time
from pathlib import Path
from runner import cleanup_children, descendants


def emit(value):
    sys.stdout.write(json.dumps(value, ensure_ascii=True) + "\n")
    sys.stdout.flush()


def resize(fd, cols, rows):
    fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack("HHHH", rows, cols, 0, 0))
    # 记录输出对应的尺寸，重新连接时才能按原来的换行和光标位置恢复内容。
    marker = f"\x1b]777;agenteam-size;{cols};{rows}\x07".encode("ascii")
    emit({"output": base64.b64encode(marker).decode("ascii")})


def run():
    if ctypes.CDLL(None, use_errno=True).prctl(36, 1, 0, 0, 0) != 0:
        raise RuntimeError("无法启用终端进程清理")
    cols, rows = int(sys.argv[1]), int(sys.argv[2])
    pid, master = pty.fork()
    if pid == 0:
        os.chdir("/workspace")
        os.environ.update(TERM="xterm-256color", COLORTERM="truecolor", LANG="C.UTF-8")
        shell = shutil.which("bash") or "/bin/sh"
        if shell.endswith("bash"):
            os.environ["PS1"] = "\\[\\e[38;5;75m\\]\\w\\[\\e[0m\\] \\[\\e[38;5;114m\\]$\\[\\e[0m\\] "
            os.execv(shell, [shell, "--noprofile", "--norc", "-i"])
        os.environ["PS1"] = "$ "
        os.execv(shell, [shell, "-i"])
    resize(master, cols, rows)
    os.set_blocking(master, False)
    os.set_blocking(sys.stdin.fileno(), False)
    selector = selectors.DefaultSelector()
    selector.register(master, selectors.EVENT_READ)
    selector.register(sys.stdin.fileno(), selectors.EVENT_READ)
    incoming = b""
    pending = b""
    previous = None
    checked = 0
    try:
        while True:
            for key, events in selector.select(0.05):
                if key.fd == master:
                    try:
                        data = os.read(master, 16384)
                    except OSError as failure:
                        if failure.errno == errno.EIO:
                            return
                        raise
                    if not data:
                        return
                    emit({"output": base64.b64encode(data).decode("ascii")})
                else:
                    data = os.read(key.fd, 65536)
                    if not data:
                        return
                    incoming += data
                    if len(incoming) > 262144:
                        raise ValueError("终端输入过长")
                    while b"\n" in incoming:
                        line, incoming = incoming.split(b"\n", 1)
                        request = json.loads(line)
                        if request.get("close"):
                            return
                        if "data" in request:
                            pending += request["data"].encode("utf-8")
                            if len(pending) > 262144:
                                raise ValueError("终端输入过快")
                        if "cols" in request:
                            resize(master, request["cols"], request["rows"])
            if pending:
                try:
                    written = os.write(master, pending)
                    pending = pending[written:]
                except BlockingIOError:
                    pass
            if time.monotonic() - checked > 0.3:
                checked = time.monotonic()
                try:
                    directory = os.readlink(f"/proc/{pid}/cwd")
                    # 后台子进程也属于用户仍在运行的命令；空闲 shell 本身不占用环境。
                    shell_state = Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()[0]
                    busy = shell_state == "R" or os.tcgetpgrp(master) != pid or bool(set(descendants()) - {pid})
                except (FileNotFoundError, ProcessLookupError):
                    return
                state = {"directory": directory, "busy": busy}
                if state != previous:
                    emit(state)
                    previous = state
    finally:
        selector.close()
        cleanup_children()
        os.close(master)


if __name__ == "__main__":
    try:
        run()
        emit({"closed": True})
    except (BrokenPipeError, KeyboardInterrupt):
        cleanup_children()
    except Exception:
        # 不将用户输入或终端输出回显到服务日志。
        sys.stderr.write("终端连接结束，进程管理未能完成。\n")
        sys.exit(1)
