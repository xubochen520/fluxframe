#!/usr/bin/env python3
"""Minimal SSH/SFTP helper for deploying to a remote Linux host.

Usage:
  python deploy/remote.py exec  "<shell command>"            # run command, stream output
  python deploy/remote.py put   <local> <remote>             # upload one file
  python deploy/remote.py putdir <localdir> <remotedir>      # upload a directory tree (recursive)

Connection info comes from env vars (with defaults):
  DEPLOY_HOST=192.168.1.100  DEPLOY_USER=root  DEPLOY_PASS=<必填>  DEPLOY_PORT=22
"""
import os
import posixpath
import stat
import sys
import time

import paramiko

# 远端输出含中文/符号（✓ 等），Windows 控制台默认 GBK 会抛 UnicodeEncodeError：统一按 UTF-8 + 替换解码
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except Exception:  # noqa: BLE001 - 老版本 Python 或非文本流时忽略
        pass

HOST = os.environ.get("DEPLOY_HOST", "192.168.1.100")
USER = os.environ.get("DEPLOY_USER", "root")
PASS = os.environ.get("DEPLOY_PASS", "")
PORT = int(os.environ.get("DEPLOY_PORT", "22"))


def connect() -> paramiko.SSHClient:
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    last = None
    for attempt in range(3):
        try:
            client.connect(
                HOST,
                port=PORT,
                username=USER,
                password=PASS,
                timeout=20,
                banner_timeout=30,
                auth_timeout=30,
                allow_agent=False,
                look_for_keys=False,
            )
            return client
        except Exception as exc:  # noqa: BLE001
            last = exc
            time.sleep(2)
    raise SystemExit(f"SSH connect failed: {last}")


def do_exec(command: str) -> int:
    client = connect()
    try:
        stdin, stdout, stderr = client.exec_command(command, timeout=7200, get_pty=True)
        stdin.close()
        chan = stdout.channel
        while True:
            while chan.recv_ready():
                sys.stdout.write(chan.recv(65536).decode("utf-8", "replace"))
                sys.stdout.flush()
            while chan.recv_stderr_ready():
                sys.stderr.write(chan.recv_stderr(65536).decode("utf-8", "replace"))
                sys.stderr.flush()
            if chan.exit_status_ready() and not chan.recv_ready() and not chan.recv_stderr_ready():
                break
            time.sleep(0.05)
        code = chan.recv_exit_status()
        sys.stdout.flush()
        sys.stderr.flush()
        return code
    finally:
        client.close()


def do_put(local: str, remote: str) -> int:
    client = connect()
    try:
        sftp = client.open_sftp()
        parent = posixpath.dirname(remote)
        if parent:
            _mkdirs(sftp, parent)
        sftp.put(local, remote)
        size = sftp.stat(remote).st_size
        print(f"uploaded {local} -> {remote} ({size} bytes)")
        sftp.close()
        return 0
    finally:
        client.close()


def _mkdirs(sftp: paramiko.SFTPClient, path: str) -> None:
    parts = [p for p in path.split("/") if p]
    cur = "/" if path.startswith("/") else ""
    for part in parts:
        cur = posixpath.join(cur, part) if cur else part
        try:
            sftp.stat(cur)
        except IOError:
            sftp.mkdir(cur)


def do_putdir(localdir: str, remotedir: str) -> int:
    client = connect()
    try:
        sftp = client.open_sftp()
        _mkdirs(sftp, remotedir)
        count = 0
        for root, dirs, files in os.walk(localdir):
            rel = os.path.relpath(root, localdir).replace("\\", "/")
            target = remotedir if rel == "." else posixpath.join(remotedir, rel)
            _mkdirs(sftp, target)
            for name in files:
                lp = os.path.join(root, name)
                rp = posixpath.join(target, name)
                mode = os.stat(lp).st_mode
                if stat.S_ISLNK(mode):
                    continue
                sftp.put(lp, rp)
                count += 1
        print(f"uploaded {count} files from {localdir} -> {remotedir}")
        sftp.close()
        return 0
    finally:
        client.close()


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    action = sys.argv[1]
    if action == "exec":
        return do_exec(sys.argv[2])
    if action == "put":
        return do_put(sys.argv[2], sys.argv[3])
    if action == "putdir":
        return do_putdir(sys.argv[2], sys.argv[3])
    print(f"unknown action: {action}")
    return 2


if __name__ == "__main__":
    sys.exit(main())
