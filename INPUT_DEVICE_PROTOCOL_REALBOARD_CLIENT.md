# RealBoard 客户端 — Wi‑Fi / 蓝牙输入接口说明（交接文档）

本文档面向**第三方输入设备厂商**（例如外接数位板、Android 发送端等），说明当前 **墨水屏客户端应用**（包名示例：`com.example.newdrawingapp`）如何通过 **TCP（Wi‑Fi）** 与 **蓝牙 RFCOMM（SPP）** 接收画面数据，以及如何与之交互（确认、断开、可选笔划回传）。

**角色约定**

| 链路 | 本客户端（墨水屏 / ReaderPaper） | 输入端设备（贵司） |
|------|----------------------------------|-------------------|
| Wi‑Fi TCP | **TCP Client**：主动连接贵司监听的端口 | **TCP Server**：监听端口，下发图片；可选接收文本行协议 |
| 蓝牙 | **RFCOMM Client**：对已配对设备发起连接 | **RFCOMM Server**：使用标准 SPP UUID 监听并接受连接 |

下文凡称「客户端」均指墨水屏侧 App；「服务端」均指贵司输入设备侧进程。

---

## 一、共用：二进制图片帧格式（Wi‑Fi 与蓝牙完全一致）

两端在已建立的 **字节流**（TCP `OutputStream` / 蓝牙 RFCOMM socket）上，按下列格式连续发送多张图片：

1. **长度前缀**：固定 **4 字节**，**大端序（Big‑Endian）** Java `writeInt/readInt`，表示紧随其后紧跟的图片载荷字节数 `N`。
2. **图片载荷**：紧随其后的 **`N` 字节**，接收端须 **`DataInputStream.readFully(byte[], 0, N)`**（或等价地循环读到满 `N` 字节）；**在开始下一轮的 `readInt()` 之前必须已消费完本轮全部 `N` 字节**。  
   ⚠️ **不得在承载该二进制协议的同一 **`InputStream` 上使用 `BufferedReader`/`Scanner` 去读其它文本**：预读会打乱边界，易出现「伪长度」巨大整数后己方主动断连等现象。

**约束（与本仓库实现一致）：**

- **`N`** 必须满足：`0 < N ≤ NetworkUtils.MAX_BINARY_IMAGE_FRAME_BYTES`（常量 **50 MiB**，与 RealBoard BluetoothServerManager 常量同名语义对齐）。
- 载荷须能被 Android **`BitmapFactory.decodeByteArray`** 成功解码；常用格式包括 **PNG、JPEG、WEBP（取决于系统版本）** 等。
- **不要求**固定宽高；解码后的位图会在墨水屏端按界面逻辑缩放显示并用于背景色判断（多点采样推断黑白底）。
- **颜色**：解码优先 **`ARGB_8888`**；透明通道若存在按平台默认行为处理。

**发送方可伪代码示例（Java）：**

```java
void sendImage(OutputStream out, byte[] pngOrJpegBytes) throws IOException {
    DataOutputStream dos = new DataOutputStream(out);
    dos.writeInt(pngOrJpegBytes.length); // big-endian
    dos.write(pngOrJpegBytes);
    dos.flush();
}
```

---

## 二、Wi‑Fi（TCP）

### 2.1 传输层

- **协议**：TCP。
- **默认端口**：**8888**（常量 `DrawingSocketManager.DEFAULT_PORT`）。
- **连接方向**：墨水屏 App **主动连接** `贵司_IP:端口`。
- **典型入口**：手动填写 `IP[:端口]`（端口省略则用 8888）；或局域网广播发现后再连（见下）。
- **Socket 选项（墨水屏侧）**：`TCP_NODELAY = true`，`keepAlive = true`，读写缓冲区约 **65536**，读超时 **0（无限阻塞等待下一帧）**，手动连接超时 **5000 ms**，快速连接尝试 **2000 ms**。

### 2.2 墨水屏 → 服务端（出站）：文本行协议（UTF‑8）

连接建立后，墨水屏在同一条 TCP 连接上会 **异步批量写出多行 UTF‑8 文本**，每条逻辑消息一行，以 **`LF`（`\n`，ASCII 0x0A）** 结尾；实际实现中约 **每 16 ms** 合并队列中的多行再一次 `flush`，贵司解析时应 **按字节流缓冲并按行拆分**，勿假定「一包一行」。

**已实现的消息类型：**

| 行内容格式 | 含义 |
|------------|------|
| `IMAGE_RECEIVED` | 已在本机完成一帧图片的解码与界面更新流程后排队发送，用于通知服务端「这一帧已显示」。 |
| `CLIENT_DISCONNECT` | 墨水屏主动断开前尝试发送（`PrintWriter.println`），通知对端关闭会话。 |
| `STROKE_START:bx,by,color,w` | 电磁笔落笔一笔：**bitmap 像素坐标** `(bx,by)`，`color` 为 Android **ARGB int**（32 位），`w` 为线宽取整后的整数（像素量级）。 |
| `STROKE:bx1,by1|bx2,by2|...` | 笔划中间采样点批量上报；`|` 分隔多个点；点数批次上限见下。 |
| `STROKE_END` | 一笔结束。 |

笔划相关仅在墨水屏电磁笔书写且 TCP 已连接时发送；坐标通过 **`viewToBitmapXY`** 映射为 **当前显示位图像素坐标**，贵司若要对齐画布应对齐 **与本客户端一致的 bitmap 坐标系**（与解码后的整图分辨率一致）。

**批次**：连续 `STROKE` 点缓冲 **满 12 个点**会自动冲刷一行 `STROKE:...`（常量 `STROKE_BATCH_SIZE = 12`）。

**说明**：代码中存在常量 `IMAGE_RECEIVED_CLIENT`，但实际排队的确认字符串为 **`IMAGE_RECEIVED`**；请以 **`IMAGE_RECEIVED`** 为准。

### 2.3 服务端 → 墨水屏（入站）

唯一结构化二进制协议即 **第一节「长度前缀 + 图片载荷」**；循环发送即可构成视频流式多张静帧。

墨水屏进程内调用顺序：`receiveImage()` → `BitmapFactory.decodeByteArray()` → UI 更新 → 队列写入 `IMAGE_RECEIVED`。

### 2.4 局域网发现（UDP，墨水屏「找服务器」）

自动连接时使用 **`NetworkUtils.searchServer()`**，与手动 IP 并行存在于产品中。

**广播参数摘要：**

- 客户端向 **`255.255.255.255`** 发送 UDP 载荷（UTF‑8 字节）：固定字符串 **`DRAWING_APP_DISCOVERY`**。
- **探测端口序列**：动态生成的优先端口列表；包含上次成功端口、`COMMON_PORTS` 列表 **8888, 8889, 8890, 8080, 8081, 8000, 9000, 9090**，以及 **`8889–8899`** 范围内尚未列入的端口等。
- **期望应答**：UDP 回包内容为单行 UTF‑8，格式必须为：  
  **`DRAWING_APP_SERVER:<tcpPort>`**  
  其中 `<tcpPort>` 为贵司 TCP 监听端口（整数）；客户端随后向应答来源 IP、该端口发起 **TCP** 连接。
- 单次 `receive` 超时约 **2000 ms**，端口循环尝试直至成功或全部超时。

贵司若在输入设备上实现「被地发现」，需在上述端口之一（通常 **8889**）监听 UDP，收到 `DRAWING_APP_DISCOVERY` 后向来源地址回复 **`DRAWING_APP_SERVER:8888`**（端口可按实际监听修改）。

**注意**：仓库内另有一份 `ServerDiscoveryManager`（字符串 `DRAWING_SERVER_DISCOVERY` / `SERVER:` / UDP **8081**），**当前自动连逻辑走的是 `NetworkUtils` 这一套**；若贵司只做单一实现，请以 **`DRAWING_APP_DISCOVERY` / `DRAWING_APP_SERVER:`** 为准并与我们对齐测试。

---

## 三、蓝牙（RFCOMM / SPP）

### 3.1 服务发现与连接方向

- **UUID**：标准串口配置文件 **SPP**，  
  **`00001101-0000-1000-8000-00805F9B34FB`**  
  （代码中与 Android `BluetoothManager.SPP_UUID` 一致）。
- **连接发起方**：墨水屏设备对已配对列表中的设备依次尝试 **`connect()`**（优先可选 MAC，逻辑见实现）。
- **Socket 创建顺序（墨水屏侧）**：优先 **`createInsecureRfcommSocketToServiceRecord(UUID)`**；失败则反射 **`createRfcommSocket(1)`**；再失败 **`createRfcommSocketToServiceRecord(UUID)`**（安全模式）。

贵司设备须作为 **RFCOMM 服务端**：使用与上述 UUID 一致的 **`listenUsingRfcommWithServiceRecord` / `listenUsingInsecureRfcommWithServiceRecord`**（具体 API 按平台），并接受墨水屏的连接。

### 3.2 载荷格式

与 TCP **完全相同**：**4 字节大端 `int` 长度 `N`** + **`N` 字节图片**；墨水屏读取实现见 **`BluetoothManager.receiveLoop`**：  
`BufferedInputStream` → **`DataInputStream.readInt()`** → **`readFully(N)`**，单线程帧循环后再写 ACK。

墨水屏不得在 RFCOMM **入站**上混用 **`BufferedReader` 读取其它内容**。

### 3.3 墨水屏 → 服务端（出站 ACK）

每接收并显示完一帧（主线程回调返回）后，在同一 RFCOMM 连接的 **`OutputStream`** 写入 ASCII：

```text
IMAGE_RECEIVED\n
```

即字节序列：`49 4D 41 47 45 5F 52 45 43 45 49 56 45 44 0A`（UTF‑8 与 ASCII 一致）。

---

## 四、交互时序建议（便于联调）

1. 输入端监听（TCP 或蓝牙 RFCOMM）。
2. 墨水屏连接成功。
3. 输入端发送 **第一帧**：`writeInt(len)` + `len` 字节 PNG/JPEG …  
4. 墨水屏解码显示后，将在 TCP 队列一行 **`IMAGE_RECEIVED`**，或在蓝牙写出 **`IMAGE_RECEIVED\n`**。
5. 输入端可发下一帧；重复 3–4。
6. 断开：墨水屏可能在 TCP 上发送 **`CLIENT_DISCONNECT`** 后关闭 socket；蓝牙侧关闭 socket 后墨水屏读线程退出并重试配对连接。

---

## 五、兼容性与工程注意

- **字节序**：长度前缀务必 **Big‑Endian**，勿用小端序。
- **TCP 文本**：必须容忍 **粘包/半包**，按 `\n` 切行解析。
- **接收图片**：必须使用 **`BufferedInputStream` + `DataInputStream`**，载荷用 **`readFully`**（见 `BluetoothManager`、`NetworkUtils.receiveImage`），禁止同一入站流再包 `BufferedReader`。
- **解码失败**：载荷无法解码时该帧可能被丢弃且无 ACK（取决于客户端分支）；建议 PNG 无损便于对齐像素。
- **分辨率**：若数位板画布需与墨水屏像素对齐，建议在联合测试中约定输出分辨率等于墨水屏当前画布逻辑分辨率（设备相关，需在实测中确认）。

---

## 六、代码溯源（便于贵司核对）

| 模块 | 路径（相对于 Android 工程） |
|------|------------------------------|
| TCP 连接与接收循环 | `app/src/main/java/.../network/DrawingSocketManager.kt` |
| UDP 发现 / TCP 收图 | `app/src/main/java/.../network/NetworkUtils.kt` |
| 蓝牙 RFCOMM | `app/src/main/java/.../network/BluetoothManager.kt` |
| 位图解码与显示 | `app/src/main/java/.../ClientActivity.kt`（`updateImage`） |

---

## 七、联系方式 / 修订记录（可自行填写）

- 文档生成依据仓库：**NewDrawing-version03 客户端工程**（以现场检出为准）。
- 修订日期：**2026-05-13**

---

*文档用途：提供给第三方硬件/软件厂商实现输入端兼容；如有字段扩展需求，建议在长度前缀帧之上另行约定版本字节或通过首条 TCP 文本握手协商。*
