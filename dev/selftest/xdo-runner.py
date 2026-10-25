"""Runs the dev client and performs real input for self-tests, on Windows: every "XDO <args>" line a script echoes
is carried out like `xdotool <args>` would, with SendInput on the game window. Linux uses xdo-runner.sh instead.

Understood (all the self-tests use): key, keydown, keyup and type (--window WIN and --delay are ignored), and
mousemove X Y (in the window's pixels) followed by any number of `click N` (1 left, 2 middle, 3 right, 4 wheel up,
5 wheel down).

Usage: python dev/selftest/xdo-runner.py [extra runClient args], e.g. --quickPlaySingleplayer test
"""
import ctypes
import ctypes.wintypes as wt
import os
import re
import shlex
import subprocess
import sys
import tempfile
import time

user32 = ctypes.WinDLL('user32', use_last_error=True)

INPUT_MOUSE, INPUT_KEYBOARD = 0, 1
KEYUP, UNICODE, SCANCODE, EXTENDED = 0x2, 0x4, 0x8, 0x1
WHEEL, LEFTDOWN, LEFTUP, RIGHTDOWN, RIGHTUP, MIDDLEDOWN, MIDDLEUP = 0x800, 0x2, 0x4, 0x8, 0x10, 0x20, 0x40


class MOUSEINPUT(ctypes.Structure):
    _fields_ = [('dx', wt.LONG), ('dy', wt.LONG), ('mouseData', wt.DWORD), ('dwFlags', wt.DWORD),
                ('time', wt.DWORD), ('dwExtraInfo', ctypes.c_size_t)]


class KEYBDINPUT(ctypes.Structure):
    _fields_ = [('wVk', wt.WORD), ('wScan', wt.WORD), ('dwFlags', wt.DWORD), ('time', wt.DWORD),
                ('dwExtraInfo', ctypes.c_size_t)]


class HARDWAREINPUT(ctypes.Structure):
    _fields_ = [('uMsg', wt.DWORD), ('wParamL', wt.WORD), ('wParamH', wt.WORD)]


class _U(ctypes.Union):
    _fields_ = [('mi', MOUSEINPUT), ('ki', KEYBDINPUT), ('hi', HARDWAREINPUT)]


class INPUT(ctypes.Structure):
    _fields_ = [('type', wt.DWORD), ('u', _U)]


def send(*inputs):
    arr = (INPUT * len(inputs))(*inputs)
    user32.SendInput(len(inputs), arr, ctypes.sizeof(INPUT))
    time.sleep(0.03)


def kb(vk, scan, flags):
    return INPUT(INPUT_KEYBOARD, _U(ki=KEYBDINPUT(vk, scan, flags, 0, 0)))


# xdotool key names -> (virtual-key code, extended key).
VK = {'Escape': (0x1B, False), 'Return': (0x0D, False), 'BackSpace': (0x08, False), 'Tab': (0x09, False),
      'space': (0x20, False), 'End': (0x23, True), 'Home': (0x24, True), 'Left': (0x25, True), 'Up': (0x26, True),
      'Right': (0x27, True), 'Down': (0x28, True)}


def key(name, down):
    vk, ext = VK.get(name) or ((user32.VkKeyScanW(ord(name)) & 0xFF, False) if len(name) == 1 else (None, None))
    if vk is None:
        raise ValueError('unknown key ' + name)
    scan = user32.MapVirtualKeyW(vk, 0)
    send(kb(vk, scan, SCANCODE | (0 if down else KEYUP) | (EXTENDED if ext else 0)))


def type_text(text):
    for ch in text:
        send(kb(0, ord(ch), UNICODE), kb(0, ord(ch), UNICODE | KEYUP))
        time.sleep(0.03)


def mouse(flags, data=0):
    send(INPUT(INPUT_MOUSE, _U(mi=MOUSEINPUT(0, 0, data & 0xFFFFFFFF, flags, 0, 0))))


def click(button):
    if button in (4, 5):
        return mouse(WHEEL, 120 if button == 4 else -120)
    down, up = {1: (LEFTDOWN, LEFTUP), 2: (MIDDLEDOWN, MIDDLEUP), 3: (RIGHTDOWN, RIGHTUP)}[button]
    mouse(down)
    time.sleep(0.05)
    mouse(up)


def game_window():
    found = []

    @ctypes.WINFUNCTYPE(wt.BOOL, wt.HWND, wt.LPARAM)
    def each(hwnd, _):
        n = user32.GetWindowTextLengthW(hwnd)
        buf = ctypes.create_unicode_buffer(n + 1)
        user32.GetWindowTextW(hwnd, buf, n + 1)
        if buf.value.startswith('Minecraft') and user32.IsWindowVisible(hwnd):
            found.append(hwnd)
        return True
    user32.EnumWindows(each, 0)
    return found[0] if found else None


def focus(hwnd):
    if user32.GetForegroundWindow() == hwnd:
        return
    # Windows only lets the foreground app hand focus over; a tapped Alt counts as this process having input.
    send(kb(0x12, 0, 0), kb(0x12, 0, KEYUP))
    user32.SetForegroundWindow(hwnd)
    time.sleep(0.2)


def move_to(hwnd, x, y):
    point = wt.POINT(int(x), int(y))
    user32.ClientToScreen(hwnd, ctypes.byref(point))
    user32.SetCursorPos(point.x, point.y)
    time.sleep(0.05)


COMMANDS = ('key', 'keydown', 'keyup', 'type', 'mousemove', 'click')


def run(line, hwnd):
    args = shlex.split(line)
    i = 0
    while i < len(args):
        cmd = args[i]
        i += 1
        while i < len(args) and args[i].startswith('--'):
            i += 2  # --window WIN, --delay N
        if cmd in ('key', 'keydown', 'keyup'):
            while i < len(args) and args[i] not in COMMANDS:
                if cmd != 'keyup':
                    key(args[i], True)
                if cmd != 'keydown':
                    key(args[i], False)
                i += 1
        elif cmd == 'type':
            type_text(args[i])
            i += 1
        elif cmd == 'mousemove':
            move_to(hwnd, args[i], args[i + 1])
            i += 2
        elif cmd == 'click':
            click(int(args[i]))
            i += 1
        else:
            raise ValueError('unsupported xdotool command: ' + cmd)


def main():
    root = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
    log_path = os.environ.get('XDO_LOG') or os.path.join(tempfile.mkdtemp(), 'client.log')
    log = open(log_path, 'w', encoding='utf-8', errors='replace')
    gradle = subprocess.Popen([os.path.join(root, 'gradlew.bat'), 'runClient', '--args=' + ' '.join(sys.argv[1:]), '--console=plain'],
                              cwd=root, stdout=log, stderr=subprocess.STDOUT)
    done, pattern = 0, re.compile(r'\[CHAT\] XDO (.*)$')
    while gradle.poll() is None:
        time.sleep(0.2)
        with open(log_path, encoding='utf-8', errors='replace') as f:
            cmds = [m.group(1).rstrip() for m in map(pattern.search, f) if m]
        while done < len(cmds):
            hwnd = game_window()
            if hwnd:
                focus(hwnd)
                try:
                    if os.environ.get('XDO_TRACE'):
                        print(time.strftime('%H:%M:%S'), 'run', cmds[done], flush=True)
                    run(cmds[done], hwnd)
                except Exception as e:  # keep going: the test reports what didn't happen
                    print('XDO failed:', cmds[done], '-', e, flush=True)
            done += 1
    log.close()
    with open(log_path, encoding='utf-8', errors='replace') as f:
        for line in f:
            m = re.search(r'\[CHAT\] ([A-Z]+ (OK|FAIL|DONE).*)$', line)
            if m:
                print(m.group(1))


if __name__ == '__main__':
    main()