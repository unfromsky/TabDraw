# 🎨 TabDraw

> **全平板通用 · 硬體級超低延遲 · 手寫筆空中懸浮與壓感 · 原生 UDP 傳輸 · 平板化身專業電腦數位板**

---

## ✨ 核心特色

- ⚡ **120Hz ~ 240Hz 原生極致流暢度**：基於 UDP 二進位高效協議，筆尖所至游標即至，無傳統 VNC/RDP 的畫面傳輸負擔。
- 🎨 **1:1 螢幕長寬比自動對齊**：平板端依據電腦目標螢幕解析度動態計算 1:1 感應工作區，在不同螢幕比例下繪製正圓不變形。
- 📱 **防倒角安全邊距**：邊界保留適當安全留白，避免平板螢幕四角實體圓弧倒角遮蔽繪圖定位。
- 🖥️ **雙版本接收端（自由選擇）**：
  - **視窗控制台（GUI 介面版）**：深紫科技風面板，提供多螢幕切換選單、平板型號識別、即時 Hz 統計與最小化至 Windows 系統匣功能。
  - **CMD 終端版（Console 版）**：極簡輕量、低資源佔用，最純粹穩定的備用終端。
- 🖥️ **多螢幕無縫支援**：支援「螢幕 1 (主螢幕)」、「螢幕 2」或「跨螢幕虛擬全桌面」切換。
- 🔍 **自動探索廣播（Auto-Discovery）**：電腦與平板處於同一區域網路（Wi-Fi）時自動配對連線，完全免手動輸入 IP。
- ✍️ **完整觸控筆支援**：原生攔截 Android `TOOL_TYPE_STYLUS`，支援下筆、懸浮游標（Hover）、0~4096 階連續壓感（Pressure）與筆身傾角（Tilt）。

---

## 🏗️ 系統架構

```
平板端 (Android / HyperOS)                      電腦端 (Windows 10/11)
┌─────────────────────────────────┐             ┌──────────────────────────────────┐
│  StylusCanvasView               │             │  TabDraw_Host.exe (GUI 介面版)   │
│  ├─ onHoverEvent() (120Hz 懸浮) │   UDP       │  ├─ UDP Socket :9999 (二進位接收)│
│  ├─ onTouchEvent() (壓感/傾角)  │ ──────────▶ │  ├─ SendInput (微軟原生輸入管線) │
│  └─ 1:1 比例感應區 + 科技格線   │  22-Byte    │  ├─ 多螢幕目標切換 (VIRTUALDESK) │
│                                 │   Packet    │  └─ 系統匣常駐 (NotifyIcon)      │
│  UdpTransmitter                 │             │                                  │
│  └─ 零積壓即時丟棄佇列          │             │  Receiver_CMD.exe (CMD 備用版)   │
│                                 │ ◀────────── │  └─ 輕量主控台模式               │
│  UDP 探索監聽器 (Port 9998)     │  1Hz Beacon │                                  │
└─────────────────────────────────┘             └──────────────────────────────────┘
```

---

## 🚀 快速開始

### 1. 電腦端（Windows）
- 直接雙擊執行 **`TabDraw_Host.exe`**（或點擊桌面捷徑 **`TabDraw (介面版)`**）。
- 亦可執行 **`Receiver_CMD.exe`** 使用輕量主控台黑視窗版本。

### 2. 平板端（Android）
- 將 **`TabDraw.apk`** 傳輸至平板安裝，或直接點擊 APK 檔進行安裝。
- 確保電腦與平板連接至**同一個 Wi-Fi 區域網路**。
- 開啟 App，系統將自動搜尋主機並於 1 秒內建立連線。

---

## 📦 二進位封包格式（22 Bytes, Big-Endian）

| 偏移 (Offset) | 欄位 | 大小 | 說明 |
|---|---|---|---|
| 0 ~ 1 | Header | 2B | `0xAB 0xCD`（魔術字節） |
| 2 ~ 3 | Seq | 2B | uint16 封包序號（序號遞增） |
| 4 | Flag | 1B | `0`=Hover, `1`=Down, `2`=Move, `3`=Up |
| 5 ~ 8 | NormX | 4B | float32 X 軸正規化座標（`0.0 ~ 1.0`） |
| 9 ~ 12 | NormY | 4B | float32 Y 軸正規化座標（`0.0 ~ 1.0`） |
| 13 ~ 16 | Pressure | 4B | float32 筆尖壓感（`0.0 ~ 1.0`） |
| 17 ~ 20 | Tilt | 4B | float32 筆身傾角（弧度） |
| 21 | Checksum | 1B | XOR 校驗碼（`bytes[0..20]`） |

---

## 🛠️ 自行編譯指南

### Android 平板端 APK 編譯
本專案採用純原生 Kotlin 與 Android Gradle Plugin：
```powershell
.\gradlew.bat assembleDebug
```
產出的 APK 位於 `app/build/outputs/apk/debug/app-debug.apk`。

### Windows 電腦端 Host 編譯
本專案採用原生 Win32 / .NET Windows Forms 架構，使用系統內建 `csc.exe` 即可完成零依賴編譯：
```powershell
# 編譯 GUI 介面版
& "C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe" /target:winexe /optimize+ /platform:anycpu /out:TabDraw_Host.exe /r:System.Windows.Forms.dll,System.Drawing.dll TabDraw_Host.cs

# 編譯 CMD 終端版
& "C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe" /target:exe /optimize+ /platform:anycpu /out:Receiver_CMD.exe Receiver.cs
```

---

## 📄 開源許可
本專案採用 MIT License 開源許可證。
