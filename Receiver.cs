using System;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

class Receiver {
    [StructLayout(LayoutKind.Sequential)]
    struct MOUSEINPUT {
        public int dx;
        public int dy;
        public uint mouseData;
        public uint dwFlags;
        public uint time;
        public IntPtr dwExtraInfo;
    }

    [StructLayout(LayoutKind.Explicit)]
    struct INPUT {
        [FieldOffset(0)] public uint type;
        [FieldOffset(8)] public MOUSEINPUT mi;
    }

    const uint INPUT_MOUSE = 0;
    const uint MOUSEEVENTF_MOVE = 0x0001;
    const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
    const uint MOUSEEVENTF_LEFTUP = 0x0004;
    const uint MOUSEEVENTF_ABSOLUTE = 0x8000;

    [DllImport("user32.dll", SetLastError = true)]
    static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);

    [DllImport("user32.dll")]
    static extern int GetSystemMetrics(int nIndex);

    const int PORT = 9999;
    const int DISCOVERY_PORT = 9998;

    static void Main(string[] args) {
        Console.Title = "TabDraw - Windows Screen Link (Auto-Discovery)";
        Console.ForegroundColor = ConsoleColor.Magenta;
        Console.WriteLine("========================================================");
        Console.WriteLine("  TabDraw - Screen Link Receiver (Auto-Discovery)");
        Console.WriteLine("========================================================");
        Console.ResetColor();

        string localIp = GetBestLocalIPv4();
        int screenW = GetSystemMetrics(0);
        int screenH = GetSystemMetrics(1);

        Console.WriteLine(string.Format("  [HOST IP]           {0}", localIp));
        Console.WriteLine(string.Format("  [DATA PORT]         UDP {0}", PORT));
        Console.WriteLine(string.Format("  [AUTO-DISCOVERY]    Broadcasting on UDP {0}/sec", DISCOVERY_PORT));
        Console.WriteLine(string.Format("  [SCREEN RESOLUTION] {0} x {1}", screenW, screenH));
        Console.WriteLine("--------------------------------------------------------\n");

        // 啟動背景廣播執行緒（每秒廣播一次自身 IP、Port 與螢幕解析度）
        StartBroadcastBeacon(localIp, screenW, screenH);

        UdpClient udp;
        try {
            udp = new UdpClient(PORT);
        } catch (Exception ex) {
            Console.ForegroundColor = ConsoleColor.Red;
            Console.WriteLine(string.Format("[ERROR] Failed to bind UDP port {0}: {1}", PORT, ex.Message));
            Console.ResetColor();
            Console.WriteLine("\n請按任意鍵退出...");
            Console.ReadKey();
            return;
        }

        IPEndPoint ep = new IPEndPoint(IPAddress.Any, PORT);
        long packetCount = 0;
        int lastSecPackets = 0;

        // FPS 統計回報執行緒
        new Thread(() => {
            while (true) {
                Thread.Sleep(1000);
                int hz = Interlocked.Exchange(ref lastSecPackets, 0);
                if (packetCount > 0) {
                    Console.ForegroundColor = ConsoleColor.Cyan;
                    Console.Write(string.Format("\r  ⚡ [LINK ACTIVE]  Sample Rate: {0,3} Hz | Total: {1,7} pkts   ", hz, packetCount));
                    Console.ResetColor();
                }
            }
        }) { IsBackground = true }.Start();

        // 手寫資料主迴圈
        while (true) {
            try {
                byte[] bytes = udp.Receive(ref ep);
                if (bytes == null || bytes.Length < 22) continue;

                if (bytes[0] != 0xAB || bytes[1] != 0xCD) continue;

                byte xor = 0;
                for (int i = 0; i < 21; i++) xor ^= bytes[i];
                if (xor != bytes[21]) continue;

                byte flag = bytes[4];
                float x = ReadFloatBE(bytes, 5);
                float y = ReadFloatBE(bytes, 9);
                float pressure = ReadFloatBE(bytes, 13);
                float tilt = ReadFloatBE(bytes, 17);

                Interlocked.Increment(ref packetCount);
                Interlocked.Increment(ref lastSecPackets);

                MoveCursor(x, y, flag);
            } catch (Exception) {
                // Ignore transient errors
            }
        }
    }

    /// <summary>
    /// 自動廣播：向區域網路 255.255.255.255 廣播自身 IP，讓平板完全免設定秒連
    /// </summary>
    static void StartBroadcastBeacon(string myIp, int w, int h) {
        new Thread(() => {
            try {
                UdpClient beacon = new UdpClient();
                beacon.EnableBroadcast = true;
                IPEndPoint target = new IPEndPoint(IPAddress.Broadcast, DISCOVERY_PORT);
                byte[] msg = Encoding.ASCII.GetBytes(string.Format("TABDRAW_HOST:{0}:{1}:{2}x{3}", myIp, PORT, w, h));

                while (true) {
                    beacon.Send(msg, msg.Length, target);
                    Thread.Sleep(1000);
                }
            } catch (Exception) {
                // Ignore broadcast socket exceptions
            }
        }) { IsBackground = true }.Start();
    }

    static string GetBestLocalIPv4() {
        try {
            string host = Dns.GetHostName();
            IPHostEntry entry = Dns.GetHostEntry(host);
            foreach (IPAddress ip in entry.AddressList) {
                if (ip.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(ip)) {
                    string str = ip.ToString();
                    if (!str.StartsWith("169.254")) return str;
                }
            }
        } catch { }
        return "192.168.0.13";
    }

    static float ReadFloatBE(byte[] bytes, int offset) {
        byte[] tmp = new byte[4];
        tmp[0] = bytes[offset + 3];
        tmp[1] = bytes[offset + 2];
        tmp[2] = bytes[offset + 1];
        tmp[3] = bytes[offset];
        return BitConverter.ToSingle(tmp, 0);
    }

    /// <summary>
    /// 全自動螢幕解析度映射：接收 0.0 ~ 1.0 正規化座標，全自動對齊任何電腦螢幕
    /// </summary>
    static void MoveCursor(float normX, float normY, byte flag) {
        float clampedX = Math.Max(0.0f, Math.Min(normX, 1.0f));
        float clampedY = Math.Max(0.0f, Math.Min(normY, 1.0f));

        int screenX = (int)(clampedX * 65535);
        int screenY = (int)(clampedY * 65535);

        uint flags = MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE;

        if (flag == 1) {       // DOWN (下筆開始繪圖)
            flags |= MOUSEEVENTF_LEFTDOWN;
        } else if (flag == 3) { // UP (提筆結束)
            flags |= MOUSEEVENTF_LEFTUP;
        }

        INPUT[] inputs = new INPUT[1];
        inputs[0].type = INPUT_MOUSE;
        inputs[0].mi = new MOUSEINPUT {
            dx = screenX,
            dy = screenY,
            mouseData = 0,
            dwFlags = flags,
            time = 0,
            dwExtraInfo = IntPtr.Zero
        };

        SendInput(1, inputs, Marshal.SizeOf(typeof(INPUT)));
    }
}
