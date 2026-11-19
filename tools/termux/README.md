# Termux checks

Run the host PTY and authentication tests with:

    PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tools/termux -v

The process tests use the system shell and a real Linux PTY. They cover independent terminal sessions, Ctrl+C, exit status, ANSI bytes, UTF-8, working directories, resizing, scrollback replay and authentication. The device checks cover Android Intent delivery and the embedded WebView.

## Android 15 / ColorOS

These checks require a device with official Termux 0.118 or later and Phone Agent installed.

1. Open Termux and finish the package bootstrap. In Termux run:

       pkg install python
       mkdir -p ~/.termux
       printf '\nallow-external-apps=true\n' >> ~/.termux/termux.properties
       termux-reload-settings

2. In Phone Agent, open Terminal and grant the Termux command permission. Tap **Test connection**. Verify success and Python availability. Revoking the permission in Android Settings should make the next connection attempt report the missing permission.
3. Tap **Connect**, enter a directory in Termux (or leave it blank for the Termux home directory), and tap **Open terminal**. Run `pwd; id; stty size` and verify Termux's UID, the expected directory, and a nonzero terminal size. Rotate the device and confirm `stty size` changes.
4. Run the following. Verify colored Cyrillic text, the shell reporting the Ctrl+C interruption, and the same tab output after closing and reopening Phone Agent while Termux remains alive.

       printf '\033[38;2;50;205;140mПривет\033[0m\n'
       sleep 60

5. Open another tab in a different work directory. Check that `cd` and environment changes in one shell don't leak into the other. Try interacting with `vim` or `tmux` if installed. Verify the keyboard, quick keys, clipboard, and terminal search.
6. Run `exit 7` and confirm the terminal reports exit code 7. Force-stop **Termux** and confirm Phone Agent reports the connection loss. After manually reopening Termux, **Reconnect** can start a new bridge. Exited or killed processes aren't restarted by reconnecting.
7. In a Phone Agent project with an explicit Termux work directory, approve a command such as `printf stdout; printf stderr >&2; exit 7`. Verify the tool result distinguishes stdout, stderr, and exit code. Verify that an invalid directory produces an error without running the command in the Termux home directory.

## Implementation

The bridge installs under Termux's private home at `~/.local/share/phone-agent-bridge` using RUN_COMMAND after the user taps Connect. It uses standard-library Python PTYs and an HMAC-SHA256 authenticated protocol on IPv4 loopback. The port and bridge secret come through an app-private one-shot result with Termux command output logging disabled and are stored in private app settings. The secret is used for signing and stays off the wire. Each session keeps up to 512 KiB of output in memory while the bridge stays alive. Android or ColorOS can still stop Termux and the bridge.

The embedded terminal includes xterm.js 5.5.0, Fit 0.10.0 and Search 0.15.0 from their npm releases. Their licenses ship with the assets. [xterm.js](https://github.com/xtermjs/xterm.js)
