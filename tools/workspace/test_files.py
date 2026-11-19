import base64
import contextlib
import importlib.util
import io
import os
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch

module_path = Path(__file__).resolve().parents[2] / "app/src/main/assets/workspace/files.py"
spec = importlib.util.spec_from_file_location("project_files", module_path)
files = importlib.util.module_from_spec(spec)
spec.loader.exec_module(files)


def encoded(text):
    return base64.b64encode(text.encode()).decode()


class ProjectFilesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.parent = Path(self.temp.name)
        self.project = self.parent / "project"
        self.project.mkdir()
        old_cwd = Path.cwd()
        self.addCleanup(os.chdir, old_cwd)
        os.chdir(self.project)

    def run_file(self, action, path, *args):
        output = io.StringIO()
        with patch.object(files, "verify_project"), contextlib.redirect_stdout(output):
            files.main(str(self.project), action, path, *map(encoded, args))
        return output.getvalue()

    def test_read_write_edit_and_listing_use_real_files(self):
        self.run_file("write", "script.sh", "#!/bin/sh\necho Привет\n")
        script = self.project / "script.sh"
        script.chmod(0o755)
        self.assertEqual(self.run_file("read", "script.sh"), script.read_text())
        self.run_file("edit", "script.sh", "Привет", "世界")
        self.assertEqual(script.read_text(), "#!/bin/sh\necho 世界\n")
        self.assertEqual(stat.S_IMODE(script.stat().st_mode), 0o755)
        self.assertEqual(self.run_file("list", "."), "script.sh")

    def test_links_secrets_and_traversal_are_refused(self):
        outside = self.parent / "outside"
        outside.mkdir()
        (outside / "secret").write_text("secret")
        (self.project / "jump").symlink_to(outside)
        (self.project / "linked").symlink_to(outside / "secret")
        os.link(outside / "secret", self.project / "hard")
        (self.project / ".env").write_text("token")
        self.assertNotIn(".env", self.run_file("list", "."))
        for path in ("jump/secret", "linked", "hard", "../outside/secret", ".env"):
            with self.subTest(path=path), self.assertRaises((OSError, ValueError)):
                self.run_file("read", path)
        with self.assertRaises(ValueError):
            self.run_file("write", "linked", "overwrite")
        self.assertEqual((outside / "secret").read_text(), "secret")

    def test_edit_is_exact_and_checks_size(self):
        self.run_file("write", "file.txt", "aaaa")
        with self.assertRaises(ValueError):
            self.run_file("edit", "file.txt", "aa", "bb")
        with self.assertRaises(ValueError):
            self.run_file("write", "file.txt", "α" * 10_001)
        self.assertEqual(self.run_file("read", "file.txt"), "aaaa")

    def test_project_root_rejects_symlink_aliases(self):
        with patch.object(files.os.path, "realpath", return_value="/real/home"):
            with patch.object(files.os, "getcwd", return_value="/real/home/project"):
                files.verify_project("/data/data/com.termux/files/home/project")
            with patch.object(files.os, "getcwd", return_value="/unrelated"):
                with self.assertRaises(ValueError):
                    files.verify_project("/data/data/com.termux/files/home/project")


if __name__ == "__main__":
    unittest.main()
