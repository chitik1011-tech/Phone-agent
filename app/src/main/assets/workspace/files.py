import base64
import contextlib
import os
import secrets
import stat
import sys

LIMIT = 20_000
PRIVATE = {
    ".git", ".env", ".ssh", ".aws", ".gnupg", ".netrc", ".npmrc", ".pypirc",
    ".git-credentials", "local.properties", "credentials.json", "id_rsa", "id_ed25519"
}


def allowed(name):
    lower = name.lower()
    return (
        name not in ("", ".", "..")
        and not any(ord(char) < 32 or ord(char) == 127 for char in name)
        and "\\" not in name
        and lower not in PRIVATE
        and not lower.startswith(".env.")
        and not lower.endswith((".jks", ".keystore", ".pem", ".key", ".p12"))
    )


def read_text(parent, name):
    fd = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=parent)
    with os.fdopen(fd, "rb") as handle:
        info = os.fstat(handle.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
            raise ValueError("Only ordinary unlinked text files are supported.")
        data = handle.read(LIMIT + 1)
        if len(data) > LIMIT or b"\0" in data:
            raise ValueError("Only UTF-8 text files up to 20 KB are supported.")
        return data.decode("utf-8")


def save_text(parent, name, content):
    data = content.encode("utf-8")
    if len(data) > LIMIT or b"\0" in data:
        raise ValueError("Only UTF-8 text files up to 20 KB are supported.")
    try:
        info = os.stat(name, dir_fd=parent, follow_symlinks=False)
    except FileNotFoundError:
        info = None
    if info is not None and (not stat.S_ISREG(info.st_mode) or info.st_nlink != 1):
        raise ValueError("Refusing to replace a link or special file.")
    temp_name = ".phone-agent-" + secrets.token_hex(16)
    descriptor = os.open(temp_name, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600, dir_fd=parent)
    try:
        with os.fdopen(descriptor, "wb") as handle:
            if info is not None:
                os.fchmod(handle.fileno(), stat.S_IMODE(info.st_mode) & 0o777)
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temp_name, name, src_dir_fd=parent, dst_dir_fd=parent)
        try:
            os.fsync(parent)
        except OSError:
            pass
    finally:
        with contextlib.suppress(FileNotFoundError):
            os.unlink(temp_name, dir_fd=parent)


def verify_project(project_path):
    for base in ("/data/data/com.termux/files/home", "/data/user/0/com.termux/files/home", "/storage/emulated/0", "/sdcard"):
        if project_path.startswith(base + "/"):
            expected = os.path.join(os.path.realpath(base), project_path[len(base) + 1:])
            break
    else:
        raise ValueError("Choose a project subfolder in Termux home or shared storage.")
    if os.getcwd() != expected:
        raise ValueError("Open the project's real directory path instead of a link.")


def main(project_path, operation, path, *payload):
    verify_project(project_path)
    parts = [] if operation == "list" and path in ("", ".") else path.split("/")
    if not all(map(allowed, parts)):
        raise ValueError("Use a relative path within the project, excluding private files.")
    directories = parts if operation == "list" else parts[:-1]
    with contextlib.ExitStack() as cleanup:
        def open_directory(name, parent=None):
            fd = os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=parent)
            cleanup.callback(os.close, fd)
            return fd

        folder = open_directory(".")
        for part in directories:
            folder = open_directory(part, folder)
        if operation == "list":
            with os.scandir(folder) as entries:
                visible = sorted(
                    entry.name + ("/" if entry.is_dir(follow_symlinks=False) else "")
                    for entry in entries if allowed(entry.name) and not entry.is_symlink()
                )
            print("\n".join(visible[:400]), end="")
            return

        name = parts[-1]
        args = [base64.b64decode(arg, validate=True).decode("utf-8") for arg in payload]
        if operation == "read":
            print(read_text(folder, name), end="")
        elif operation == "write":
            save_text(folder, name, args[0])
            print("Saved " + path)
        elif operation == "edit":
            old, new = args
            content = read_text(folder, name)
            index = content.find(old)
            if not old or index < 0 or content.find(old, index + 1) >= 0:
                raise ValueError("The original text must occur exactly once.")
            save_text(folder, name, content[:index] + new + content[index + len(old):])
            print("Updated " + path)
        else:
            raise ValueError("Unknown file operation.")


if __name__ == "__main__":
    try:
        main(*sys.argv[1:])
    except (OSError, UnicodeError, ValueError) as error:
        sys.exit(str(error))
