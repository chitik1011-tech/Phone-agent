"""Host tests. Run with python3 -m unittest discover -s tools/termux -v."""

import base64
from contextlib import redirect_stdout
import importlib.util
import io
import json
import os
from pathlib import Path
import secrets
import socket
import stat
import subprocess
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
ASSET = ROOT / "app" / "src" / "main" / "assets" / "termux" / "phone_agent_bridge.py"
SPEC = importlib.util.spec_from_file_location("phone_agent_bridge", ASSET)
bridge = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(bridge)


class BridgeTest(unittest.TestCase):
    def setUp(self):
        key = secrets.token_bytes(32)
        self.server = bridge.Bridge(key)
        self.connection = {"port": self.server.server_address[1], "key": key.hex()}
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.workspace = tempfile.TemporaryDirectory()
        self.ids = []

    def tearDown(self):
        for id_ in self.ids:
            self.call("close", id=id_)
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=3)
        self.workspace.cleanup()

    def call(self, op, **fields):
        return bridge.ask(self.connection, {"version": bridge.VERSION, "op": op, **fields})

    def shell(self, directory=None):
        response = self.call("open", directory=directory or self.workspace.name, rows=24, columns=80)
        id_ = response["id"]
        self.ids.append(id_)
        return id_

    def type(self, id_, text):
        data = base64.b64encode(text.encode()).decode("ascii")
        self.assertEqual({}, self.call("write", id=id_, data=data))

    def wait_for(self, id_, predicate):
        offset, output = 0, b""
        deadline = time.monotonic() + 4
        while time.monotonic() < deadline:
            result = self.call("poll", id=id_, offset=offset)
            offset = result["offset"]
            output += base64.b64decode(result["data"])
            if predicate(result, output):
                return result, output
            time.sleep(0.025)
        self.fail(f"Expected terminal output was unavailable: {output!r}")

    def test_real_pty_working_directory_utf8_ansi_resize_and_exit(self):
        id_ = self.shell()
        self.assertEqual({}, self.call("resize", id=id_, rows=31, columns=127))
        self.type(id_, "printf '\\033[38;2;10;20;30m%s\\033[0m\\n' 'Привет'; stty size; pwd; exit 7\n")
        result, output = self.wait_for(id_, lambda p, _o: p["exit"] == 7)
        self.assertEqual(7, result["exit"])
        self.assertIn(b"\x1b[38;2;10;20;30m" + "Привет".encode() + b"\x1b[0m", output)
        self.assertIn(b"\r\n31 127\r\n", output)
        self.assertIn((self.workspace.name + "\r\n").encode(), output)

    def test_ctrl_c_interrupts_foreground_job(self):
        id_ = self.shell()
        self.type(id_, "printf 'RUN_%s\\n' STARTED; sleep 60\n")
        self.wait_for(id_, lambda _p, o: b"\rRUN_STARTED\r\n" in o)
        time.sleep(0.1)
        self.type(id_, "\x03")
        self.type(id_, "printf 'AFTER_%s\\n' INTERRUPT; exit\n")
        result, output = self.wait_for(id_, lambda p, _o: p["exit"] is not None)
        self.assertEqual(0, result["exit"])
        self.assertIn(b"\rAFTER_INTERRUPT\r\n", output)

    def test_multiple_tabs_and_reconnect_replay(self):
        first = self.shell()
        second_dir = str(Path(self.workspace.name) / "second")
        os.mkdir(second_dir)
        second = self.shell(second_dir)
        self.type(first, "export PHONE_AGENT_TEST_STATE=first; printf 'STATE:%s\\n' \"$PHONE_AGENT_TEST_STATE\"\n")
        _, history = self.wait_for(first, lambda _p, o: b"\rSTATE:first\r\n" in o)
        self.type(second, "printf 'STATE:%s\\n' \"$PHONE_AGENT_TEST_STATE\"; pwd\n")
        _, independent = self.wait_for(second, lambda _p, o: (second_dir + "\r\n").encode() in o)
        self.assertIn(b"\rSTATE:\r\n", independent)
        self.assertEqual({first, second}, {item["id"] for item in self.call("list")["sessions"]})
        replayed = self.call("poll", id=first, offset=0)
        self.assertTrue(base64.b64decode(replayed["data"]).startswith(history))

    def test_unauthenticated_or_replayed_request_never_opens_shell(self):
        request = bridge.encode({"version": 1, "op": "open", "rows": 24, "columns": 80})
        client_nonce = secrets.token_bytes(32)
        saved_signature = None
        for _ in range(2):
            with socket.create_connection(("127.0.0.1", self.connection["port"]), timeout=3) as sock:
                sock.sendall(client_nonce)
                first = bridge.recv_exact(sock, 32)
                bridge.recv_exact(sock, 32)
                bridge.send_frame(sock, request)
                sock.sendall(saved_signature or bytes(32))
                self.assertEqual(b"", sock.recv(1))
                saved_signature = bridge.signature(
                    bytes.fromhex(self.connection["key"]), b"q", client_nonce, first, request,
                )
        self.assertEqual([], self.call("list")["sessions"])
        self.assertEqual("127.0.0.1", self.server.server_address[0])

    def test_invalid_path_has_no_implicit_home_fallback(self):
        response = self.call("open", directory="relative", rows=24, columns=80)
        self.assertIn("absolute", response["error"])
        self.assertEqual([], self.call("list")["sessions"])


class ProvisionTest(unittest.TestCase):
    def test_provisions_private_state_and_reuses_running_bridge(self):
        with tempfile.TemporaryDirectory() as home:
            state_directory = Path(home) / ".local" / "share" / "phone-agent-bridge"
            children = []
            popen = subprocess.Popen
            old_mask = os.umask(0o077)

            def track(*args, **kwargs):
                child = popen(*args, **kwargs)
                children.append(child)
                return child

            try:
                with (
                    patch.dict(os.environ, {"HOME": home}),
                    patch.object(bridge, "BRIDGE_DIR", state_directory),
                    patch.object(bridge, "bridge_source", ASSET.read_text(), create=True),
                    patch.object(bridge.subprocess, "Popen", side_effect=track),
                ):
                    first_out = io.StringIO()
                    with redirect_stdout(first_out):
                        bridge.provision()
                    second_out = io.StringIO()
                    with redirect_stdout(second_out):
                        bridge.provision()

                self.assertEqual(1, len(children))
                self.assertEqual(first_out.getvalue(), second_out.getvalue())
                config = json.loads(first_out.getvalue())
                self.assertEqual({"version": 1}, bridge.ask(config, {"version": 1, "op": "ping"}))
                self.assertEqual(0o700, stat.S_IMODE(state_directory.stat().st_mode))
                self.assertEqual(0o600, stat.S_IMODE((state_directory / "socket.json").stat().st_mode))
            finally:
                os.umask(old_mask)
                for child in children:
                    child.terminate()
                    child.wait(timeout=3)


if __name__ == "__main__":
    unittest.main()
