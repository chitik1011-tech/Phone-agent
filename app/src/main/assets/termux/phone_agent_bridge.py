"""Loopback PTY transport for Phone Agent. Python 3, standard library only."""

import base64
import binascii
import errno
import fcntl
import hmac
import json
import os
from pathlib import Path
import pty
import secrets
import select
import signal
import socket
import socketserver
import struct
import subprocess
import sys
import termios
import threading
import time
import uuid

VERSION = 1
MAX_TABS = 8
MAX_REQUEST = 128 * 1024
MAX_RESPONSE = 192 * 1024
MAX_HISTORY = 512 * 1024
MAX_INPUT = 24 * 1024
MAX_READ = 64 * 1024
MAX_COMMAND_OUTPUT = 32 * 1024
MAX_COMMAND_BYTES = 64 * 1024
MAX_COMMANDS = 3
IDLE_SECONDS = 20 * 60
BRIDGE_DIR = Path.home() / ".local" / "share" / "phone-agent-bridge"
CHILD_SETUP = """
import fcntl, os, sys, termios
fcntl.ioctl(0, termios.TIOCSCTTY, 0)
os.execv(sys.argv[1], [sys.argv[1], "-l"])
"""


def recv_exact(sock, n):
    data = bytearray()
    while len(data) < n:
        chunk = sock.recv(n - len(data))
        if not chunk:
            raise EOFError
        data.extend(chunk)
    return bytes(data)


def frame(sock, size_limit):
    n = struct.unpack("!I", recv_exact(sock, 4))[0]
    if not 0 < n <= size_limit:
        raise ValueError("Invalid frame length.")
    return recv_exact(sock, n)


def send_frame(sock, body):
    sock.sendall(struct.pack("!I", len(body)) + body)


def signature(key, kind, client, server, body=b""):
    return hmac.digest(key, kind + client + server + body, "sha256")


def encode(message):
    return json.dumps(message, ensure_ascii=True, separators=(",", ":")).encode("ascii")


def ask(connection, message):
    """Used by the provisioning process to authenticate an existing server."""
    key = bytes.fromhex(connection["key"])
    body = encode(message)
    with socket.create_connection(("127.0.0.1", connection["port"]), timeout=1) as sock:
        client = secrets.token_bytes(32)
        sock.sendall(client)
        server = recv_exact(sock, 32)
        if not hmac.compare_digest(recv_exact(sock, 32), signature(key, b"s", client, server)):
            raise PermissionError("Invalid server signature.")
        send_frame(sock, body)
        sock.sendall(signature(key, b"q", client, server, body))
        reply = frame(sock, MAX_RESPONSE)
        if not hmac.compare_digest(recv_exact(sock, 32), signature(key, b"r", client, server, reply)):
            raise PermissionError("Invalid message signature.")
        return json.loads(reply)


def terminal_size(fd, rows, columns):
    rows, columns = min(200, max(2, rows)), min(500, max(2, columns))
    fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack("HHHH", rows, columns, 0, 0))


class Terminal:
    def __init__(self, directory, rows, columns):
        self.id = uuid.uuid4().hex
        self.directory = directory
        self.lock = threading.Lock()
        self.history = bytearray()
        self.offset = 0
        self.exit_code = None
        self.finished_time = None
        self.stopping = False
        self.fd, slave = pty.openpty()
        try:
            terminal_size(slave, rows, columns)
            os.set_blocking(self.fd, False)
            shell = os.getenv("SHELL") or str(Path(os.getenv("PREFIX", "/usr")) / "bin" / "bash")
            env = dict(os.environ, TERM="xterm-256color", COLORTERM="truecolor")
            self.process = subprocess.Popen(
                [sys.executable, "-c", CHILD_SETUP, shell],
                stdin=slave,
                stdout=slave,
                stderr=slave,
                cwd=directory,
                env=env,
                start_new_session=True,
            )
        except BaseException:
            os.close(self.fd)
            raise
        finally:
            os.close(slave)
        threading.Thread(target=self.read, daemon=True).start()

    def read(self):
        try:
            while True:
                if not select.select([self.fd], [], [], 0.5)[0]:
                    if self.stopping or self.process.poll() is not None:
                        break
                    continue
                try:
                    data = os.read(self.fd, 32 * 1024)
                except BlockingIOError:
                    continue
                except OSError as error:
                    if error.errno == errno.EIO:
                        break
                    raise
                if not data:
                    break
                with self.lock:
                    self.history.extend(data)
                    excess = len(self.history) - MAX_HISTORY
                    if excess > 0:
                        del self.history[:excess]
                        self.offset += excess
        finally:
            with self.lock:
                os.close(self.fd)
                self.fd = -1
            try:
                code = self.process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                self.process.kill()
                code = self.process.wait()
            with self.lock:
                self.exit_code = code
                self.finished_time = time.monotonic()

    def snapshot(self):
        with self.lock:
            return {"id": self.id, "directory": self.directory, "exit": self.exit_code}

    def poll(self, offset):
        with self.lock:
            start = min(len(self.history), max(0, offset - self.offset))
            body = self.history[start:start + MAX_READ]
            end = start + len(body)
            return {
                "offset": self.offset + end,
                "data": base64.b64encode(body).decode("ascii"),
                "dropped": offset < self.offset,
                "exit": self.exit_code if end == len(self.history) else None,
            }

    def write(self, body):
        with self.lock:
            if self.fd < 0 or self.stopping:
                raise ValueError("The terminal session has ended.")
            pending = memoryview(body)
            deadline = time.monotonic() + 0.5
            while pending:
                try:
                    pending = pending[os.write(self.fd, pending):]
                except BlockingIOError:
                    left = deadline - time.monotonic()
                    if left <= 0 or not select.select([], [self.fd], [], left)[1]:
                        raise ValueError("The terminal input queue is full.")

    def resize(self, rows, columns):
        with self.lock:
            if self.fd >= 0:
                terminal_size(self.fd, rows, columns)

    def close(self):
        with self.lock:
            if self.fd < 0:
                return
            self.stopping = True
            try:
                foreground = os.tcgetpgrp(self.fd)
                if foreground > 0 and foreground != self.process.pid:
                    os.killpg(foreground, signal.SIGHUP)
            except OSError:
                pass
            try:
                os.killpg(self.process.pid, signal.SIGHUP)
            except ProcessLookupError:
                pass


class Bridge(socketserver.ThreadingTCPServer):
    daemon_threads = True

    def __init__(self, key):
        super().__init__(("127.0.0.1", 0), BridgeHandler)
        self.key = key
        self.sessions = {}
        self.lock = threading.Lock()
        self.clients = threading.BoundedSemaphore(16)
        self.touched = time.monotonic()

    def process_request(self, request, client_address):
        if not self.clients.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self.clients.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.clients.release()

    def handle_error(self, _request, _client_address):
        # Untrusted local connections and secrets are never logged.
        pass

    def reap(self):
        now = time.monotonic()
        with self.lock:
            self.sessions = {
                id_: session
                for id_, session in self.sessions.items()
                if session.finished_time is None or now - session.finished_time < IDLE_SECONDS
            }
            return not self.sessions and now - self.touched > IDLE_SECONDS

    def dispatch(self, request, _peer=None):
        if not isinstance(request, dict) or request.get("version") != VERSION:
            raise ValueError("Unsupported terminal bridge protocol.")
        operation = request.get("op")
        with self.lock:
            self.touched = time.monotonic()
            if operation == "ping":
                return {"version": VERSION}
            if operation == "list":
                return {"sessions": [session.snapshot() for session in self.sessions.values()]}
            if operation == "open":
                if len(self.sessions) >= MAX_TABS:
                    raise ValueError("Close a terminal tab before opening another.")
                directory = request.get("directory") or str(Path.home())
                if not isinstance(directory, str) or not os.path.isabs(directory):
                    raise ValueError("Use an absolute Termux directory.")
                terminal = Terminal(
                    directory,
                    required_int(request, "rows"),
                    required_int(request, "columns"),
                )
                self.sessions[terminal.id] = terminal
                return terminal.snapshot()
            session = self.sessions.get(request.get("id"))
            if session is None:
                return {"error": "This Termux session is no longer available.", "missing": True}
            if operation == "close":
                session.close()
                del self.sessions[session.id]
                return {}
        if operation == "poll":
            return session.poll(required_int(request, "offset"))
        if operation == "write":
            data = base64.b64decode(request.get("data", ""), validate=True)
            if len(data) > MAX_INPUT:
                raise ValueError("Terminal input is too long.")
            session.write(data)
            return {}
        if operation == "resize":
            session.resize(required_int(request, "rows"), required_int(request, "columns"))
            return {}
        raise ValueError("Unknown terminal operation.")


def signal_group(pid, sig):
    try:
        os.killpg(pid, sig)
        return True
    except ProcessLookupError:
        return False


def stop_command(process):
    if signal_group(process.pid, signal.SIGTERM):
        time.sleep(0.75)
        signal_group(process.pid, signal.SIGKILL)
    process.wait()


def command_exited(process):
    if hasattr(os, "waitid") and hasattr(os, "WNOWAIT"):
        return os.waitid(os.P_PID, process.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT) is not None
    return process.poll() is not None


def run_command(request, peer):
    directory = request.get("directory")
    command = request.get("command")
    timeout = required_int(request, "timeoutMs")
    if (not isinstance(directory, str) or not os.path.isabs(directory)
            or "\0" in directory or len(os.fsencode(directory)) > 4096):
        raise ValueError("Use an absolute Termux directory.")
    if (not isinstance(command, str) or not command.strip()
            or "\0" in command or len(command.encode("utf-8")) > MAX_COMMAND_BYTES):
        raise ValueError("Use a shell command up to 64 KiB.")
    if not 1000 <= timeout <= 3_600_000:
        raise ValueError("Invalid command timeout.")

    # Keeping one connection per command gives the bridge a lifetime to observe.
    # A peer closed before launch must never leave behind a running job.
    if select.select([peer], [], [], 0)[0]:
        raise EOFError
    shell = Path(sys.executable).with_name("bash")
    if not shell.is_file():
        shell = Path(os.getenv("PREFIX", "/usr")) / "bin" / "bash"
    process = subprocess.Popen(
        [str(shell), "-c", command],
        cwd=directory,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        start_new_session=True,
    )
    buffers = {process.stdout: bytearray(), process.stderr: bytearray()}
    streams = set(buffers)
    deadline = time.monotonic() + timeout / 1000
    truncated = False
    timed_out = False
    drain_deadline = None
    try:
        while True:
            if drain_deadline is None and command_exited(process):
                # Signal the command group before collecting its exit status.
                signal_group(process.pid, signal.SIGKILL)
                process.wait()
                drain_deadline = time.monotonic() + 0.25
            exited = drain_deadline is not None
            wait = deadline - time.monotonic()
            if exited and time.monotonic() >= drain_deadline:
                truncated = True
                break
            if wait <= 0 and not exited:
                timed_out = True
                break
            ready, _, _ = select.select([peer, *streams], [], [], 0 if exited else min(wait, 0.25))
            if peer in ready:
                raise EOFError
            if exited and not ready:
                break
            for pipe in ready:
                data = os.read(pipe.fileno(), 8192)
                if not data:
                    streams.remove(pipe)
                    continue
                buffer = buffers[pipe]
                room = MAX_COMMAND_OUTPUT - len(buffer)
                buffer.extend(data[:room])
                truncated |= len(data) > room
    finally:
        try:
            if process.returncode is None:
                stop_command(process)
        finally:
            process.stdout.close()
            process.stderr.close()

    code = process.returncode
    return {
        "stdout": base64.b64encode(buffers[process.stdout]).decode("ascii"),
        "stderr": base64.b64encode(buffers[process.stderr]).decode("ascii"),
        "exit": 124 if timed_out else (128 - code if code < 0 else code),
        "timedOut": timed_out,
        "truncated": truncated,
    }


class CommandBridge(Bridge):
    def __init__(self, key):
        super().__init__(key)
        self.command_slots = threading.BoundedSemaphore(MAX_COMMANDS)
        self.running = 0

    def reap(self):
        with self.lock:
            return not self.running and time.monotonic() - self.touched > IDLE_SECONDS

    def dispatch(self, request, peer=None):
        if not isinstance(request, dict) or request.get("version") != VERSION:
            raise ValueError("Unsupported command bridge protocol.")
        operation = request.get("op")
        if operation not in ("ping", "run"):
            raise ValueError("Unknown command operation.")
        with self.lock:
            self.touched = time.monotonic()
            if operation == "ping":
                return {"version": VERSION}
            if not self.command_slots.acquire(blocking=False):
                raise ValueError("Too many Termux commands are already running.")
            self.running += 1
        try:
            return run_command(request, peer)
        finally:
            with self.lock:
                self.running -= 1
                self.touched = time.monotonic()
                self.command_slots.release()


def required_int(request, field):
    value = request.get(field)
    if not isinstance(value, int) or isinstance(value, bool):
        raise ValueError(f"Invalid {field}.")
    return value


class BridgeHandler(socketserver.BaseRequestHandler):
    def handle(self):
        sock = self.request
        sock.settimeout(2)
        try:
            client = recv_exact(sock, 32)
            server = secrets.token_bytes(32)
            key = self.server.key
            sock.sendall(server + signature(key, b"s", client, server))
            body = frame(sock, MAX_REQUEST)
            if not hmac.compare_digest(recv_exact(sock, 32), signature(key, b"q", client, server, body)):
                return
            try:
                result = self.server.dispatch(json.loads(body), sock)
            except (ValueError, TypeError, binascii.Error) as error:
                result = {"error": str(error)}
            except OSError as error:
                result = {"error": error.strerror or "Terminal I/O failed."}
            reply = encode(result)
            send_frame(sock, reply)
            sock.sendall(signature(key, b"r", client, server, reply))
        except (EOFError, OSError, ValueError):
            return


def atomic_write(path, data):
    temporary = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    try:
        with temporary.open("xb") as file:
            file.write(data)
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def serve(command_only=False):
    key = secrets.token_bytes(32)
    bridge_type = CommandBridge if command_only else Bridge
    with bridge_type(key) as bridge:
        config = {"version": VERSION, "port": bridge.server_address[1], "key": key.hex()}
        atomic_write(BRIDGE_DIR / connection_file(command_only), encode(config))
        print("ready", flush=True)
        bridge.timeout = 5
        while not bridge.reap():
            bridge.handle_request()


def connection_file(command_only):
    return "command-socket.json" if command_only else "socket.json"


def current_connection(command_only=False):
    try:
        config = json.loads((BRIDGE_DIR / connection_file(command_only)).read_bytes())
        if config["version"] == VERSION and ask(config, {"version": VERSION, "op": "ping"}) == {"version": VERSION}:
            return config
    except (OSError, ValueError, EOFError, KeyError):
        pass
    return None


def provision(command_only=False):
    os.umask(0o077)
    BRIDGE_DIR.mkdir(mode=0o700, parents=True, exist_ok=True)
    with (BRIDGE_DIR / "provision.lock").open("ab") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        config = current_connection(command_only)
        if config is None:
            source = globals().get("bridge_source")
            source_bytes = source.encode("utf-8") if source is not None else Path(__file__).read_bytes()
            script = BRIDGE_DIR / ("command-bridge.py" if command_only else "bridge.py")
            atomic_write(script, source_bytes)
            daemon = subprocess.Popen(
                [sys.executable, str(script), "--serve-commands" if command_only else "--serve"],
                stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                start_new_session=True,
            )
            with daemon.stdout as ready:
                if not select.select([ready], [], [], 4)[0] or ready.readline() != b"ready\n":
                    daemon.terminate()
                    raise RuntimeError("The PTY bridge failed to start.")
            config = current_connection(command_only)
            if config is None:
                daemon.terminate()
                raise RuntimeError("The PTY bridge failed its connection check.")
        print(encode(config).decode("ascii"), flush=True)


if __name__ == "__main__":
    if sys.argv[1:] == ["--serve"]:
        serve()
    elif sys.argv[1:] == ["--provision"]:
        provision()
    elif sys.argv[1:] == ["--serve-commands"]:
        serve(command_only=True)
    elif sys.argv[1:] == ["--provision-commands"]:
        provision(command_only=True)
    else:
        sys.exit("Use --provision from Phone Agent.")
