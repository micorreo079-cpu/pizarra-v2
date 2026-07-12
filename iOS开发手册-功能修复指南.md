# iOS端功能修复手册

本文档说明iOS端需要修复和实现的功能，以与Android端保持一致。

## 一、Ping-Pong心跳检测功能（必须实现）

### 1.1 功能说明
Ping-Pong心跳检测用于保持连接活跃，及时检测连接断开情况。

### 1.2 消息格式
- **PING消息**：`"PING_REALBOARD"`
- **PONG消息**：`"PONG_REALBOARD"`

### 1.3 客户端（iOS）需要实现的功能

#### 1.3.1 定时发送PING消息
- **发送间隔**：每5秒（5000毫秒）发送一次PING消息
- **触发时机**：Socket连接建立后立即启动
- **发送方式**：使用文本消息发送（与发送其他控制消息相同的方式）

```swift
// 伪代码示例
private var pingTimer: Timer?
private let PING_INTERVAL: TimeInterval = 5.0
private let PING_MESSAGE = "PING_REALBOARD"

func startPingTimer() {
    stopPingTimer()
    pingTimer = Timer.scheduledTimer(withTimeInterval: PING_INTERVAL, repeats: true) { [weak self] _ in
        guard let self = self, self.isConnected else { return }
        self.sendPing()
    }
}

func stopPingTimer() {
    pingTimer?.invalidate()
    pingTimer = nil
}

func sendPing() {
    sendTextMessage(PING_MESSAGE)
}
```

#### 1.3.2 接收并处理PONG消息
- **处理方式**：当收到`"PONG_REALBOARD"`消息时，更新最后一次收到PONG的时间戳
- **消息类型判断**：需要区分控制消息和图片数据

```swift
// 伪代码示例
private var lastPongTime: Date = Date()
private let PONG_MESSAGE = "PONG_REALBOARD"
private let PONG_TIMEOUT: TimeInterval = 10.0  // 10秒超时
private let PONG_CHECK_INTERVAL: TimeInterval = 3.0  // 每3秒检查一次

func handleMessage(_ message: String) {
    if message == PONG_MESSAGE {
        lastPongTime = Date()
        print("收到服务端Pong，连接正常")
    }
    // 处理其他消息...
}
```

#### 1.3.3 PONG超时检测
- **超时时间**：10秒（10000毫秒）未收到PONG消息则认为连接断开
- **检查间隔**：每3秒（3000毫秒）检查一次是否超时
- **超时处理**：检测到超时后，主动断开Socket连接

```swift
// 伪代码示例
private var pongTimeoutTimer: Timer?

func startPongTimeoutCheck() {
    stopPongTimeoutCheck()
    lastPongTime = Date()
    pongTimeoutTimer = Timer.scheduledTimer(withTimeInterval: PONG_CHECK_INTERVAL, repeats: true) { [weak self] _ in
        guard let self = self, self.isConnected else { return }
        let elapsed = Date().timeIntervalSince(self.lastPongTime)
        if elapsed > self.PONG_TIMEOUT {
            print("Pong超时，\(elapsed)秒未收到Pong，准备断开连接")
            self.disconnect()
        }
    }
}

func stopPongTimeoutCheck() {
    pongTimeoutTimer?.invalidate()
    pongTimeoutTimer = nil
}
```

#### 1.3.4 连接状态管理
- **启动时机**：Socket连接成功后，同时启动PING定时器和PONG超时检测
- **停止时机**：Socket断开连接时，停止PING定时器和PONG超时检测

```swift
// 伪代码示例
func onSocketConnected() {
    isConnected = true
    lastPongTime = Date()  // 初始化PONG时间
    startPingTimer()
    startPongTimeoutCheck()
}

func onSocketDisconnected() {
    isConnected = false
    stopPingTimer()
    stopPongTimeoutCheck()
}
```

### 1.4 服务端（iOS）需要实现的功能

#### 1.4.1 接收并响应PING消息
- **处理方式**：当收到`"PING_REALBOARD"`消息时，立即回复`"PONG_REALBOARD"`消息

```swift
// 伪代码示例
func handleMessage(_ message: String, from clientSocket: Socket) {
    if message == "PING_REALBOARD" {
        sendTextMessage("PONG_REALBOARD", to: clientSocket)
        print("收到客户端Ping，已回复Pong")
    }
    // 处理其他消息...
}
```

## 二、画布尺寸规范（必须修复）

### 2.1 标准画布尺寸
- **宽度**：1650像素
- **高度**：2200像素
- **说明**：这是SONY DPT RP1墨水屏的标准分辨率，必须严格使用此尺寸

### 2.2 图片发送时的尺寸要求
- 发送给Android客户端（墨水屏）的图片必须是**1650x2200像素**
- 如果原始图片尺寸不同，需要**缩放**到1650x2200
- **重要**：不要裁剪，要按比例缩放（保持宽高比）或拉伸填充

### 2.3 实现建议

```swift
// 伪代码示例：缩放图片到指定尺寸
func resizeImageToCanvasSize(_ image: UIImage) -> UIImage? {
    let targetSize = CGSize(width: 1650, height: 2200)
    
    // 方法1：保持宽高比，可能会有黑边
    UIGraphicsBeginImageContextWithOptions(targetSize, false, 1.0)
    let rect = calculateAspectFitRect(imageSize: image.size, targetSize: targetSize)
    image.draw(in: rect)
    let scaledImage = UIGraphicsGetImageFromCurrentImageContext()
    UIGraphicsEndImageContext()
    
    // 或者方法2：拉伸填充整个画布（可能会变形）
    // UIGraphicsBeginImageContextWithOptions(targetSize, false, 1.0)
    // image.draw(in: CGRect(origin: .zero, size: targetSize))
    // let scaledImage = UIGraphicsGetImageFromCurrentImageContext()
    // UIGraphicsEndImageContext()
    
    return scaledImage
}
```

## 三、画布颜色处理（必须修复）

### 3.1 支持的颜色模式
- **黑色画布**：背景色为黑色（RGB: 0, 0, 0）
- **白色画布**：背景色为白色（RGB: 255, 255, 255）

### 3.2 发送图片时的颜色要求
- 发送的PNG图片必须包含正确的背景色
- **黑色画布模式**：图片背景应为黑色（RGB接近0,0,0）
- **白色画布模式**：图片背景应为白色（RGB接近255,255,255）

### 3.3 图片格式要求
- **格式**：PNG格式（支持透明度）
- **质量**：100%质量（无压缩）
- **颜色模式**：ARGB_8888（32位，包含Alpha通道）

### 3.4 实现建议

```swift
// 伪代码示例：创建指定背景色的画布图片
func createCanvasImage(drawing: UIImage, backgroundColor: UIColor) -> UIImage? {
    let canvasSize = CGSize(width: 1650, height: 2200)
    
    UIGraphicsBeginImageContextWithOptions(canvasSize, false, 1.0)
    
    // 先绘制背景色
    backgroundColor.setFill()
    UIRectFill(CGRect(origin: .zero, size: canvasSize))
    
    // 再绘制内容
    drawing.draw(at: .zero)
    
    let resultImage = UIGraphicsGetImageFromCurrentImageContext()
    UIGraphicsEndImageContext()
    
    return resultImage
}

// 发送图片时
func sendImage(_ image: UIImage, canvasColor: CanvasColor) {
    let backgroundColor = canvasColor == .black ? UIColor.black : UIColor.white
    guard let canvasImage = createCanvasImage(drawing: image, backgroundColor: backgroundColor) else {
        return
    }
    
    // 压缩为PNG
    guard let imageData = canvasImage.pngData() else {
        return
    }
    
    // 发送图片数据（见下一节）
    sendImageData(imageData)
}
```

## 四、图片数据发送格式（必须修复）

### 4.1 数据包格式
发送图片数据时，需要按照以下格式：

1. **先发送4字节的整数**：表示图片数据的字节大小（Big-Endian字节序）
2. **然后发送图片字节数组**：完整的PNG图片数据

### 4.2 接收确认
- 客户端收到图片后，会发送文本消息：`"IMAGE_RECEIVED"`
- 服务端应该等待并接收这个确认消息

### 4.3 实现示例

```swift
// 伪代码示例：发送图片数据
func sendImageData(_ imageData: Data) {
    guard let socket = clientSocket else { return }
    
    var size = UInt32(imageData.count).bigEndian
    let sizeData = Data(bytes: &size, count: 4)
    
    // 先发送4字节的大小
    socket.write(sizeData, withTimeout: -1, tag: 0)
    
    // 然后发送图片数据
    socket.write(imageData, withTimeout: -1, tag: 1)
    
    // 等待确认消息
    socket.readData(toLength: 100, withTimeout: 30, tag: 2)
}

// 处理确认消息
func socket(_ sock: Socket, didRead data: Data, withTag tag: Int) {
    if tag == 2 {
        if let message = String(data: data, encoding: .utf8),
           message.trimmingCharacters(in: .whitespacesAndNewlines) == "IMAGE_RECEIVED" {
            print("收到图片接收确认")
        }
    }
}
```

### 4.4 控制消息与图片数据的区分

**重要**：需要在同一个Socket连接上区分控制消息（如PING/PONG）和图片数据。

**Android端的实现方式**：
- 图片数据：先发送4字节大小，然后发送数据
- 控制消息：直接发送文本（使用PrintWriter.println()，会自动添加换行符）

**建议的区分方式**：
1. **方案1**：使用不同的Socket连接（一个用于控制消息，一个用于图片数据）
2. **方案2**：在同一个Socket上，先尝试读取4字节，判断是否为控制消息：
   - 如果读取到的数据可以解析为文本消息（长度<=128字节，且全部为可打印字符），则作为控制消息处理
   - 否则作为图片数据大小处理，然后读取对应长度的图片数据

## 五、总结检查清单

请确保iOS端实现以下功能：

- [ ] **Ping-Pong心跳**：
  - [ ] 客户端每5秒发送PING消息
  - [ ] 服务端收到PING后回复PONG消息
  - [ ] 客户端检测PONG超时（10秒未收到则断开）
  - [ ] 连接建立时启动心跳，断开时停止心跳

- [ ] **画布尺寸**：
  - [ ] 所有发送的图片尺寸为1650x2200像素
  - [ ] 图片缩放逻辑正确实现

- [ ] **画布颜色**：
  - [ ] 支持黑色和白色画布模式
  - [ ] 发送的图片包含正确的背景色
  - [ ] PNG格式，100%质量，ARGB_8888颜色模式

- [ ] **数据发送格式**：
  - [ ] 先发送4字节大小（Big-Endian）
  - [ ] 再发送图片字节数组
  - [ ] 正确处理"IMAGE_RECEIVED"确认消息

- [ ] **消息区分**：
  - [ ] 能够区分控制消息（PING/PONG）和图片数据
  - [ ] 在同一Socket连接上正确处理两种类型的数据

## 六、参考常量定义

```swift
// 消息常量
let PING_MESSAGE = "PING_REALBOARD"
let PONG_MESSAGE = "PONG_REALBOARD"
let IMAGE_RECEIVED_MESSAGE = "IMAGE_RECEIVED"

// 时间常量（毫秒）
let PING_INTERVAL: TimeInterval = 5.0        // 5秒
let PONG_TIMEOUT: TimeInterval = 10.0        // 10秒
let PONG_CHECK_INTERVAL: TimeInterval = 3.0  // 3秒

// 画布尺寸常量（像素）
let CANVAS_WIDTH: CGFloat = 1650
let CANVAS_HEIGHT: CGFloat = 2200
```

---

**注意事项**：
1. 所有时间单位需要转换为iOS使用的时间单位（TimeInterval以秒为单位）
2. 字节序需要确保使用Big-Endian（网络字节序）
3. 测试时请确保与Android端完全兼容
4. 建议添加详细的日志输出，便于调试和问题定位







