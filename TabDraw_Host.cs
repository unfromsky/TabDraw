using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Windows.Forms;

namespace TabDraw {
    public static class Program {
        [DllImport("user32.dll")]
        static extern bool SetProcessDpiAwarenessContext(IntPtr dpiContext);

        [DllImport("user32.dll")]
        static extern bool SetProcessDPIAware();

        private static readonly IntPtr DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2 = new IntPtr(-4);

        [STAThread]
        public static void Main() {
            try {
                if (Environment.OSVersion.Version.Major >= 10) {
                    SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
                } else {
                    SetProcessDPIAware();
                }
            } catch {
                try { SetProcessDPIAware(); } catch { }
            }

            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new MainForm());
        }
    }

    public class MainForm : Form {
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
        const uint MOUSEEVENTF_VIRTUALDESK = 0x4000;

        [DllImport("user32.dll", SetLastError = true)]
        static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);

        [DllImport("user32.dll")]
        static extern int GetSystemMetrics(int nIndex);

        const int PORT = 9999;
        const int DISCOVERY_PORT = 9998;

        // UI 元件
        private NotifyIcon trayIcon;
        private ContextMenuStrip trayMenu;
        private ComboBox comboMonitors;
        private Label lblSelectedMonitorInfo;
        private Label lblClientDevice;
        private Label lblClientIp;
        private Label lblFps;
        private Label lblPackets;
        private Label lblPressure;
        private Label lblServerStatus;
        private Panel previewPanel;

        // 狀態與同步
        private volatile bool isRunning = true;
        private UdpClient udpServer;
        private Thread receiveThread;
        private Thread broadcastThread;
        private Thread statsThread;

        private Screen selectedScreen;
        private int monitorW = 1920;
        private int monitorH = 1080;
        private string localIp = "192.168.0.13";

        private long totalPackets = 0;
        private int currentHz = 0;
        private int packetCounter = 0;
        private float currentPressure = 0f;
        private string clientDeviceName = "等待平板連線...";
        private string clientIpAddress = "--";

        // 深紫科技感配色
        private readonly Color COLOR_BG = Color.FromArgb(16, 11, 30);          // #100B1E
        private readonly Color COLOR_CARD = Color.FromArgb(28, 19, 56);        // #1C1338
        private readonly Color COLOR_CARD_BORDER = Color.FromArgb(76, 29, 149); // #4C1D95
        private readonly Color COLOR_ACCENT = Color.FromArgb(168, 85, 247);     // #A855F7 亮紫
        private readonly Color COLOR_CYAN = Color.FromArgb(56, 189, 248);       // #38BDF8 天青
        private readonly Color COLOR_GREEN = Color.FromArgb(16, 185, 129);     // #10B981 翡翠綠
        private readonly Color COLOR_TEXT = Color.FromArgb(250, 245, 255);      // #FAF5FF

        public MainForm() {
            localIp = GetBestLocalIPv4();
            selectedScreen = Screen.PrimaryScreen;
            if (selectedScreen != null) {
                monitorW = selectedScreen.Bounds.Width;
                monitorH = selectedScreen.Bounds.Height;
            }

            InitializeTray();
            InitializeComponent();
            UpdateSelectedMonitorMetrics();

            StartUdpServer();
            StartBroadcasting();
            StartStatsLoop();
        }

        private void InitializeComponent() {
            this.Text = "TabDraw - 電腦控制台 (Host Dashboard)";
            this.Size = new Size(760, 610);
            this.MinimumSize = new Size(740, 580);
            this.BackColor = COLOR_BG;
            this.ForeColor = COLOR_TEXT;
            this.Font = new Font("Segoe UI", 9.5f, FontStyle.Regular);
            this.StartPosition = FormStartPosition.CenterScreen;
            this.Icon = SystemIcons.Application;

            // ── 頂部導航列 ──────────────────────────────────────────
            Panel topBar = new Panel {
                Dock = DockStyle.Top,
                Height = 64,
                BackColor = Color.FromArgb(20, 14, 38),
                Padding = new Padding(20, 10, 20, 10)
            };

            Label titleLabel = new Label {
                Text = "🎨 TabDraw Host",
                Font = new Font("Segoe UI", 13f, FontStyle.Bold),
                ForeColor = COLOR_TEXT,
                AutoSize = true,
                Location = new Point(20, 12)
            };

            Label subTitleLabel = new Label {
                Text = "平板電腦無線數位板伺服器 · 超低延遲 Native 傳輸",
                Font = new Font("Segoe UI", 8.5f),
                ForeColor = Color.FromArgb(160, 150, 180),
                AutoSize = true,
                Location = new Point(22, 38)
            };

            lblServerStatus = new Label {
                Text = "● 伺服器運行中 (UDP 9999)",
                Font = new Font("Segoe UI", 9f, FontStyle.Bold),
                ForeColor = COLOR_GREEN,
                AutoSize = true,
                Location = new Point(460, 22),
                Anchor = AnchorStyles.Top | AnchorStyles.Right
            };

            topBar.Controls.Add(titleLabel);
            topBar.Controls.Add(subTitleLabel);
            topBar.Controls.Add(lblServerStatus);
            this.Controls.Add(topBar);

            // ── 主要內容區域 ────────────────────────────────────────
            TableLayoutPanel mainLayout = new TableLayoutPanel {
                Dock = DockStyle.Fill,
                ColumnCount = 2,
                RowCount = 2,
                Padding = new Padding(16, 74, 16, 16),
                BackColor = COLOR_BG
            };
            mainLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50f));
            mainLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50f));
            mainLayout.RowStyles.Add(new RowStyle(SizeType.Percent, 53f));
            mainLayout.RowStyles.Add(new RowStyle(SizeType.Percent, 47f));

            // ── 卡片 1：繪圖目標螢幕 (Target Monitor) ─────────────────
            Panel cardMonitor = CreateCardPanel("🖥 繪圖目標螢幕 (Target Monitor)");
            Label lblMonSelect = new Label {
                Text = "選擇要將平板對應到哪一個電腦螢幕：",
                Font = new Font("Segoe UI", 9f),
                ForeColor = Color.FromArgb(190, 180, 210),
                Location = new Point(16, 40),
                Size = new Size(300, 20)
            };

            comboMonitors = new ComboBox {
                DropDownStyle = ComboBoxStyle.DropDownList,
                Location = new Point(16, 64),
                Size = new Size(300, 28),
                BackColor = Color.FromArgb(40, 28, 70),
                ForeColor = Color.White,
                Font = new Font("Segoe UI", 9.5f)
            };
            RefreshMonitorsList();
            comboMonitors.SelectedIndexChanged += ComboMonitors_SelectedIndexChanged;

            previewPanel = new Panel {
                Location = new Point(16, 102),
                Size = new Size(300, 110),
                BackColor = Color.FromArgb(18, 12, 34)
            };
            previewPanel.Paint += PreviewPanel_Paint;

            lblSelectedMonitorInfo = new Label {
                Text = string.Format("解析度: {0} x {1} (16:10)", monitorW, monitorH),
                Font = new Font("Consolas", 9f),
                ForeColor = COLOR_CYAN,
                Location = new Point(16, 218),
                Size = new Size(300, 20)
            };

            cardMonitor.Controls.Add(lblMonSelect);
            cardMonitor.Controls.Add(comboMonitors);
            cardMonitor.Controls.Add(previewPanel);
            cardMonitor.Controls.Add(lblSelectedMonitorInfo);

            // ── 卡片 2：已連線平板資訊 (Tablet Status) ─────────────────
            Panel cardClient = CreateCardPanel("📱 已連線平板資訊 (Tablet Status)");
            
            Label lblDeviceTag = new Label { Text = "連線設備型號", Location = new Point(16, 40), Size = new Size(130, 18), ForeColor = COLOR_ACCENT, Font = new Font("Segoe UI", 8.5f) };
            lblClientDevice = new Label { Text = "等待平板連線...", Location = new Point(16, 58), Size = new Size(300, 22), ForeColor = COLOR_TEXT, Font = new Font("Segoe UI", 10f, FontStyle.Bold) };

            Label lblIpTag = new Label { Text = "平板 IP 位址", Location = new Point(16, 88), Size = new Size(130, 18), ForeColor = COLOR_ACCENT, Font = new Font("Segoe UI", 8.5f) };
            lblClientIp = new Label { Text = "--", Location = new Point(16, 106), Size = new Size(300, 20), ForeColor = COLOR_CYAN, Font = new Font("Consolas", 9.5f) };

            Label lblHzTag = new Label { Text = "即時傳輸頻率 (Hz)", Location = new Point(16, 134), Size = new Size(130, 18), ForeColor = COLOR_ACCENT, Font = new Font("Segoe UI", 8.5f) };
            lblFps = new Label { Text = "0 Hz", Location = new Point(16, 152), Size = new Size(130, 24), ForeColor = COLOR_GREEN, Font = new Font("Segoe UI", 12f, FontStyle.Bold) };

            Label lblPressureTag = new Label { Text = "下筆壓感", Location = new Point(160, 134), Size = new Size(130, 18), ForeColor = COLOR_ACCENT, Font = new Font("Segoe UI", 8.5f) };
            lblPressure = new Label { Text = "0 %", Location = new Point(160, 152), Size = new Size(130, 24), ForeColor = COLOR_CYAN, Font = new Font("Segoe UI", 12f, FontStyle.Bold) };

            Label lblPktTag = new Label { Text = "累積接收封包", Location = new Point(16, 186), Size = new Size(130, 18), ForeColor = COLOR_ACCENT, Font = new Font("Segoe UI", 8.5f) };
            lblPackets = new Label { Text = "0 pkts", Location = new Point(16, 206), Size = new Size(280, 20), ForeColor = Color.FromArgb(180, 170, 200), Font = new Font("Consolas", 9f) };

            cardClient.Controls.Add(lblDeviceTag);
            cardClient.Controls.Add(lblClientDevice);
            cardClient.Controls.Add(lblIpTag);
            cardClient.Controls.Add(lblClientIp);
            cardClient.Controls.Add(lblHzTag);
            cardClient.Controls.Add(lblFps);
            cardClient.Controls.Add(lblPressureTag);
            cardClient.Controls.Add(lblPressure);
            cardClient.Controls.Add(lblPktTag);
            cardClient.Controls.Add(lblPackets);

            // ── 卡片 3：本機網路與廣播 (Host Network) ─────────────────
            Panel cardNet = CreateCardPanel("🌐 電腦區域網路連線 (Host Network)");
            Label lblHostIpInfo = new Label {
                Text = string.Format("電腦本機 IP: {0}\n數據監聽埠號: UDP {1}\n自動探索廣播: UDP {2} (每秒)", localIp, PORT, DISCOVERY_PORT),
                Font = new Font("Consolas", 9.5f),
                ForeColor = Color.FromArgb(200, 190, 220),
                Location = new Point(16, 40),
                Size = new Size(300, 64)
            };
            cardNet.Controls.Add(lblHostIpInfo);

            // ── 卡片 4：快捷操作說明 (Quick Tips) ─────────────────────
            Panel cardSystem = CreateCardPanel("💡 快捷操作說明 (Quick Tips)");
            Label lblTips = new Label {
                Text = "• 按右上角「－」最小化：\n  直接縮小至右下角系統匣，背景持續運作。\n\n• 雙擊右下角系統匣圖示：\n  隨時重新開啟控制台視窗。\n\n• 按右上角「✕」關閉：\n  直接完全退出程式並中斷連線。",
                Font = new Font("Segoe UI", 9f),
                ForeColor = Color.FromArgb(190, 180, 215),
                Location = new Point(16, 36),
                AutoSize = true,
                MaximumSize = new Size(320, 0)
            };
            cardSystem.Controls.Add(lblTips);

            mainLayout.Controls.Add(cardMonitor, 0, 0);
            mainLayout.Controls.Add(cardClient, 1, 0);
            mainLayout.Controls.Add(cardNet, 0, 1);
            mainLayout.Controls.Add(cardSystem, 1, 1);

            this.Controls.Add(mainLayout);
        }

        private Panel CreateCardPanel(string title) {
            Panel panel = new Panel {
                Dock = DockStyle.Fill,
                BackColor = COLOR_CARD,
                Margin = new Padding(6),
                Padding = new Padding(12)
            };
            panel.Paint += (s, e) => {
                using (Pen p = new Pen(COLOR_CARD_BORDER, 1.5f)) {
                    e.Graphics.DrawRectangle(p, 0, 0, panel.Width - 1, panel.Height - 1);
                }
            };
            Label lblTitle = new Label {
                Text = title,
                Font = new Font("Segoe UI", 9.5f, FontStyle.Bold),
                ForeColor = COLOR_TEXT,
                Location = new Point(14, 12),
                AutoSize = true
            };
            panel.Controls.Add(lblTitle);
            return panel;
        }

        private void InitializeTray() {
            trayMenu = new ContextMenuStrip();
            trayMenu.Items.Add("🎨 顯示 TabDraw 控制台", null, (s, e) => RestoreFromTray());
            trayMenu.Items.Add("-");
            trayMenu.Items.Add("✕ 結束 TabDraw", null, (s, e) => {
                this.Close();
            });

            trayIcon = new NotifyIcon {
                Text = "TabDraw - 伺服器運行中",
                Icon = SystemIcons.Application,
                ContextMenuStrip = trayMenu,
                Visible = true
            };
            trayIcon.DoubleClick += (s, e) => RestoreFromTray();
        }

        private void RestoreFromTray() {
            this.Show();
            this.WindowState = FormWindowState.Normal;
            this.BringToFront();
            this.Activate();
        }

        /// <summary>
        /// 核心需求：點擊右上角「－」最小化時，直接縮小到系統匣（隱藏視窗）
        /// </summary>
        protected override void OnResize(EventArgs e) {
            base.OnResize(e);
            if (this.WindowState == FormWindowState.Minimized) {
                this.Hide();
                if (trayIcon != null) {
                    trayIcon.ShowBalloonTip(1500, "TabDraw 運行中", "已縮小至右下角系統匣，手寫筆在背景持續流暢感應！", ToolTipIcon.Info);
                }
            }
        }

        /// <summary>
        /// 核心需求：點擊右上角「✕」關閉時，乾脆俐落完全關閉退出程式
        /// </summary>
        protected override void OnFormClosing(FormClosingEventArgs e) {
            isRunning = false;
            if (trayIcon != null) {
                trayIcon.Visible = false;
                trayIcon.Dispose();
            }
            try {
                if (udpServer != null) udpServer.Close();
            } catch { }
            base.OnFormClosing(e);
        }

        // ── 螢幕管理 ──────────────────────────────────────────────
        private void RefreshMonitorsList() {
            comboMonitors.Items.Clear();
            for (int i = 0; i < Screen.AllScreens.Length; i++) {
                Screen s = Screen.AllScreens[i];
                string name = string.Format("[螢幕 {0}] {1} - {2}x{3}{4}",
                    i + 1, s.DeviceName, s.Bounds.Width, s.Bounds.Height,
                    s.Primary ? " (主螢幕)" : "");
                comboMonitors.Items.Add(name);
                if (s.Primary && comboMonitors.SelectedIndex == -1) {
                    comboMonitors.SelectedIndex = i;
                }
            }
            if (Screen.AllScreens.Length > 1) {
                comboMonitors.Items.Add("[全桌面] 跨螢幕合併桌面 (Virtual Desktop)");
            }
            if (comboMonitors.SelectedIndex == -1 && comboMonitors.Items.Count > 0) {
                comboMonitors.SelectedIndex = 0;
            }
        }

        private void ComboMonitors_SelectedIndexChanged(object sender, EventArgs e) {
            int idx = comboMonitors.SelectedIndex;
            if (idx >= 0 && idx < Screen.AllScreens.Length) {
                selectedScreen = Screen.AllScreens[idx];
            } else {
                selectedScreen = null; // 全桌面
            }
            UpdateSelectedMonitorMetrics();
            previewPanel.Invalidate();
        }

        private void UpdateSelectedMonitorMetrics() {
            if (selectedScreen != null) {
                monitorW = selectedScreen.Bounds.Width;
                monitorH = selectedScreen.Bounds.Height;
                lblSelectedMonitorInfo.Text = string.Format("解析度: {0} x {1} ({2:F2}:1)",
                    monitorW, monitorH, (float)monitorW / monitorH);
            } else {
                monitorW = GetSystemMetrics(78); // SM_CXVIRTUALSCREEN
                monitorH = GetSystemMetrics(79); // SM_CYVIRTUALSCREEN
                lblSelectedMonitorInfo.Text = string.Format("虛擬全螢幕: {0} x {1}", monitorW, monitorH);
            }
        }

        private void PreviewPanel_Paint(object sender, PaintEventArgs e) {
            Graphics g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;

            int pw = previewPanel.Width;
            int ph = previewPanel.Height;

            float aspect = (float)monitorW / Math.Max(1, monitorH);
            float panelAspect = (float)(pw - 20) / Math.Max(1, ph - 20);

            float drawW, drawH;
            if (panelAspect > aspect) {
                drawH = ph - 24;
                drawW = drawH * aspect;
            } else {
                drawW = pw - 24;
                drawH = drawW / aspect;
            }

            float drawX = (pw - drawW) / 2f;
            float drawY = (ph - drawH) / 2f;

            using (SolidBrush b = new SolidBrush(Color.FromArgb(32, 22, 60))) {
                g.FillRectangle(b, drawX, drawY, drawW, drawH);
            }
            using (Pen p = new Pen(COLOR_CYAN, 2f)) {
                g.DrawRectangle(p, drawX, drawY, drawW, drawH);
            }

            string text = string.Format("{0} x {1}", monitorW, monitorH);
            using (Font f = new Font("Segoe UI", 9f, FontStyle.Bold))
            using (SolidBrush tb = new SolidBrush(COLOR_TEXT)) {
                SizeF sf = g.MeasureString(text, f);
                g.DrawString(text, f, tb, drawX + (drawW - sf.Width) / 2f, drawY + (drawH - sf.Height) / 2f);
            }
        }

        // ── UDP 數據與事件處理 ──────────────────────────────────
        private void StartUdpServer() {
            try {
                udpServer = new UdpClient(PORT);
            } catch (Exception ex) {
                lblServerStatus.Text = "✕ 埠號 9999 被佔用: " + ex.Message;
                lblServerStatus.ForeColor = Color.Red;
                return;
            }

            receiveThread = new Thread(() => {
                IPEndPoint ep = new IPEndPoint(IPAddress.Any, PORT);
                while (isRunning) {
                    try {
                        byte[] bytes = udpServer.Receive(ref ep);
                        if (bytes == null || bytes.Length == 0) continue;

                        // 判斷是否為平板註冊/握手字串
                        if (bytes.Length > 15 && bytes[0] == 'T' && bytes[1] == 'A' && bytes[2] == 'B') {
                            string msg = Encoding.UTF8.GetString(bytes).Trim();
                            if (msg.StartsWith("TABDRAW_CLIENT:")) {
                                string[] parts = msg.Split(':');
                                if (parts.Length >= 2) {
                                    clientDeviceName = parts[1];
                                    clientIpAddress = ep.Address.ToString();
                                }
                            }
                            continue;
                        }

                        // 二進位筆跡封包 (22 Bytes)
                        if (bytes.Length < 22) continue;
                        if (bytes[0] != 0xAB || bytes[1] != 0xCD) continue;

                        byte xor = 0;
                        for (int i = 0; i < 21; i++) xor ^= bytes[i];
                        if (xor != bytes[21]) continue;

                        byte flag = bytes[4];
                        float normX = ReadFloatBE(bytes, 5);
                        float normY = ReadFloatBE(bytes, 9);
                        float pressure = ReadFloatBE(bytes, 13);

                        Interlocked.Increment(ref totalPackets);
                        Interlocked.Increment(ref packetCounter);
                        currentPressure = pressure;
                        clientIpAddress = ep.Address.ToString();

                        MoveCursor(normX, normY, flag);
                    } catch (Exception) {
                        // 忽略偶發性接收異常
                    }
                }
            }) { IsBackground = true };
            receiveThread.Start();
        }

        /// <summary>
        /// 硬體級即時游標移動：純 SendInput 原生佇列，支援多螢幕虛擬桌面，絕不衝突閃爍
        /// </summary>
        private void MoveCursor(float normX, float normY, byte flag) {
            float clampedX = Math.Max(0.0f, Math.Min(normX, 1.0f));
            float clampedY = Math.Max(0.0f, Math.Min(normY, 1.0f));

            int vL = GetSystemMetrics(76); // SM_XVIRTUALSCREEN
            int vT = GetSystemMetrics(77); // SM_YVIRTUALSCREEN
            int vW = GetSystemMetrics(78); // SM_CXVIRTUALSCREEN
            int vH = GetSystemMetrics(79); // SM_CYVIRTUALSCREEN

            int targetPixelX, targetPixelY;

            if (selectedScreen != null) {
                targetPixelX = selectedScreen.Bounds.Left + (int)(clampedX * selectedScreen.Bounds.Width);
                targetPixelY = selectedScreen.Bounds.Top + (int)(clampedY * selectedScreen.Bounds.Height);
            } else {
                targetPixelX = vL + (int)(clampedX * vW);
                targetPixelY = vT + (int)(clampedY * vH);
            }

            int normVx = (int)(((float)(targetPixelX - vL) / Math.Max(1, vW)) * 65535);
            int normVy = (int)(((float)(targetPixelY - vT) / Math.Max(1, vH)) * 65535);

            uint flags = MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK;
            if (flag == 1) { // DOWN (下筆開始繪圖)
                flags |= MOUSEEVENTF_LEFTDOWN;
            } else if (flag == 3) { // UP (提筆結束)
                flags |= MOUSEEVENTF_LEFTUP;
            }

            INPUT[] inputs = new INPUT[1];
            inputs[0].type = INPUT_MOUSE;
            inputs[0].mi = new MOUSEINPUT {
                dx = normVx,
                dy = normVy,
                mouseData = 0,
                dwFlags = flags,
                time = 0,
                dwExtraInfo = IntPtr.Zero
            };

            SendInput(1, inputs, Marshal.SizeOf(typeof(INPUT)));
        }

        private void StartBroadcasting() {
            broadcastThread = new Thread(() => {
                UdpClient beacon = null;
                try {
                    beacon = new UdpClient();
                    beacon.EnableBroadcast = true;
                    IPEndPoint target = new IPEndPoint(IPAddress.Broadcast, DISCOVERY_PORT);

                    while (isRunning) {
                        // 廣播格式：TABDRAW_HOST:<IP>:<PORT>:<WIDTH>x<HEIGHT>
                        string msgStr = string.Format("TABDRAW_HOST:{0}:{1}:{2}x{3}", localIp, PORT, monitorW, monitorH);
                        byte[] msg = Encoding.ASCII.GetBytes(msgStr);
                        beacon.Send(msg, msg.Length, target);
                        Thread.Sleep(1000);
                    }
                } catch { }
                finally {
                    if (beacon != null) beacon.Close();
                }
            }) { IsBackground = true };
            broadcastThread.Start();
        }

        private void StartStatsLoop() {
            statsThread = new Thread(() => {
                while (isRunning) {
                    Thread.Sleep(1000);
                    int hz = Interlocked.Exchange(ref packetCounter, 0);
                    currentHz = hz;

                    if (this.IsHandleCreated && !this.IsDisposed) {
                        this.BeginInvoke((MethodInvoker)delegate {
                            lblFps.Text = string.Format("{0} Hz", currentHz);
                            lblPackets.Text = string.Format("{0:N0} pkts", totalPackets);
                            lblPressure.Text = string.Format("{0:F0} %", currentPressure * 100);
                            lblClientDevice.Text = clientDeviceName;
                            lblClientIp.Text = clientIpAddress;
                        });
                    }
                }
            }) { IsBackground = true };
            statsThread.Start();
        }

        private static float ReadFloatBE(byte[] bytes, int offset) {
            byte[] tmp = new byte[4];
            tmp[0] = bytes[offset + 3];
            tmp[1] = bytes[offset + 2];
            tmp[2] = bytes[offset + 1];
            tmp[3] = bytes[offset];
            return BitConverter.ToSingle(tmp, 0);
        }

        private static string GetBestLocalIPv4() {
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
    }
}
