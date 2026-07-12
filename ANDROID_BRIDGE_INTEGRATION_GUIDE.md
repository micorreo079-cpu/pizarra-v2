# Android RealBoard Bridge & URL Scheme Integration Guide
> **写给负责实现 Android RealBoard 项目的 AI**
> Version: 1.0 | Date: 2026-04-06

---

## 一、功能概述

本指南实现两个独立功能，可分别开启：

| 功能 | 说明 |
|------|------|
| **mr.smith URL Scheme 接收** | 接收 `realboard://push?image=...` 跳转，解码 Base64 图片，显示到画布并自动发送到墨水屏 |
| **Bridge 轮询服务** | 每 3 秒轮询一个 HTTP endpoint，当 `timeUpdated` 字段变化时通知 UI 更新 |

---

## 二、涉及文件清单

| 文件 | 操作 |
|------|------|
| `AndroidManifest.xml` | 注册 `realboard://push` intent-filter |
| `MainActivity.kt` | 处理 URL Scheme deep link，解码图片，调用业务逻辑 |
| `BridgeService.kt` | 新建：HTTP 轮询服务（直接复制本文代码） |
| `StorageHelper.kt` | 新建（或修改已有）：保存 Bridge endpoint 和开关状态到 SharedPreferences |
| 主屏幕 Activity/Fragment | 添加 Bridge 开关 UI + 配置对话框 |

---

## 三、AndroidManifest.xml

在 `MainActivity` 的 `<activity>` 标签内，**追加**以下 `<intent-filter>`（保留原有的 MAIN/LAUNCHER filter）：

```xml
<!-- 处理 realboard://push URL Scheme（mr.smith 推送图片） -->
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="realboard" android:host="push" />
</intent-filter>
```

确保 `<activity>` 有 `android:launchMode="singleTop"`（防止重复创建实例）：

```xml
<activity
    android:name=".MainActivity"
    android:launchMode="singleTop"
    ...>
```

---

## 四、MainActivity.kt — URL Scheme 处理

在你的 `MainActivity.kt` 中添加以下代码。**不要替换整个文件，只需添加相应方法。**

### 4.1 处理 URL Scheme 入口（添加到 MainActivity）

```kotlin
import android.content.Intent
import android.net.Uri
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*

// 在 onCreate 末尾添加：
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // ... 你原有的初始化代码 ...

    // 处理启动时的 URL Scheme（冷启动）
    handleRealBoardIntent(intent)
}

// 处理 App 已在运行时的 URL Scheme（热启动）
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleRealBoardIntent(intent)
}

// 核心：解析 URL、解码图片、交给业务层处理
private fun handleRealBoardIntent(intent: Intent?) {
    intent ?: return
    val uri = intent.data ?: return
    if (uri.scheme != "realboard" || uri.host != "push") return

    android.util.Log.d("RealBoard", "📥 Handling realboard://push")

    val base64Image = uri.getQueryParameter("image")
    val callbackScheme = uri.getQueryParameter("callback") ?: ""
    val fileName = uri.getQueryParameter("filename") ?: "push_image.jpg"

    if (base64Image.isNullOrEmpty()) {
        android.util.Log.e("RealBoard", "❌ Missing image data")
        sendRealBoardCallback(callbackScheme, "error", "Missing image data")
        return
    }

    CoroutineScope(Dispatchers.IO).launch {
        try {
            // Step 1: URL-safe Base64 → 标准 Base64 + 补回 padding
            var fixed = base64Image.replace("-", "+").replace("_", "/")
            val rem = fixed.length % 4
            if (rem != 0) fixed += "=".repeat(4 - rem)
            val rawBytes = Base64.decode(fixed, Base64.DEFAULT)

            // Step 2: 解码为 Bitmap（兼容 PNG / JPG 输入）
            val bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
                ?: throw Exception("Cannot decode image")

            // Step 3: 转换为 JPEG 并写入临时文件
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos)
            val jpegBytes = baos.toByteArray()
            val tmpFile = File(cacheDir, "realboard_push_${UUID.randomUUID()}.jpg")
            tmpFile.writeBytes(jpegBytes)

            android.util.Log.d("RealBoard", "✅ Saved temp: ${tmpFile.absolutePath}")

            // Step 4: 切回主线程，交给业务层
            withContext(Dispatchers.Main) {
                onRealBoardImageReceived(tmpFile.absolutePath, callbackScheme)
            }

        } catch (e: Exception) {
            android.util.Log.e("RealBoard", "❌ Error: ${e.message}")
            withContext(Dispatchers.Main) {
                sendRealBoardCallback(callbackScheme, "error", e.message ?: "Unknown")
            }
        }
    }
}

/**
 * ★ 业务入口：图片已解码并写入 tmpPath，在这里加载到画布并发送。
 *
 * 你需要根据你的项目结构实现此方法的业务逻辑：
 * 1. 加载 tmpPath 的图片到画布背景
 * 2. 如果 e-ink 客户端已连接，调用你的发送方法
 * 3. 处理完后调用 sendRealBoardCallback(callback, "ok", null)
 * 4. 删除临时文件 File(tmpPath).delete()
 */
private fun onRealBoardImageReceived(tmpPath: String, callback: String) {
    android.util.Log.d("RealBoard", "🖼️ Image ready at: $tmpPath")

    // ── 在这里填入你的业务代码 ──────────────────────────────────
    // 示例伪代码（根据你的实际 API 替换）：
    //
    // val bitmap = BitmapFactory.decodeFile(tmpPath)
    // drawingView.setBackgroundBitmap(bitmap)
    //
    // if (eInkClient.isConnected()) {
    //     val canvas1650x2200 = drawingView.getRenderedBitmap(1650, 2200)
    //     eInkClient.sendImage(canvas1650x2200) {
    //         sendRealBoardCallback(callback, "ok", null)
    //     }
    // } else {
    //     showToast("Image loaded (no display connected)")
    //     sendRealBoardCallback(callback, "ok", null)
    // }
    //
    // File(tmpPath).delete()
    // ────────────────────────────────────────────────────────────
}

// 回调 mr.smith
fun sendRealBoardCallback(scheme: String, status: String, message: String? = null) {
    if (scheme.isEmpty()) return
    try {
        var urlStr = "$scheme://eink-result?status=$status"
        if (message != null) urlStr += "&message=${Uri.encode(message)}"
        val callbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(urlStr)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(callbackIntent)
        android.util.Log.d("RealBoard", "↩️ Callback: $urlStr")
    } catch (e: Exception) {
        android.util.Log.e("RealBoard", "❌ Callback failed: ${e.message}")
    }
}
```

---

## 五、BridgeService.kt — HTTP 轮询服务（完整新建文件）

新建文件 `BridgeService.kt`，**完整复制**以下代码：

```kotlin
package com.yourcompany.yourapp  // ← 替换为你的包名

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Bridge 轮询服务
 * 每 3 秒请求 endpoint，当 JSON 响应中的 timeUpdated 字段变化时触发 onDataUpdated 回调。
 *
 * 响应 JSON 格式（服务器需返回）：
 * { "value": "任意字符串", "timeUpdated": "任意时间戳字符串" }
 */
class BridgeService {

    private var endpointUrl: String? = null
    private var lastTimeUpdated: String? = null
    private var currentValue: String? = null
    private var isPolling = false

    private var pollingJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // 轮询间隔（毫秒）
    private val pollingIntervalMs = 3000L

    // ── 回调（在主线程回调）──────────────────────────────────────

    /** 当数据更新时回调：(value, timeUpdated) */
    var onDataUpdated: ((value: String, timeUpdated: String) -> Unit)? = null

    /** 连接状态变化时回调：(isConnected) */
    var onConnectionStatusChanged: ((isConnected: Boolean) -> Unit)? = null

    // ── 公开方法 ────────────────────────────────────────────────

    fun setEndpoint(url: String) {
        endpointUrl = url
    }

    fun getEndpoint(): String? = endpointUrl

    fun getCurrentValue(): String? = currentValue

    fun isRunning(): Boolean = isPolling

    fun startPolling() {
        if (endpointUrl.isNullOrEmpty()) {
            android.util.Log.w("BridgeService", "No endpoint set")
            return
        }
        if (isPolling) {
            android.util.Log.d("BridgeService", "Already polling")
            return
        }
        isPolling = true
        android.util.Log.d("BridgeService", "▶ Start polling: $endpointUrl")

        pollingJob = CoroutineScope(Dispatchers.IO).launch {
            // 立即执行一次
            pollOnce()
            while (isPolling) {
                delay(pollingIntervalMs)
                if (isPolling) pollOnce()
            }
        }
    }

    fun stopPolling() {
        isPolling = false
        pollingJob?.cancel()
        pollingJob = null
        android.util.Log.d("BridgeService", "⏹ Polling stopped")
        mainHandler.post { onConnectionStatusChanged?.invoke(false) }
    }

    fun dispose() {
        stopPolling()
        endpointUrl = null
        lastTimeUpdated = null
        currentValue = null
        onDataUpdated = null
        onConnectionStatusChanged = null
    }

    // ── 内部轮询逻辑 ─────────────────────────────────────────────

    private suspend fun pollOnce() {
        val url = endpointUrl ?: return
        try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                connectTimeout = 5000
                readTimeout = 5000
            }

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val body = connection.inputStream.bufferedReader().readText()
                connection.disconnect()

                val json = JSONObject(body)
                if (json.has("value") && json.has("timeUpdated")) {
                    val value = json.getString("value")
                    val timeUpdated = json.getString("timeUpdated")

                    if (lastTimeUpdated != timeUpdated) {
                        android.util.Log.d("BridgeService", "🆕 New data: $value @ $timeUpdated")
                        lastTimeUpdated = timeUpdated
                        currentValue = value
                        mainHandler.post {
                            onDataUpdated?.invoke(value, timeUpdated)
                            onConnectionStatusChanged?.invoke(true)
                        }
                    }
                } else {
                    android.util.Log.w("BridgeService", "⚠ Response missing 'value'/'timeUpdated'")
                }
            } else {
                connection.disconnect()
                android.util.Log.w("BridgeService", "⚠ HTTP $responseCode")
                mainHandler.post { onConnectionStatusChanged?.invoke(false) }
            }
        } catch (e: Exception) {
            android.util.Log.e("BridgeService", "❌ Poll error: ${e.message}")
            mainHandler.post { onConnectionStatusChanged?.invoke(false) }
        }
    }
}
```

---

## 六、StorageHelper.kt — 持久化配置（完整新建文件）

新建文件 `StorageHelper.kt`，保存 Bridge 的 endpoint 和开关状态：

```kotlin
package com.yourcompany.yourapp  // ← 替换为你的包名

import android.content.Context
import android.content.SharedPreferences

object StorageHelper {

    private const val PREFS_NAME = "RealBoardSettings"
    private const val KEY_BRIDGE_ENDPOINT = "bridge_endpoint"
    private const val KEY_BRIDGE_ENABLED  = "bridge_enabled"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getBridgeEndpoint(context: Context): String? =
        prefs(context).getString(KEY_BRIDGE_ENDPOINT, null)

    fun setBridgeEndpoint(context: Context, url: String) =
        prefs(context).edit().putString(KEY_BRIDGE_ENDPOINT, url).apply()

    fun clearBridgeEndpoint(context: Context) =
        prefs(context).edit().remove(KEY_BRIDGE_ENDPOINT).apply()

    fun isBridgeEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BRIDGE_ENABLED, false)

    fun setBridgeEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_BRIDGE_ENABLED, enabled).apply()
}
```

---

## 七、主屏幕 Activity — Bridge UI 接入

在你的主屏幕 Activity 中添加以下代码：

### 7.1 声明变量

```kotlin
private val bridgeService = BridgeService()
private var isBridgeEnabled = false
private var isBridgeConnected = false
private var bridgeData: String? = null
```

### 7.2 在 onCreate 中初始化

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // ...
    initBridge()
}
```

### 7.3 initBridge 方法

```kotlin
private fun initBridge() {
    val endpoint = StorageHelper.getBridgeEndpoint(this)
    isBridgeEnabled = StorageHelper.isBridgeEnabled(this)

    if (!endpoint.isNullOrEmpty()) {
        bridgeService.setEndpoint(endpoint)
    }

    bridgeService.onDataUpdated = { value, _ ->
        bridgeData = value
        updateBridgeUI()
        // ★ 在这里加你的业务逻辑，例如把 value 写到画布上自动发送
        // onBridgeDataReceived(value)
    }

    bridgeService.onConnectionStatusChanged = { connected ->
        isBridgeConnected = connected
        updateBridgeUI()
    }

    if (isBridgeEnabled && !endpoint.isNullOrEmpty()) {
        bridgeService.startPolling()
    }

    updateBridgeUI()
}
```

### 7.4 开关切换方法

```kotlin
private fun toggleBridge(enable: Boolean) {
    if (enable) {
        val endpoint = StorageHelper.getBridgeEndpoint(this)
        if (endpoint.isNullOrEmpty()) {
            showBridgeConfigDialog()  // 还没配置，先弹配置框
            return
        }
        StorageHelper.setBridgeEnabled(this, true)
        isBridgeEnabled = true
        bridgeService.setEndpoint(endpoint)
        bridgeService.startPolling()
    } else {
        StorageHelper.setBridgeEnabled(this, false)
        bridgeService.stopPolling()
        isBridgeEnabled = false
        isBridgeConnected = false
        bridgeData = null
    }
    updateBridgeUI()
}
```

### 7.5 配置对话框

```kotlin
private fun showBridgeConfigDialog() {
    val currentEndpoint = StorageHelper.getBridgeEndpoint(this) ?: ""

    val input = android.widget.EditText(this).apply {
        setText(currentEndpoint)
        hint = "https://wkt.pw/b/..."
        setPadding(40, 20, 40, 20)
    }

    android.app.AlertDialog.Builder(this)
        .setTitle("Bridge Integration")
        .setMessage("Enter your Bridge endpoint URL:")
        .setView(input)
        .setPositiveButton("Save") { _, _ ->
            val url = input.text.toString().trim()
            StorageHelper.setBridgeEndpoint(this, url)
            if (url.isNotEmpty()) {
                bridgeService.setEndpoint(url)
                if (isBridgeEnabled) {
                    bridgeService.stopPolling()
                    bridgeService.startPolling()
                } else {
                    toggleBridge(true)
                }
            }
        }
        .setNeutralButton("Disable") { _, _ ->
            toggleBridge(false)
            StorageHelper.clearBridgeEndpoint(this)
        }
        .setNegativeButton("Cancel", null)
        .show()
}
```

### 7.6 UI 更新方法（根据你的布局自行适配）

```kotlin
private fun updateBridgeUI() {
    runOnUiThread {
        // 示例：假设有一个 Switch 叫 bridgeSwitch 和 TextView 叫 bridgeStatusText
        // bridgeSwitch.isChecked = isBridgeEnabled
        // bridgeStatusText.text = when {
        //     !isBridgeEnabled       -> "Off"
        //     bridgeData != null     -> bridgeData!!
        //     else                   -> "Waiting..."
        // }
        // bridgeIconView.setColorFilter(
        //     if (isBridgeEnabled && isBridgeConnected) Color.BLUE else Color.GRAY
        // )
    }
}
```

### 7.7 布局参考（XML）

在你的主屏幕布局中添加 Bridge 控件行：

```xml
<!-- Bridge 控件行 -->
<LinearLayout
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:gravity="center_vertical"
    android:padding="8dp">

    <ImageView
        android:id="@+id/bridgeIcon"
        android:layout_width="18dp"
        android:layout_height="18dp"
        android:src="@drawable/ic_link"
        android:tint="@color/white" />

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Bridge"
        android:textColor="#99FFFFFF"
        android:textSize="12sp"
        android:layout_marginStart="6dp" />

    <Switch
        android:id="@+id/bridgeSwitch"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginStart="4dp" />

    <TextView
        android:id="@+id/bridgeStatusText"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:textColor="#AAFFFFFF"
        android:textSize="13sp"
        android:text="Off"
        android:layout_marginStart="4dp"
        android:ellipsize="end"
        android:singleLine="true" />

    <ImageButton
        android:id="@+id/bridgeSettingsBtn"
        android:layout_width="32dp"
        android:layout_height="32dp"
        android:src="@drawable/ic_settings"
        android:background="?attr/selectableItemBackgroundBorderless"
        android:tint="#66FFFFFF" />
</LinearLayout>
```

### 7.8 在 onCreate 中绑定控件事件

```kotlin
// 开关切换
bridgeSwitch.setOnCheckedChangeListener { _, isChecked ->
    toggleBridge(isChecked)
}

// 设置按钮
bridgeSettingsBtn.setOnClickListener {
    showBridgeConfigDialog()
}
```

---

## 八、生命周期管理

```kotlin
override fun onDestroy() {
    super.onDestroy()
    bridgeService.dispose()
}

// 如果 Activity 可能被重建（横竖屏等），在 onStop 停止轮询，onStart 恢复：
override fun onStop() {
    super.onStop()
    if (isBridgeEnabled) bridgeService.stopPolling()
}

override fun onStart() {
    super.onStart()
    val endpoint = StorageHelper.getBridgeEndpoint(this)
    if (isBridgeEnabled && !endpoint.isNullOrEmpty()) {
        bridgeService.setEndpoint(endpoint!!)
        bridgeService.startPolling()
    }
}
```

---

## 九、权限

在 `AndroidManifest.xml` 中确保有网络权限：

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

---

## 十、Bridge 数据格式（服务器 API 规范）

BridgeService 期望服务器返回以下 JSON：

```json
{
  "value": "你要显示的内容（任意字符串）",
  "timeUpdated": "2026-04-06T12:00:00Z"
}
```

| 字段 | 说明 |
|------|------|
| `value` | 要显示/处理的数据值 |
| `timeUpdated` | 上次更新时间戳（只要此字段变化，就触发 `onDataUpdated`） |

**只有 `timeUpdated` 变化时才触发回调**，即服务器数据没更新时不会重复通知 UI，避免频繁刷新。

---

## 十一、测试清单

- [ ] 安装 App，在 ADB shell 中执行 `am start -W -a android.intent.action.VIEW -d "realboard://push?image=XXX&callback=mrsmith" com.yourpackage`
- [ ] Logcat 过滤 `RealBoard` tag，确认 `📥 Handling realboard://push` 出现
- [ ] 确认 `✅ Saved temp` 出现，说明图片解码成功
- [ ] 确认 `onRealBoardImageReceived` 被调用，画布更新
- [ ] Bridge：输入有效 endpoint → 开启 Switch → Logcat 看到 `▶ Start polling`
- [ ] Bridge：数据变化时 `onDataUpdated` 回调触发
- [ ] 关闭 Switch → `⏹ Polling stopped`

---

## 十二、关键实现注意事项

1. **Base64 padding 必须补回**：URL 传输中的 `=` 被去掉，解码前必须补 `=` 至 4 的倍数，否则 `Base64.decode` 返回错误结果
2. **`onRealBoardImageReceived` 需你自己实现业务逻辑**：把图片加载到画布、发送到墨水屏的代码由你根据项目结构填写
3. **BridgeService 所有回调在主线程**：`onDataUpdated` 和 `onConnectionStatusChanged` 已通过 `Handler(Looper.getMainLooper())` 切回主线程，可以直接操作 UI
4. **BridgeService 的 `dispose()` 必须调用**：在 Activity `onDestroy` 中调用，避免内存泄漏和后台网络请求
5. **轮询间隔**：默认 3 秒，修改 `pollingIntervalMs` 常量即可

---

*RealBoard by yaomagic | Android Bridge Integration Guide 2026-04-06*
