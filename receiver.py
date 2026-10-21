#!/usr/bin/env python3
"""
receiver.py — TabDraw_Native 電腦接收端測試腳本
=================================================
用途：
  1. 開啟 UDP 監聽，接收平板傳來的手寫筆封包
  2. 解析 20-Byte 二進位封包
  3. 即時顯示 FPS、座標、壓感、狀態
  4. 可選：透過 Win32 SendInput 模擬 Windows 筆尖游標

封包格式（20 Bytes, Big-Endian）：
  [0-1]  Header : 0xAB 0xCD
  [2-3]  Seq    : uint16
  [4]    Flag   : 0=Hover, 1=Down, 2=Move, 3=Up
  [5-8]  X      : float32
  [9-12] Y      : float32
  [13-16] Pressure : float32
  [17-20] Tilt  : float32
  [19]   Checksum : XOR of bytes[0..18]

使用方式：
  python receiver.py [--port 9999] [--cursor] [--debug]
"""

import socket
import struct
import argparse
import time
import sys
import threading
from collections import deque

# ── 封包解析常數 ─────────────────────────────────────────────────
HEADER      = b'\xAB\xCD'
PACKET_SIZE = 22
PACKET_FMT  = '>2sH B ffff B'   # big-endian: header(2), seq(H), flag(B), x f, y f, pressure f, tilt f, chk(B)

FLAG_NAMES = {0: 'HOVER', 1: 'DOWN', 2: 'MOVE', 3: 'UP'}
FLAG_COLORS = {
    0: '\033[36m',  # Cyan  = Hover
    1: '\033[32m',  # Green = Down
    2: '\033[33m',  # Yellow= Move
    3: '\033[31m',  # Red   = Up
}
RESET = '\033[0m'

# ── Win32 游標控制（選用）───────────────────────────────────────
try:
    import ctypes
    from ctypes import wintypes

    class MOUSEINPUT(ctypes.Structure):
        _fields_ = [
            ("dx",          ctypes.c_long),
            ("dy",          ctypes.c_long),
            ("mouseData",   ctypes.c_ulong),
            ("dwFlags",     ctypes.c_ulong),
            ("time",        ctypes.c_ulong),
            ("dwExtraInfo", ctypes.POINTER(ctypes.c_ulong)),
        ]

    class INPUT(ctypes.Structure):
        class _INPUT(ctypes.Union):
            _fields_ = [("mi", MOUSEINPUT)]
        _anonymous_ = ("_input",)
        _fields_ = [("type", ctypes.c_ulong), ("_input", _INPUT)]

    MOUSEEVENTF_MOVE      = 0x0001
    MOUSEEVENTF_ABSOLUTE  = 0x8000
    MOUSEEVENTF_LEFTDOWN  = 0x0002
    MOUSEEVENTF_LEFTUP    = 0x0004
    INPUT_MOUSE           = 0
    WIN32_AVAILABLE       = True
except Exception:
    WIN32_AVAILABLE = False


class PacketReceiver:
    def __init__(self, port: int, use_cursor: bool, debug: bool):
        self.port       = port
        self.use_cursor = use_cursor and WIN32_AVAILABLE
        self.debug      = debug

        # FPS 統計
        self.recv_times: deque = deque(maxlen=120)
        self.total_packets  = 0
        self.bad_packets    = 0
        self.last_seq       = -1
        self.dropped_count  = 0

        # 螢幕解析度（游標模式用）
        if self.use_cursor:
            self.screen_w = ctypes.windll.user32.GetSystemMetrics(0)
            self.screen_h = ctypes.windll.user32.GetSystemMetrics(1)
            print(f"  [Cursor] Screen: {self.screen_w}x{self.screen_h}")

        # 統計顯示執行緒
        self._stats_thread = threading.Thread(target=self._stats_loop, daemon=True)

    def run(self):
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.bind(('0.0.0.0', self.port))
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1024 * 64)
        print(f"\n  ╔══════════════════════════════════════╗")
        print(f"  ║   TabDraw Native — UDP Receiver      ║")
        print(f"  ║   Listening on  0.0.0.0:{self.port:<5}        ║")
        print(f"  ║   Packet size   {PACKET_SIZE} bytes              ║")
        print(f"  ║   Press Ctrl+C to quit               ║")
        print(f"  ╚══════════════════════════════════════╝\n")

        self._stats_thread.start()

        try:
            while True:
                data, addr = sock.recvfrom(PACKET_SIZE * 4)  # 允許接收最多 4 個封包長度
                self._process(data, addr)
        except KeyboardInterrupt:
            print(f"\n\n  Stopped. Total: {self.total_packets} pkts, Bad: {self.bad_packets}, Dropped(seq): {self.dropped_count}")
        finally:
            sock.close()

    def _process(self, data: bytes, addr):
        """解析並處理單一封包"""
        if len(data) < PACKET_SIZE:
            self.bad_packets += 1
            return

        # 只取前 20 bytes（防止雜包）
        raw = data[:PACKET_SIZE]

        # 驗證 header
        if raw[0:2] != HEADER:
            self.bad_packets += 1
            return

        # 驗證 checksum
        xor_val = 0
        for b in raw[:-1]:
            xor_val ^= b
        if xor_val != raw[-1]:
            self.bad_packets += 1
            if self.debug:
                print(f"  [!] Checksum mismatch from {addr}")
            return

        # 解析欄位
        try:
            _, seq, flag, x, y, pressure, tilt, _ = struct.unpack(PACKET_FMT, raw)
        except struct.error as e:
            self.bad_packets += 1
            return

        # Sequence 跳號檢測
        if self.last_seq >= 0:
            expected = (self.last_seq + 1) & 0xFFFF
            if seq != expected:
                self.dropped_count += int((seq - expected) & 0xFFFF)
        self.last_seq = seq

        now = time.perf_counter()
        self.recv_times.append(now)
        self.total_packets += 1

        # 游標移動
        if self.use_cursor:
            self._move_cursor(x, y, flag, pressure)

        # 詳細輸出（debug 模式）
        if self.debug:
            color = FLAG_COLORS.get(flag, '')
            name  = FLAG_NAMES.get(flag, f'?{flag}')
            print(f"  {color}[{name:5}]{RESET}  "
                  f"X={x:8.2f}  Y={y:8.2f}  "
                  f"P={pressure:.3f}  Tilt={tilt:.2f}°  "
                  f"seq={seq}")

    def _stats_loop(self):
        """每秒輸出一次統計（非 debug 模式）"""
        while True:
            time.sleep(1.0)
            now = time.perf_counter()
            cutoff = now - 1.0
            # 計算最近 1 秒內的封包數
            recent = sum(1 for t in self.recv_times if t >= cutoff)
            fps = recent

            # 平均間隔（ms）
            times = list(self.recv_times)
            if len(times) >= 2:
                diffs = [(times[i+1] - times[i]) * 1000 for i in range(len(times)-1)]
                avg_ms = sum(diffs) / len(diffs)
                max_ms = max(diffs)
            else:
                avg_ms = 0.0
                max_ms = 0.0

            bar_len = min(fps, 120)
            bar = '█' * (bar_len // 4) + '░' * (30 - bar_len // 4)

            sys.stdout.write(
                f"\r  [{bar}]  "
                f"\033[93m{fps:3d} Hz\033[0m  "
                f"avg={avg_ms:.1f}ms  "
                f"max={max_ms:.1f}ms  "
                f"total={self.total_packets}  "
                f"bad={self.bad_packets}  "
                f"drop={self.dropped_count}   "
            )
            sys.stdout.flush()

    def _move_cursor(self, tablet_x: float, tablet_y: float, flag: int, pressure: float):
        """
        Win32 SendInput 游標模擬（座標映射：平板解析度 2560x1600 → 螢幕解析度）
        """
        TABLET_W = 2560.0
        TABLET_H = 1600.0

        # 將平板橫向座標映射至 0..65535 的正規化座標
        norm_x = max(0.0, min(tablet_x / TABLET_W, 1.0))
        norm_y = max(0.0, min(tablet_y / TABLET_H, 1.0))

        screen_x = int(norm_x * 65535)
        screen_y = int(norm_y * 65535)

        flags = MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE

        if flag == 1:   # DOWN (下筆開始繪圖)
            flags |= MOUSEEVENTF_LEFTDOWN
        elif flag == 3: # UP (提筆結束)
            flags |= MOUSEEVENTF_LEFTUP

        inp = INPUT(
            type=INPUT_MOUSE,
            mi=MOUSEINPUT(
                dx=screen_x,
                dy=screen_y,
                mouseData=0,
                dwFlags=flags,
                time=0,
                dwExtraInfo=None
            )
        )
        ctypes.windll.user32.SendInput(1, ctypes.byref(inp), ctypes.sizeof(INPUT))


def main():
    parser = argparse.ArgumentParser(description='TabDraw Native — UDP Receiver')
    parser.add_argument('--port',      type=int,  default=9999,  help='UDP listen port (default: 9999)')
    parser.add_argument('--no-cursor', action='store_true',      help='Disable Win32 cursor simulation')
    parser.add_argument('--debug',     action='store_true',      help='Print every packet detail')
    args = parser.parse_args()

    use_cursor = (not args.no_cursor) and WIN32_AVAILABLE
    if not use_cursor and not args.no_cursor:
        print("  [!] Win32 cursor simulation not available on this platform.")

    receiver = PacketReceiver(args.port, use_cursor, args.debug)
    receiver.run()


if __name__ == '__main__':
    main()
