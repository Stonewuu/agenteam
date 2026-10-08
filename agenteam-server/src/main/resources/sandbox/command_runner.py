"""分别管理每条命令，退出时清理其后代进程，保持用户容器运行。"""

import ctypes
import fcntl
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time
import traceback


ROOT = Path("/tmp/agenteam-commands")


def write_state(directory, value):
    pending = directory / ("status." + str(os.getpid()) + ".tmp")
    pending.write_text(json.dumps(value), encoding="utf-8")
    os.replace(pending, directory / "status.json")


def process_info(pid):
    try:
        fields = Path("/proc", str(pid), "stat").read_text().rsplit(")", 1)[1].split()
        return int(fields[1]), fields[19]
    except (FileNotFoundError, ProcessLookupError):
        return None


def read_state(directory):
    path = directory / "status.json"
    return json.loads(path.read_text()) if path.exists() else {"state": "missing"}


def descendants():
    # 子进程收养机制使双重派生和脱离会话的后台进程仍归本次管理程序清理。
    entries = {}
    for path in Path("/proc").iterdir():
        if path.name.isdigit():
            info = process_info(int(path.name))
            if info:
                entries[int(path.name)] = info
    owned = {os.getpid()}
    while True:
        added = {pid for pid, info in entries.items() if info[0] in owned} - owned
        if not added:
            break
        owned.update(added)
    owned.discard(os.getpid())
    return {pid: entries[pid][1] for pid in owned}


def cleanup_children():
    deadline = time.monotonic() + 5
    while True:
        owned = descendants()
        for pid, started in owned.items():
            current = process_info(pid)
            if current and current[1] == started:
                try:
                    os.kill(pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
        while True:
            try:
                pid, _ = os.waitpid(-1, os.WNOHANG)
                if pid == 0:
                    break
            except ChildProcessError:
                break
        if not descendants():
            return
        if time.monotonic() >= deadline:
            raise RuntimeError("命令的后台进程尚未全部退出")
        time.sleep(0.02)


def run(directory, script, working, stdout, stderr, seconds):
    if ctypes.CDLL(None, use_errno=True).prctl(36, 1, 0, 0, 0) != 0:
        raise RuntimeError("无法启用命令后台进程清理")
    os.umask(0)
    with (directory / "lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        previous = read_state(directory)
        if previous["state"] != "missing" or (directory / "cancel").exists():
            if previous["state"] == "missing":
                write_state(directory, {"state": "completed", "exitCode": 143, "timedOut": False})
            return 0
        write_state(directory, {"state": "running", "pid": os.getpid(), "started": process_info(os.getpid())[1]})
    process = None
    result = {"state": "completed", "exitCode": 1, "timedOut": False}
    try:
        deadline = time.monotonic() + seconds
        with open(stdout, "wb", buffering=0) as out, open(stderr, "wb", buffering=0) as err:
            if (directory / "cancel").exists():
                result["exitCode"] = 143
            else:
                process = subprocess.Popen(["sh", script], cwd=working, stdout=out, stderr=err,
                                           stdin=subprocess.DEVNULL, start_new_session=True)
                while process.poll() is None:
                    if (directory / "cancel").exists():
                        result["exitCode"] = 143
                        break
                    if time.monotonic() >= deadline:
                        result.update(exitCode=124, timedOut=True)
                        break
                    time.sleep(0.05)
                else:
                    result["exitCode"] = process.returncode
    except Exception:
        traceback.print_exc()
        result = {"state": "completed", "exitCode": 1, "timedOut": False, "error": "命令管理程序执行失败"}
    finally:
        try:
            cleanup_children()
        except Exception:
            traceback.print_exc()
            result = {"state": "unknown", "error": "命令后台进程清理未完成"}
        write_state(directory, result)
    return 0


def inspect(directory, cancel):
    with (directory / "lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        state = read_state(directory)
        if cancel:
            (directory / "cancel").touch()
            if state["state"] == "missing":
                state = {"state": "completed", "exitCode": 143, "timedOut": False}
                write_state(directory, state)
        if state["state"] == "running":
            info = process_info(state["pid"])
            if not info or info[1] != state["started"]:
                # 无法确认归属时不终止其他命令，也不把丢失管理程序当成执行完成。
                state = {"state": "unknown", "error": "命令管理进程已退出，执行结果待核实"}
        print(json.dumps(state))


def main():
    action, token = sys.argv[1:3]
    if len(token) != 36 or any(c not in "0123456789abcdef-" for c in token):
        raise ValueError("命令编号无效")
    directory = ROOT / token
    directory.mkdir(parents=True, exist_ok=True)
    if action == "run":
        return run(directory, *sys.argv[3:7], float(sys.argv[7]))
    if action not in ("status", "cancel"):
        raise ValueError("命令管理操作无效")
    inspect(directory, action == "cancel")
    return 0


if __name__ == "__main__":
    sys.exit(main())
