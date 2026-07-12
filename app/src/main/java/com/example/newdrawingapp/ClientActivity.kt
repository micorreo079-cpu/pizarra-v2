package com.example.newdrawingapp

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.newdrawingapp.network.DrawingSocketManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.util.Log
import android.Manifest
import android.widget.Button
import android.graphics.Canvas
import android.graphics.Color
import android.animation.Animator
import android.animation.AnimatorInflater
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.view.InputDevice
import android.view.MotionEvent
import android.widget.RelativeLayout
import java.util.ArrayDeque
import android.widget.Toast
import android.view.ViewGroup
import android.util.DisplayMetrics
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.view.Gravity
import com.example.newdrawingapp.network.NetworkUtils
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import android.os.SystemClock
import android.os.PowerManager
import android.os.Build
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class ClientActivity : AppCompatActivity() {
    companion object {
        // 书写模式默认值：true=交换模式（第5张时交换），false=正常模式（书写时正常出现）
        private const val DEFAULT_SWAP_MODE = false
        
        private const val PREFS_NAME = "ClientConnectionPrefs"
        private const val KEY_SWAP_MODE = "swap_mode"
        private const val KEY_LAST_CONNECTED_IP = "last_connected_ip"
        private const val KEY_LAST_CONNECTED_PORT = "last_connected_port"
        private const val KEY_LAST_BRUSH_TYPE = "last_brush_type"
        private const val KEY_LAST_BACKGROUND_COLOR = "last_background_color"
        /** Last RFCOMM peer (RealBoard); used to try this MAC first on auto-reconnect */
        private const val KEY_LAST_BT_MAC = "last_connected_bt_mac"
        private const val SAVE_DIRECTORY = "saved_screenshots"

        private const val REQUEST_BT_CONNECT = 9301
    }

    /** Android 12+ needs BLUETOOTH_CONNECT before reading bondedDevices / RFCOMM. */
    private fun hasBtConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** Request BLE connect perm if missing; otherwise start prefs + pairing-first auto-connect. */
    private fun maybeStartBluetoothAutoConnectWithPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasBtConnectPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                REQUEST_BT_CONNECT
            )
            return
        }
        startBluetoothAutoConnectConfigured()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_BT_CONNECT) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startBluetoothAutoConnectConfigured()
        } else {
            Log.w("ClientActivity", "BLUETOOTH_CONNECT denied — BT auto-connect may not access paired list")
            startBluetoothAutoConnectConfigured()
        }
    }

    private val drawingSocketManager = DrawingSocketManager(this)
    private val bluetoothManager = com.example.newdrawingapp.network.BluetoothManager(this)
    private lateinit var imageView: ImageView
    private lateinit var statusIndicator: TextView
    // Modo V2 (servidor): overlay con IP:puerto + QR para conectar fácil.
    private var isServerMode = false
    private var serverInfoView: View? = null
    // Evita añadir la zona del QR dos veces (onCreate + onNewIntent).
    private var v2TapZoneAdded = false
    // Pulsación larga (4 s) en la zona superior izquierda → mostrar el QR (V2).
    private var v2HoldRunnable: Runnable? = null
    // En V2 la zona también replica la parte superior del combo de cierre
    // (2 s arriba + abajo-izquierda pulsado), que quedaba tapada por la zona.
    private var v2CloseComboRunnable: Runnable? = null
    // Renovación periódica del WakeLock (el acquire con timeout vence a las 10h).
    private var wakeLockRenewRunnable: Runnable? = null
    private lateinit var btnCombined: Button
    private lateinit var btnClear: Button
    private var isWhiteCanvas = false
    
    // WakeLock 用于强制保持设备唤醒（防止墨水屏休眠）
    private var wakeLock: PowerManager.WakeLock? = null
    private val blinkAnimation by lazy {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            repeatCount = 3
            repeatMode = ValueAnimator.REVERSE
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (drawingSocketManager.connectionState.value is DrawingSocketManager.ConnectionState.Connected) {
                        statusIndicator.visibility = View.GONE
                    }
                }
            })
        }
    }
    
    // 组合按钮相关变量
    private var combinedClickCount = 0
    private var lastCombinedClickTime = 0L
    private val TRIPLE_CLICK_TIMEOUT = 1500L // 1.5秒内三击重连
    
    // 重置点击计数的Runnable
    private val resetCombinedClickRunnable = Runnable {
        combinedClickCount = 0
    }
    
    // 清除相关变量
    private var clearClickCount = 0
    private var lastClearClickTime = 0L
    private val CLEAR_TIMEOUT = 1000L
    
    // 组合点击相关变量（保留用于清除按钮）
    private var isClearClicked = false
    private val COMBINATION_TIMEOUT = 1000L
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    
    // 新增：长按关闭APP相关变量
    private var isLeftBottomLongPressed = false
    private var leftBottomLongPressStartTime = 0L
    private val LEFT_BOTTOM_LONG_PRESS_DURATION = 1000L // 左下角按钮需要长按1秒
    private var leftBottomLongPressRunnable: Runnable? = null
    
    private var isLeftTopLongPressed = false
    private var leftTopLongPressStartTime = 0L
    private val LEFT_TOP_LONG_PRESS_DURATION = 2000L // 左上角按钮需要长按2秒
    private var leftTopLongPressRunnable: Runnable? = null
    
    private var leftBottomLongPressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var leftTopLongPressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    
    // 长按关闭APP逻辑说明：
    // 1. 先长按左下角按钮1秒以上
    // 2. 然后长按左上角按钮2秒以上
    // 3. 两个条件都满足时，APP才会关闭
    // 这样可以有效防止误触关闭APP
    
    // 添加新的成员变量
    private var currentBitmap: Bitmap? = null
    private val eraserRadius = 30f  // 橡皮擦半径
    private var isErasing = false
    private var lastX: Float = 0f
    private var lastY: Float = 0f
    
    // 优化涂抹性能：复用Paint和Canvas对象
    private var eraserPaint: Paint? = null
    private var eraseCanvas: Canvas? = null
    private var currentEraseBitmap: Bitmap? = null  // 跟踪当前Canvas对应的bitmap
    private var writingDisplayBitmap: Bitmap? = null
    private var writingDisplayCanvas: Canvas? = null
    private var pendingMainInvalidate = false
    private var pendingWritingInvalidate = false
    private val invalidateHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // Sony DHW 硬件加速（DPT-RP1 专有，其他设备静默降级）
    private var sonyDhw: SonySystemUtil? = null

    // 主画布马克笔书写（电磁笔）
    private var drawingOverlay: DrawingOverlaySurfaceView? = null   // SurfaceView 实时笔画层（保留备用）
    private var markerCanvas: Canvas? = null
    private var currentMarkerBitmap: Bitmap? = null
    private var isDrawingWithStylus = false
    private var lastStylusX = 0f
    private var lastStylusY = 0f
    private val markerStrokeWidth = 17f
    // 本笔画包围盒（view 坐标），用于 ACTION_UP 时局部刷新 imageView
    private var strokeBoundsLeft = 0f
    private var strokeBoundsTop = 0f
    private var strokeBoundsRight = 0f
    private var strokeBoundsBottom = 0f

    /** 上一采样点 eventTime（与 getHistoricalEventTime 同源），用于快写时「隔时拆笔」 */
    private var lastStylusSampleEventTime = 0L

    /** StylusHover：HOVER_MOVE 节流；触摸管线低压力节流 */
    private var lastStylusHoverMoveLogMs = 0L
    private var lastTouchStylusLowPressureLogMs = 0L

    // Sony 专有快速刷新 API：View.invalidate(Rect, int)
    // mode=1 强制 A2/DU 快速模式（~20ms），避免 GC16 慢刷（~450ms）阻塞下一笔的 DHW squiggle
    private var sonyFastInvalidateMethod: java.lang.reflect.Method? = null
    private val sonyFastInvalidateRect = android.graphics.Rect()

    private fun initSonyFastInvalidate() {
        try {
            sonyFastInvalidateMethod = android.view.View::class.java
                .getMethod("invalidate", android.graphics.Rect::class.java, Int::class.java)
            Log.i("ClientActivity", "Sony 快速刷新 API 可用")
        } catch (e: NoSuchMethodException) {
            Log.i("ClientActivity", "Sony 快速刷新 API 不可用，使用标准 invalidate")
        }
    }

    /** 墨水屏过小局部刷新易「首笔不显」：矩形边长至少扩到此值（像素）再 invalidate */
    private val epdcInvalidateMinSidePx = 48

    /**
     * 用 A2 快速模式刷新矩形区域。
     * 若 Sony API 不可用则回退到标准 invalidate。
     * mode 候选值：1=DU, 2=GC16, 4=GL16, 6=A2（按设备实际情况选最快的）
     */
    private fun invalidateFast(l: Int, t: Int, r: Int, b: Int) {
        val iw = imageView.width
        val ih = imageView.height
        if (iw <= 0 || ih <= 0) {
            imageView.invalidate()
            return
        }
        var ll = l
        var tt = t
        var rr = r
        var bb = b
        val minSide = epdcInvalidateMinSidePx.coerceAtMost(maxOf(iw, ih))
        var w = rr - ll
        var h = bb - tt
        if (w < minSide) {
            val mid = (ll + rr) / 2
            val half = minSide / 2
            ll = mid - half
            rr = mid + half
        }
        if (h < minSide) {
            val mid = (tt + bb) / 2
            val half = minSide / 2
            tt = mid - half
            bb = mid + half
        }
        ll = ll.coerceIn(0, iw - 1)
        tt = tt.coerceIn(0, ih - 1)
        rr = rr.coerceIn(ll + 1, iw)
        bb = bb.coerceIn(tt + 1, ih)
        val method = sonyFastInvalidateMethod
        if (method != null) {
            sonyFastInvalidateRect.set(ll, tt, rr, bb)
            try {
                method.invoke(imageView, sonyFastInvalidateRect, 1)
                return
            } catch (_: Exception) {}
        }
        imageView.invalidate(ll, tt, rr, bb)
    }
    // 预分配 Paint：绝对最简线条，无笔刷效果，无抗锯齿
    private val bitmapMarkerPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND  // ROUND = 圆头，相邻段首尾重叠，无方块感
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = false          // 纯黑/白像素，触发 EPDC A2 刷新
        isFilterBitmap = false
        isDither = false
    }
    // 预缓存坐标变换参数，避免每段都重新计算（每段节省 7+ 次浮点运算 + 2 次 Triple 分配）
    private var bitmapScale = 1f
    private var bitmapOffsetX = 0f
    private var bitmapOffsetY = 0f
    
    // 添加缩放边距控制
    private var marginPercent = 0.02f  // 改为2%的边距，使图片更大
    
    // 添加缓存变量，避免重复创建
    private val matrix = Matrix()
    private var lastScale = 1f
    private var lastLeft = 0f
    private var lastTop = 0f
    private var lastWidth = 0
    private var lastHeight = 0
    
    // 修改标记变量的声明
    private var touchListenerSet = false  // 改为普通的布尔变量
    /** 避免 imageView 首次 measured 宽高为 0 时只跑一次 post，导致 markerCanvas 长期为 null */
    private var imageViewMarkerLayoutHooked = false

    /** DHW 注册失败后的延迟重试次数（避免无限 postDelayed） */
    private var dhwAreaRegisterRetryCount = 0

    private val dhwApplyRunnable = Runnable { applyDhwAreaFromImageViewWork() }

    /** 电磁笔诊断：每笔递增，用于 logcat 过滤 tag StylusDiag */
    private var stylusDiagStrokeId = 0
    private var stylusDiagLastNullCanvasStroke = -1
    private var stylusDiagFirstMoveLoggedStroke = -1

    /** 抬笔后若超过此时长再落笔，认为 EPDC/DHW 可能休眠，首帧需唤醒（毫秒） */
    private val stylusIdleWakeAfterMs = 3000L
    /** 上次电磁笔抬笔时间；0=尚未记录 */
    private var lastStylusPointerUpAt = 0L

    /** 最近一次笔活动后，在此之前视为「书写会话」：跳过闲置唤醒，并由 keepalive 维持 DHW/EPDC */
    private val stylusSessionDurationMs = 3600_000L
    private val stylusKeepaliveIntervalMs = 60_000L
    private var stylusSessionUntil = 0L
    private var lastStylusSessionBumpAt = 0L
    private var stylusKeepaliveRunnable: Runnable? = null

    private lateinit var rootLayout: RelativeLayout
    
    // 添加位图缓存管理
    private var bitmapCache: Bitmap? = null
    private val maxCacheSize = 3  // 最大缓存数量
    private val bitmapQueue = ArrayDeque<Bitmap>(maxCacheSize)
    
    // 隐藏功能相关变量
    private lateinit var btnHiddenActivate: Button
    private lateinit var hiddenDrawingView: HiddenDrawingView
    private lateinit var writingDisplayContainer: RelativeLayout
    private lateinit var writingDisplayView: ImageView
    
    // 隐藏按钮点击计数
    private var hiddenButtonClickCount = 0
    private var lastHiddenClickTime = 0L
    private val HIDDEN_CLICK_TIMEOUT = 2000L // 2秒内需要完成开启点击
    private val DOUBLE_CLICK_TIMEOUT = 300L // 0.3秒内的双击
    private var undoDelayedRunnable: Runnable? = null
    
    // 功能状态
    private var isHiddenFeatureEnabled = false
    
    // 书写内容堆叠管理
    private val writingContentList = mutableListOf<Bitmap>()
    private val maxWritingCount = 5 // 最多保存5次书写内容
    
    // 笔刷类型管理
    enum class BrushType {
        NORMAL,    // 普通笔刷
        CHALK      // 粉笔笔刷
    }
    private var currentBrushType = BrushType.NORMAL
    
    // 保存和加载功能相关变量
    private lateinit var btnSave1: ImageView
    private lateinit var btnSave2: ImageView
    private lateinit var btnSave3: ImageView
    private lateinit var btnBrushNormal: ImageView
    private lateinit var btnBrushChalk: ImageView
    private lateinit var brushButtonsContainer: LinearLayout
    private lateinit var saveButtonsContainer: LinearLayout
    /** 书写完成伪触摸专用：右下角小区域，消费事件，不落到主画布/按钮 */
    private lateinit var fakeTouchSink: View
    
    // 剪贴板临时文件引用
    private var lastClipboardTempFile: File? = null
    
    // 保存的截图文件路径
    private val savedScreenshots = mutableMapOf<Int, String>()
    
    // 长按保存相关变量
    private var longPressStartTime = 0L
    private val LONG_PRESS_DURATION = 3000L // 3秒长按保存
    private var longPressRunnable: Runnable? = null
    private var longPressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var currentLongPressButtonId = -1
    
    // 第一个点和第二个点同时按住3秒切换 Swap mode
    private var isSave1Pressed = false
    private var isSave2Pressed = false
    private var swapModeComboRunnable: Runnable? = null
    private val swapModeComboHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val SWAP_MODE_COMBO_DURATION = 3000L
    private var swapModeComboJustFired = false
    
    // 组合关闭窗口：左下角单击后，1.2秒内点击右下角则关闭
    private val DEACTIVATE_COMBO_TIMEOUT = 1200L
    private var lastLeftBottomSingleTapAt = 0L
    
    // 书写完成后自动刷新
    private var lastUserInteractionAt = System.currentTimeMillis()
    private val POST_WRITING_FAKE_TOUCH_DELAY = 4000L
    private var pendingFakeTouchRunnable: Runnable? = null
    
    // 拦截底部上滑手势相关变量
    private var gestureStartY = 0f
    private var gestureStartX = 0f
    private var isGestureStarted = false
    private var isBottomTouchIntercepted = false
    private val BOTTOM_GESTURE_THRESHOLD = 150f // 底部区域阈值（像素），增加到150像素
    private val MIN_SWIPE_DISTANCE = 30f // 最小滑动距离，降低到30像素以便更早拦截
    
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Comprobación OTA como MUCHO una vez al mes (silenciosa). Usa la
        // licencia que el móvil guardó en la pizarra ("received_license_name").
        maybeCheckOta()

        // 保持屏幕常亮，防止设备进入休眠模式
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // 获取 WakeLock 强制保持设备唤醒（针对墨水屏设备）
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            // 使用 FULL_WAKE_LOCK 保持CPU和屏幕都唤醒（适用于墨水屏设备）
            // 对于Android 5.1，FULL_WAKE_LOCK仍然可用
            @Suppress("DEPRECATION")
            wakeLock = powerManager.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "ClientActivity::WakeLock"
            )
            wakeLock?.setReferenceCounted(false) // 不使用引用计数，确保不会被意外释放
            wakeLock?.acquire(10 * 60 * 60 * 1000L) // 10小时超时（实际不会超时）
            if (wakeLock?.isHeld == true) {
                Log.d("ClientActivity", "WakeLock已成功获取（FULL_WAKE_LOCK），设备将保持唤醒状态")
            } else {
                Log.w("ClientActivity", "WakeLock获取失败，尝试PARTIAL_WAKE_LOCK作为后备")
                // 如果FULL_WAKE_LOCK失败，尝试PARTIAL_WAKE_LOCK作为后备
                try {
                    wakeLock?.release()
                    wakeLock = powerManager.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "ClientActivity::WakeLock::Fallback"
                    )
                    wakeLock?.setReferenceCounted(false)
                    wakeLock?.acquire(10 * 60 * 60 * 1000L)
                    if (wakeLock?.isHeld == true) {
                        Log.d("ClientActivity", "使用PARTIAL_WAKE_LOCK作为后备方案")
                    }
                } catch (e2: Exception) {
                    Log.e("ClientActivity", "后备WakeLock也失败: ${e2.message}", e2)
                }
            }
        } catch (e: Exception) {
            Log.e("ClientActivity", "获取WakeLock失败: ${e.message}", e)
            e.printStackTrace()
        }
        // El acquire con timeout de 10h vence si la app no vuelve a pasar por
        // onResume: renovarlo periódicamente cada 4h.
        scheduleWakeLockRenewal()

        // 设置窗口全屏和透明 - 安卓5.1兼容性改进
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
                window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            } else {
                // 安卓5.1以下版本的兼容处理
                window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)
            }
        } catch (e: Exception) {
            Log.w("ClientActivity", "设置系统UI可见性失败: ${e.message}")
            // 降级处理：只设置全屏
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
        
        // 设置Window.Callback来拦截系统手势（在更底层拦截）
        val originalCallback = window.callback
        window.callback = object : android.view.Window.Callback {
            override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
                // 在Window层面拦截底部上滑手势
                val screenHeight = resources.displayMetrics.heightPixels
                val bottomThreshold = screenHeight - BOTTOM_GESTURE_THRESHOLD
                
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        if (event.y >= bottomThreshold) {
                            gestureStartY = event.y
                            gestureStartX = event.x
                            isGestureStarted = true
                            isBottomTouchIntercepted = false
                            Log.d("ClientActivity", "[Window]检测到底部触摸: y=${event.y}")
                        } else {
                            isGestureStarted = false
                        }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (isGestureStarted) {
                            val deltaY = gestureStartY - event.y
                            val deltaX = Math.abs(event.x - gestureStartX)
                            if (deltaY > 0 && (deltaY > MIN_SWIPE_DISTANCE || (deltaY > 10 && deltaY > deltaX * 0.5))) {
                                if (!isBottomTouchIntercepted) {
                                    Log.d("ClientActivity", "[Window]拦截底部上滑手势: deltaY=$deltaY")
                                    isBottomTouchIntercepted = true
                                }
                                return true // 在Window层面拦截
                            } else {
                                // 不是有效的上滑手势
                            }
                        } else {
                            // 手势未开始
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (isBottomTouchIntercepted) {
                            Log.d("ClientActivity", "[Window]消费底部手势的UP事件")
                            isGestureStarted = false
                            isBottomTouchIntercepted = false
                            return true
                        } else {
                            if (isGestureStarted) {
                                val deltaY = gestureStartY - event.y
                                val deltaX = Math.abs(event.x - gestureStartX)
                                if (deltaY > MIN_SWIPE_DISTANCE && deltaY > deltaX) {
                                    Log.d("ClientActivity", "[Window]拦截底部快速上滑（UP）: deltaY=$deltaY")
                                    isGestureStarted = false
                                    return true
                                } else {
                                    // 不是有效的上滑手势
                                }
                            } else {
                                // 手势未开始
                            }
                        }
                        isGestureStarted = false
                        isBottomTouchIntercepted = false
                    }
                }
                
                // 调用原始callback处理其他事件
                return originalCallback?.dispatchTouchEvent(event) ?: false
            }
            
            // 委托其他方法给原始callback
            override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean = 
                originalCallback?.dispatchKeyEvent(event) ?: false
            override fun dispatchKeyShortcutEvent(event: android.view.KeyEvent): Boolean = 
                originalCallback?.dispatchKeyShortcutEvent(event) ?: false
            override fun dispatchPopulateAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent): Boolean = 
                originalCallback?.dispatchPopulateAccessibilityEvent(event) ?: false
            override fun onCreatePanelView(featureId: Int): android.view.View? = 
                originalCallback?.onCreatePanelView(featureId)
            override fun onCreatePanelMenu(featureId: Int, menu: android.view.Menu): Boolean = 
                originalCallback?.onCreatePanelMenu(featureId, menu) ?: false
            override fun onPreparePanel(featureId: Int, view: android.view.View?, menu: android.view.Menu): Boolean = 
                originalCallback?.onPreparePanel(featureId, view, menu) ?: false
            override fun onMenuOpened(featureId: Int, menu: android.view.Menu): Boolean = 
                originalCallback?.onMenuOpened(featureId, menu) ?: false
            override fun onMenuItemSelected(featureId: Int, item: android.view.MenuItem): Boolean = 
                originalCallback?.onMenuItemSelected(featureId, item) ?: false
            override fun onWindowAttributesChanged(attrs: android.view.WindowManager.LayoutParams) {
                originalCallback?.onWindowAttributesChanged(attrs)
            }
            override fun onContentChanged() {
                originalCallback?.onContentChanged()
            }
            override fun onWindowFocusChanged(hasFocus: Boolean) {
                originalCallback?.onWindowFocusChanged(hasFocus)
            }
            override fun onAttachedToWindow() {
                originalCallback?.onAttachedToWindow()
            }
            override fun onDetachedFromWindow() {
                originalCallback?.onDetachedFromWindow()
            }
            override fun onPanelClosed(featureId: Int, menu: android.view.Menu) {
                originalCallback?.onPanelClosed(featureId, menu)
            }
            override fun onSearchRequested(): Boolean = 
                originalCallback?.onSearchRequested() ?: false
            override fun onSearchRequested(searchEvent: android.view.SearchEvent): Boolean = 
                originalCallback?.onSearchRequested(searchEvent) ?: false
            override fun onWindowStartingActionMode(callback: android.view.ActionMode.Callback): android.view.ActionMode? = 
                originalCallback?.onWindowStartingActionMode(callback)
            override fun onWindowStartingActionMode(callback: android.view.ActionMode.Callback, type: Int): android.view.ActionMode? = 
                originalCallback?.onWindowStartingActionMode(callback, type)
            override fun onActionModeStarted(mode: android.view.ActionMode) {
                originalCallback?.onActionModeStarted(mode)
            }
            override fun onActionModeFinished(mode: android.view.ActionMode) {
                originalCallback?.onActionModeFinished(mode)
            }
            override fun dispatchTrackballEvent(event: android.view.MotionEvent): Boolean = 
                originalCallback?.dispatchTrackballEvent(event) ?: false
            override fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean = 
                originalCallback?.dispatchGenericMotionEvent(event) ?: false
        }
        
        setContentView(R.layout.activity_client)
        
        rootLayout = findViewById(R.id.rootLayout)
        imageView = findViewById(R.id.imageView)
        
        // 设置整个视图层次的背景为透明
        window.decorView.setBackgroundColor(Color.TRANSPARENT)
        rootLayout.setBackgroundColor(Color.TRANSPARENT)
        imageView.setBackgroundColor(Color.TRANSPARENT)
        
        statusIndicator = findViewById(R.id.statusIndicator)
        btnCombined = findViewById(R.id.btnCombined)
        btnClear = findViewById(R.id.btnClear)
        
        // 初始化隐藏功能相关的视图
        btnHiddenActivate = findViewById(R.id.btnHiddenActivate)
        hiddenDrawingView = findViewById(R.id.hiddenDrawingView)
        writingDisplayContainer = findViewById(R.id.writingDisplayContainer)
        writingDisplayView = findViewById(R.id.writingDisplayView)
        
        // 初始化保存按钮
        btnSave1 = findViewById(R.id.btnSave1)
        btnSave2 = findViewById(R.id.btnSave2)
        btnSave3 = findViewById(R.id.btnSave3)
        saveButtonsContainer = findViewById(R.id.saveButtonsContainer)
        btnBrushNormal = findViewById(R.id.btnBrushNormal)
        btnBrushChalk = findViewById(R.id.btnBrushChalk)
        brushButtonsContainer = findViewById(R.id.brushButtonsContainer)
        fakeTouchSink = findViewById(R.id.fakeTouchSink)
        fakeTouchSink.setOnTouchListener { _, _ -> true }
        
        setupHiddenFeature()
        setupCombinedButton()
        setupSaveButtons()
        setupBrushButtons()
        
        // 恢复上次的设置
        restoreLastSettings()
        
        // 设置组合按钮触摸事件（支持长按关闭APP和点击功能）
        btnCombined.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // 开始长按计时
                    leftTopLongPressStartTime = System.currentTimeMillis()
                    isLeftTopLongPressed = false
                    
                    // 取消之前的长按任务
                    leftTopLongPressRunnable?.let { leftTopLongPressHandler.removeCallbacks(it) }
                    
                    // 创建新的长按任务
                    leftTopLongPressRunnable = Runnable {
                        isLeftTopLongPressed = true
                        Log.d("ClientActivity", "左上角按钮长按2秒完成")
                        
                        // 检查左下角按钮是否已经长按完成
                        if (isLeftBottomLongPressed) {
                            Log.d("ClientActivity", "检测到左下角+左上角长按组合，关闭整个应用")
                            // 先断开连接
                            drawingSocketManager.disconnect()
                            // Dar tiempo al hilo de cierre a enviar CLIENT_DISCONNECT
                            // antes de matar el proceso (System.exit corta los hilos).
                            try { Thread.sleep(250) } catch (_: InterruptedException) {}
                            // 完全关闭APP
                            finishAffinity() // 关闭所有Activity
                            System.exit(0) // 终止进程
                        }
                    }
                    
                    // 2秒后执行长按任务
                    leftTopLongPressHandler.postDelayed(leftTopLongPressRunnable!!, LEFT_TOP_LONG_PRESS_DURATION)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // 取消长按任务
                    leftTopLongPressRunnable?.let { leftTopLongPressHandler.removeCallbacks(it) }
                    
                    // 如果不是长按，则执行点击功能
                    if (!isLeftTopLongPressed) {
                        val currentTime = System.currentTimeMillis()
                        
                        // 检查是否在1.5秒内
                        if (currentTime - lastCombinedClickTime > TRIPLE_CLICK_TIMEOUT) {
                            // 超时重置计数
                            combinedClickCount = 1
                        } else {
                            // 在时间窗口内，增加计数
                            combinedClickCount++
                        }
                        
                        lastCombinedClickTime = currentTime
                        
                        when (combinedClickCount) {
                            1, 2 -> {
                                // 笔刷改由左上角右侧两图标切换
                            }
                            3 -> {
                                performReconnect()
                                combinedClickCount = 0
                            }
                        }
                        
                        // 1.5秒后重置计数
                        handler.removeCallbacks(resetCombinedClickRunnable)
                        handler.postDelayed(resetCombinedClickRunnable, TRIPLE_CLICK_TIMEOUT)
                    }
                    
                    // 重置长按状态
                    isLeftTopLongPressed = false
                    true
                }
                else -> false
            }
        }
        
        // 修改清除按钮触摸事件（支持长按关闭APP和双击清除功能）
        btnClear.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // 开始长按计时
                    leftBottomLongPressStartTime = System.currentTimeMillis()
                    isLeftBottomLongPressed = false
                    
                    // 取消之前的长按任务
                    leftBottomLongPressRunnable?.let { leftBottomLongPressHandler.removeCallbacks(it) }
                    
                    // 创建新的长按任务
                    leftBottomLongPressRunnable = Runnable {
                        isLeftBottomLongPressed = true
                        Log.d("ClientActivity", "左下角按钮长按1秒完成")
                        
                        // 检查左上角按钮是否已经长按完成
                        if (isLeftTopLongPressed) {
                            Log.d("ClientActivity", "检测到左下角+左上角长按组合，关闭整个应用")
                            // 先断开连接
                            drawingSocketManager.disconnect()
                            // Dar tiempo al hilo de cierre a enviar CLIENT_DISCONNECT
                            try { Thread.sleep(250) } catch (_: InterruptedException) {}
                            // 完全关闭APP
                            finishAffinity() // 关闭所有Activity
                            System.exit(0) // 终止进程
                        }
                    }
                    
                    // 1秒后执行长按任务
                    leftBottomLongPressHandler.postDelayed(leftBottomLongPressRunnable!!, LEFT_BOTTOM_LONG_PRESS_DURATION)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // 取消长按任务
                    leftBottomLongPressRunnable?.let { leftBottomLongPressHandler.removeCallbacks(it) }
                    
                    // 如果不是长按，则执行点击功能
                    if (!isLeftBottomLongPressed) {
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastClearClickTime > CLEAR_TIMEOUT) {
                            clearClickCount = 1
                            // 记录一次"左下角单击"时间，用于后续与右下角组合关闭
                            lastLeftBottomSingleTapAt = currentTime
                            Log.d("ClientActivity", "记录左下角单击时间，用于组合关闭: $lastLeftBottomSingleTapAt")
                        } else {
                            clearClickCount++
                        }
                        lastClearClickTime = currentTime
                        
                        if (clearClickCount >= 2) {
                            clearClickCount = 0
                            drawingSocketManager.clearDrawing()
                            
                            // 清除所有图片缓存，防止内存泄漏
                            clearAllImageCache()
                            Log.d("ClientActivity", "双击清除按钮，已清理所有图片缓存")
                            // 双击清除发生时，取消组合关闭的单击标记，避免误触
                            lastLeftBottomSingleTapAt = 0L
                        }
                    }
                    
                    // 重置长按状态
                    isLeftBottomLongPressed = false
                    true
                }
                else -> false
            }
        }
        
        // 无论 WiFi 连接方式如何，始终在后台启动蓝牙搜索
        maybeStartBluetoothAutoConnectWithPermission()

        // 开始自动连接或使用手动IP
        if (intent.getBooleanExtra("SERVER_MODE", false)) {
            // Modo V2: la pizarra hace de SERVIDOR de red (escucha y acepta).
            isServerMode = true
            addV2TapZone() // zona oculta arriba-derecha: 3 toques → QR
            drawingSocketManager.startAsServer()
            bluetoothManager.startAsServer() // BT directo: el móvil conecta por la MAC del QR
            bluetoothManager.ensureSearching() // y además barrido auto (emparejados), como en V1
        } else {
            val manualIp = intent.getStringExtra("MANUAL_IP")
            if (manualIp != null) {
                // 使用手动输入的IP
                lifecycleScope.launch {
                    drawingSocketManager.connectToIp(manualIp)
                }
            } else {
                // 自动搜索并连接
                startAutoConnect()
            }
        }

        // 初始化擦除功能（确保在应用启动时就可用）
        initializeEraserFunction()
        
        // 监听连接状态
        lifecycleScope.launch {
            drawingSocketManager.connectionState.collect { state ->
                when (state) {
                    is DrawingSocketManager.ConnectionState.Connected -> {
                        statusIndicator.visibility = View.GONE
                        blinkAnimation.cancel()  // 取消任何正在进行的动画
                        if (isServerMode) hideServerInfo()

                        // 保存成功连接的地址
                        // Solo en modo cliente V1: en Modo V2 la "dirección" es
                        // la IP del MÓVIL entrante y envenenaba la reconexión
                        // rápida del modo clásico.
                        if (!isServerMode) {
                            drawingSocketManager.getLastConnectedAddress()?.let { address ->
                                saveLastConnectedAddress(address.first, address.second)
                            }
                        }

                    }
                    is DrawingSocketManager.ConnectionState.Disconnected -> {
                        statusIndicator.visibility = View.VISIBLE
                        statusIndicator.setTextColor(Color.GRAY)
                        statusIndicator.text = "×"
                    }
                    is DrawingSocketManager.ConnectionState.Searching -> {
                        statusIndicator.visibility = View.VISIBLE
                        statusIndicator.setTextColor(Color.GRAY)
                        statusIndicator.text = "×"
                    }
                    is DrawingSocketManager.ConnectionState.Connecting -> {
                        statusIndicator.visibility = View.VISIBLE
                        statusIndicator.setTextColor(Color.GRAY)
                        statusIndicator.text = "×"
                    }
                    is DrawingSocketManager.ConnectionState.Error -> {
                        statusIndicator.visibility = View.VISIBLE
                        statusIndicator.setTextColor(Color.GRAY)
                        statusIndicator.text = "×"
                    }
                }
            }
        }
        
        // 书写功能默认关闭，用户需双击右下角激活
    }
    
    private fun setupHiddenFeature() {
        // 设置隐藏按钮点击事件
        btnHiddenActivate.setOnClickListener {
            val currentTime = System.currentTimeMillis()
            
            // 优先检测组合关闭：左下角→右下角各一次
            val comboActive = isHiddenFeatureEnabled && lastLeftBottomSingleTapAt > 0 && (currentTime - lastLeftBottomSingleTapAt) <= DEACTIVATE_COMBO_TIMEOUT
            if (comboActive) {
                // 命中组合关闭，立即关闭并清除标记
                disableHiddenFeature()
                Log.d("ClientActivity", "组合关闭触发：左下角后右下角各一次，间隔=${currentTime - lastLeftBottomSingleTapAt}ms")
                lastLeftBottomSingleTapAt = 0L
                // 取消任何待处理的后退/清除延迟
                undoDelayedRunnable?.let { handler.removeCallbacks(it) }
                undoDelayedRunnable = null
                return@setOnClickListener
            }
            
            if (!isHiddenFeatureEnabled) {
                // 功能未开启时，检测两次点击
                if (currentTime - lastHiddenClickTime > HIDDEN_CLICK_TIMEOUT) {
                    // 超时重置计数
                    hiddenButtonClickCount = 1
                } else {
                    hiddenButtonClickCount++
                }
                
                lastHiddenClickTime = currentTime
                Log.d("ClientActivity", "隐藏按钮点击计数: $hiddenButtonClickCount/2")
                
                if (hiddenButtonClickCount >= 2) {
                    // 两次点击完成，开启隐藏功能
                    enableHiddenFeature()
                    hiddenButtonClickCount = 0
                }
            } else {
                // 功能已开启时：优先组合关闭；仅保留双击清除与单击后退（取消三击关闭）
                // 优先检测组合关闭：左下角→右下角各一次
                val comboActive = isHiddenFeatureEnabled && lastLeftBottomSingleTapAt > 0 && (currentTime - lastLeftBottomSingleTapAt) <= DEACTIVATE_COMBO_TIMEOUT
                if (comboActive) {
                    disableHiddenFeature()
                    Log.d("ClientActivity", "组合关闭触发：左下角后右下角各一次，间隔=${currentTime - lastLeftBottomSingleTapAt}ms")
                    lastLeftBottomSingleTapAt = 0L
                    // 取消任何待处理的后退/清除延迟
                    undoDelayedRunnable?.let { handler.removeCallbacks(it) }
                    undoDelayedRunnable = null
                    return@setOnClickListener
                }
                
                // 双击判定窗口
                val interval = currentTime - lastHiddenClickTime
                if (interval <= DOUBLE_CLICK_TIMEOUT) {
                    // 第二击：执行清除
                    undoDelayedRunnable?.let { handler.removeCallbacks(it) }
                    undoDelayedRunnable = null
                    clearScreen()
                    hiddenButtonClickCount = 0
                    Log.d("ClientActivity", "双击清除执行 (间隔: ${interval}ms)")
                } else {
                    // 首击：安排单击延迟后退
                    hiddenButtonClickCount = 1
                    lastHiddenClickTime = currentTime
                    undoDelayedRunnable?.let { handler.removeCallbacks(it) }
                    undoDelayedRunnable = Runnable {
                        undoLastWriting()
                        hiddenButtonClickCount = 0
                        Log.d("ClientActivity", "单击后退执行（未形成双击）")
                    }
                    handler.postDelayed(undoDelayedRunnable!!, DOUBLE_CLICK_TIMEOUT)
                    Log.d("ClientActivity", "首击：安排单击后退延迟")
                }
            }
        }
        
        // 设置隐藏画板的尺寸，使其与屏幕比例相同
        setupHiddenCanvasSize()
        
        // 设置书写完成监听器
        hiddenDrawingView.setOnWritingCompletedListener(object : HiddenDrawingView.OnWritingCompletedListener {
            override fun onWritingCompleted(sessionBitmap: Bitmap) {
                // 书写会话完成时，全屏显示内容
                displayWritingFullscreen(sessionBitmap)
                Log.d("ClientActivity", "书写会话完成，全屏显示")
                schedulePostWritingFakeTouch()
            }
            
            override fun onRequestBackgroundColorDetection(): Boolean {
                // 隐藏画板请求检测背景色
                return detectScreenBackgroundColor()
            }
            
            override fun getCurrentWritingCount(): Int {
                // 返回当前已有的书写数量
                return writingContentList.size
            }
        })
        
    }
    
    override fun onUserInteraction() {
        super.onUserInteraction()
        lastUserInteractionAt = System.currentTimeMillis()
    }
    
    private fun setupCombinedButton() {
        btnCombined.visibility = View.VISIBLE
        Log.d("ClientActivity", "左上角触控：笔刷由旁侧两按钮切换，1.5 秒内三击重连")
    }

    private fun setupBrushButtons() {
        btnBrushNormal.setOnClickListener { selectBrush(BrushType.NORMAL) }
        btnBrushChalk.setOnClickListener { selectBrush(BrushType.CHALK) }
    }

    private fun updateBrushButtonHighlight() {
        if (!::btnBrushNormal.isInitialized) return
        when (currentBrushType) {
            BrushType.NORMAL -> {
                btnBrushNormal.alpha = 1f
                btnBrushChalk.alpha = 0.42f
            }
            BrushType.CHALK -> {
                btnBrushNormal.alpha = 0.42f
                btnBrushChalk.alpha = 1f
            }
        }
        // 与保存按钮同款灰点：白底上几乎看不见，按底色调成深/浅色
        val tint = if (isWhiteCanvas) Color.rgb(52, 52, 52) else Color.rgb(235, 235, 235)
        val cf = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
        btnBrushNormal.colorFilter = cf
        btnBrushChalk.colorFilter = cf
    }

    /** 点击笔刷按钮：直接设为对应类型 */
    private fun selectBrush(type: BrushType) {
        if (currentBrushType == type) return
        currentBrushType = type
        saveBrushType(currentBrushType)
        showBrushToggleFlash()
        applyBrushTypeToHiddenView()
        updateBrushButtonHighlight()
        Log.d("ClientActivity", "笔刷设为: $currentBrushType")
    }
    
    // 恢复上次的设置
    private fun restoreLastSettings() {
        try {
            // 恢复笔刷类型
            val lastBrushType = getLastBrushType()
            currentBrushType = lastBrushType
            applyBrushTypeToHiddenView()
            Log.d("ClientActivity", "已恢复笔刷类型: $lastBrushType")
            
            // 恢复背景颜色（须先于笔刷图标着色）
            val lastBackgroundColor = getLastBackgroundColor()
            isWhiteCanvas = lastBackgroundColor
            
            // 设置初始背景颜色
            val backgroundColor = if (isWhiteCanvas) Color.WHITE else Color.BLACK
            updateBackgroundColors(backgroundColor)
            
            // 同步隐藏画板笔色 + 笔刷按钮对比度（含 updateBrushButtonHighlight）
            updateHiddenDrawingPenColor()
            
            Log.d("ClientActivity", "已恢复背景颜色: ${if (isWhiteCanvas) "白色" else "黑色"}")
        } catch (e: Exception) {
            Log.e("ClientActivity", "恢复设置时出错: ${e.message}")
        }
    }
    
    /** 与书写功能（隐藏画板）开关同步：左侧保存条 + 左上角笔刷条 */
    private fun setWritingFeatureSidebarsVisible(visible: Boolean) {
        val vis = if (visible) View.VISIBLE else View.GONE
        if (::saveButtonsContainer.isInitialized) saveButtonsContainer.visibility = vis
        if (::brushButtonsContainer.isInitialized) brushButtonsContainer.visibility = vis
    }

    private fun setupSaveButtons() {
        setWritingFeatureSidebarsVisible(false)
        
        // 设置保存按钮的触摸事件
        setupSaveButtonTouchListener(btnSave1, 1)
        setupSaveButtonTouchListener(btnSave2, 2)
        setupSaveButtonTouchListener(btnSave3, 3)
        
        // 创建保存目录
        createSaveDirectory()
        
        // 加载之前保存的截图路径
        loadSavedScreenshotPaths()
        
        Log.d("ClientActivity", "保存按钮已设置：长按3秒保存，点击加载，初始状态为隐藏，样式为三个不同的按钮图标")
    }
    
    private fun loadSavedScreenshotPaths() {
        try {
            val saveDir = File(filesDir, SAVE_DIRECTORY)
            if (saveDir.exists()) {
                val files = saveDir.listFiles()
                files?.forEach { file ->
                    if (file.name.startsWith("screenshot_") && file.name.endsWith(".png")) {
                        // 从文件名中提取按钮ID
                        val buttonIdStr = file.name.substringAfter("screenshot_").substringBefore(".png")
                        val buttonId = buttonIdStr.toIntOrNull()
                        if (buttonId != null && buttonId in 1..3) {
                            savedScreenshots[buttonId] = file.absolutePath
                            Log.d("ClientActivity", "加载保存的截图路径: 按钮$buttonId -> ${file.absolutePath}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ClientActivity", "加载保存的截图路径失败: ${e.message}")
        }
    }
    
    private fun setupSaveButtonTouchListener(button: ImageView, buttonId: Int) {
        button.setOnTouchListener { _, event ->
            // 每次触摸时都确保保存/笔刷条可见（书写功能开启时）
            if (isHiddenFeatureEnabled && saveButtonsContainer.visibility != View.VISIBLE) {
                setWritingFeatureSidebarsVisible(true)
                Log.d("ClientActivity", "触摸保存按钮时重新显示容器，按钮ID: $buttonId")
            }
            
            // 第一个点和第二个点同时按住3秒切换 Swap mode
            if (buttonId == 1 || buttonId == 2) {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        if (buttonId == 1) isSave1Pressed = true else isSave2Pressed = true
                        swapModeComboRunnable?.let { swapModeComboHandler.removeCallbacks(it) }
                        if (isSave1Pressed && isSave2Pressed) {
                            longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                            currentLongPressButtonId = -1
                            swapModeComboRunnable = Runnable {
                                swapModeComboJustFired = true
                                val newMode = !isSwapModeEnabled()
                                setSwapModeEnabled(newMode)
                                showSwapModeToggleFlash()
                                Log.d("ClientActivity", "Swap mode 已切换为: $newMode")
                            }
                            swapModeComboHandler.postDelayed(swapModeComboRunnable!!, SWAP_MODE_COMBO_DURATION)
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (buttonId == 1) isSave1Pressed = false else isSave2Pressed = false
                        swapModeComboRunnable?.let { swapModeComboHandler.removeCallbacks(it) }
                        swapModeComboRunnable = null
                        if (swapModeComboJustFired) {
                            swapModeComboJustFired = false
                            return@setOnTouchListener true
                        }
                    }
                }
            }
            
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (swapModeComboJustFired) return@setOnTouchListener true
                    if ((buttonId == 1 || buttonId == 2) && isSave1Pressed && isSave2Pressed) {
                        return@setOnTouchListener true
                    }
                    longPressStartTime = System.currentTimeMillis()
                    currentLongPressButtonId = buttonId
                    longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                    longPressRunnable = Runnable {
                        saveCurrentScreenshot(buttonId)
                        currentLongPressButtonId = -1
                    }
                    longPressHandler.postDelayed(longPressRunnable!!, LONG_PRESS_DURATION)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                    if (swapModeComboJustFired) return@setOnTouchListener true
                    if (currentLongPressButtonId == buttonId) {
                        loadSavedScreenshot(buttonId)
                        currentLongPressButtonId = -1
                    }
                    true
                }
                else -> false
            }
        }
    }
    
    private fun createSaveDirectory() {
        try {
            val saveDir = File(filesDir, SAVE_DIRECTORY)
            if (!saveDir.exists()) {
                saveDir.mkdirs()
                Log.d("ClientActivity", "创建保存目录: ${saveDir.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e("ClientActivity", "创建保存目录失败: ${e.message}")
        }
    }
    
    private fun saveCurrentScreenshot(buttonId: Int) {
        try {
            // 获取当前屏幕的截图
            val screenshot = captureCurrentScreen()
            if (screenshot != null) {
                // 保存到文件
                val fileName = "screenshot_$buttonId.png"
                val saveFile = File(File(filesDir, SAVE_DIRECTORY), fileName)
                
                val outputStream = FileOutputStream(saveFile)
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                outputStream.close()
                
                // 更新保存路径
                savedScreenshots[buttonId] = saveFile.absolutePath
                
                // 显示保存成功提示
                showSaveSuccessToast(buttonId)
                
                Log.d("ClientActivity", "截图已保存到: ${saveFile.absolutePath}")
            } else {
                Log.e("ClientActivity", "无法获取当前屏幕截图")
                // Toast弹窗已取消
            }
        } catch (e: Exception) {
            Log.e("ClientActivity", "保存截图失败: ${e.message}")
            // Toast弹窗已取消
        }
    }
    
    private fun captureCurrentScreen(): Bitmap? {
        try {
            // 获取当前屏幕的尺寸
            val displayMetrics = DisplayMetrics()
            windowManager.defaultDisplay.getMetrics(displayMetrics)
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels
            
            // 创建屏幕大小的位图
            val screenshot = try {
                Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.ARGB_8888)
            } catch (e: OutOfMemoryError) {
                Log.e("ClientActivity", "截图时内存不足: ${e.message}")
                return null
            }
            val canvas = Canvas(screenshot)
            
            // 绘制当前屏幕内容（缩放绘制，避免只截取左上角）
            if (bitmapCache != null) {
                val src = Rect(0, 0, bitmapCache!!.width, bitmapCache!!.height)
                val dst = Rect(0, 0, screenWidth, screenHeight)
                canvas.drawBitmap(bitmapCache!!, src, dst, null)
            } else {
                // 否则绘制背景色
                val backgroundColor = if (isWhiteCanvas) Color.WHITE else Color.BLACK
                canvas.drawColor(backgroundColor)
            }
            
            // 如果有书写内容显示，也绘制上去
            if (writingDisplayContainer.visibility == View.VISIBLE) {
                val writingBitmap = (writingDisplayView.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                writingBitmap?.let { bitmap ->
                    val src = Rect(0, 0, bitmap.width, bitmap.height)
                    val dst = Rect(0, 0, screenWidth, screenHeight)
                    canvas.drawBitmap(bitmap, src, dst, null)
                }
            }
            
            return screenshot
        } catch (e: Exception) {
            Log.e("ClientActivity", "截图失败: ${e.message}")
            return null
        }
    }
    
    private fun loadSavedScreenshot(buttonId: Int) {
        try {
            val filePath = savedScreenshots[buttonId]
            if (filePath != null) {
                val file = File(filePath)
                if (file.exists()) {
                    // 加载保存的截图
                    val bitmap = BitmapFactory.decodeFile(filePath)
                    if (bitmap != null) {
                        // 显示加载的截图
                        displayLoadedScreenshot(bitmap)
                        // Toast弹窗已取消
                        Log.d("ClientActivity", "成功加载截图: $filePath")
                    } else {
                        Log.e("ClientActivity", "无法解码保存的截图文件")
                        // Toast弹窗已取消
                    }
                } else {
                    Log.e("ClientActivity", "保存的截图文件不存在: $filePath")
                    // Toast弹窗已取消
                }
            } else {
                Log.d("ClientActivity", "按钮 $buttonId 没有保存的截图")
                // Toast弹窗已取消
            }
        } catch (e: Exception) {
            Log.e("ClientActivity", "加载截图失败: ${e.message}")
            // Toast弹窗已取消
        }
    }
    
    private fun displayLoadedScreenshot(bitmap: Bitmap) {
        try {
            // 更新当前位图缓存
            bitmapCache?.recycle()
            try {
                bitmapCache = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            } catch (e: OutOfMemoryError) {
                Log.e("ClientActivity", "加载截图时内存不足，使用原始位图: ${e.message}")
                bitmapCache = bitmap
            }
            
            // 更新主屏幕显示
            imageView.setImageBitmap(bitmapCache)
            
            // 检测背景色并更新
            val backgroundColor = detectBackgroundColorFromBitmap(bitmap)
            updateBackgroundColors(backgroundColor)
            
            // 隐藏书写内容显示
            writingDisplayContainer.visibility = View.GONE
            
            // 保持保存/笔刷条可见（书写功能开启时）
            if (isHiddenFeatureEnabled) {
                setWritingFeatureSidebarsVisible(true)
            }
            
            Log.d("ClientActivity", "截图已加载并显示，保存/笔刷条保持可见")
        } catch (e: Exception) {
            Log.e("ClientActivity", "显示加载的截图失败: ${e.message}")
        }
    }
    
    private fun detectBackgroundColorFromBitmap(bitmap: Bitmap): Int {
        try {
            // 从位图中采样检测背景色
            val samplePoints = listOf(
                Pair(bitmap.width / 4, bitmap.height / 4),
                Pair(bitmap.width / 2, bitmap.height / 2),
                Pair(bitmap.width * 3 / 4, bitmap.height * 3 / 4)
            )
            
            var whitePixelCount = 0
            var totalPixelCount = 0
            
            for ((x, y) in samplePoints) {
                try {
                    if (x >= 0 && x < bitmap.width && y >= 0 && y < bitmap.height) {
                        val pixel = bitmap.getPixel(x, y)
                        totalPixelCount++
                        if (isPixelWhite(pixel)) {
                            whitePixelCount++
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ClientActivity", "Error sampling pixel at ($x, $y): ${e.message}")
                }
            }
            
            if (totalPixelCount > 0) {
                val whiteRatio = whitePixelCount.toFloat() / totalPixelCount
                isWhiteCanvas = whiteRatio > 0.5f
                return if (isWhiteCanvas) Color.WHITE else Color.BLACK
            }
        } catch (e: Exception) {
            Log.e("ClientActivity", "从位图检测背景色失败: ${e.message}")
        }
        
        // 默认返回黑色背景
        return Color.BLACK
    }
    
    private fun showSaveSuccessToast(buttonId: Int) {
        // Toast弹窗已取消
        Log.d("ClientActivity", "截图已保存到按钮 $buttonId")
    }
    
    private fun startSaveButtonMonitor() {
        // 启动定期监控保存按钮的可见性状态
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (isHiddenFeatureEnabled) {
                    val currentVisibility = saveButtonsContainer.visibility
                    Log.d("ClientActivity", "保存按钮状态监控: 当前可见性=$currentVisibility, 期望可见性=${View.VISIBLE}")
                    
                    // 如果保存条应该是可见的但实际不可见，则重新显示（笔刷条同步）
                    if (currentVisibility != View.VISIBLE) {
                        Log.w("ClientActivity", "检测到保存按钮意外隐藏，重新显示")
                        setWritingFeatureSidebarsVisible(true)
                        saveButtonsContainer.requestLayout()
                        saveButtonsContainer.invalidate()
                        brushButtonsContainer.requestLayout()
                        brushButtonsContainer.invalidate()
                        
                        runOnUiThread {
                            setWritingFeatureSidebarsVisible(true)
                            saveButtonsContainer.bringToFront()
                            brushButtonsContainer.bringToFront()
                        }
                    }
                    
                    // 继续监控
                    handler.postDelayed(this, 500) // 每0.5秒检查一次，更频繁的监控
                }
            }
        }, 500) // 0.5秒后开始监控
    }
    
    private fun debugSaveButtonStatus() {
        try {
            Log.d("ClientActivity", "=== 保存按钮状态调试 ===")
            Log.d("ClientActivity", "保存按钮容器: ${saveButtonsContainer.javaClass.simpleName}")
            Log.d("ClientActivity", "容器可见性: ${saveButtonsContainer.visibility}")
            Log.d("ClientActivity", "容器宽度: ${saveButtonsContainer.width}, 高度: ${saveButtonsContainer.height}")
            Log.d("ClientActivity", "容器父视图: ${saveButtonsContainer.parent?.javaClass?.simpleName}")
            Log.d("ClientActivity", "根布局: ${rootLayout.javaClass.simpleName}")
            Log.d("ClientActivity", "根布局子视图数量: ${rootLayout.childCount}")
            
            // 检查每个保存按钮的状态
            for (i in 0 until saveButtonsContainer.childCount) {
                val child = saveButtonsContainer.getChildAt(i)
                Log.d("ClientActivity", "子视图 $i: ${child.javaClass.simpleName}, 可见性: ${child.visibility}")
            }
            
            Log.d("ClientActivity", "=== 调试结束 ===")
        } catch (e: Exception) {
            Log.e("ClientActivity", "调试保存按钮状态失败: ${e.message}")
        }
    }
    
    private fun performReconnect() {
        // En Modo V2 (servidor) el triple toque NO debe arrancar la maquinaria
        // de cliente V1: desconectaba al móvil y dejaba ambos modos conviviendo.
        // El servidor V2 ya se auto-repara aceptando la siguiente conexión.
        if (isServerMode) {
            Log.d("ClientActivity", "performReconnect ignorado en Modo V2 (servidor)")
            return
        }
        // 执行重连操作
        drawingSocketManager.disconnect()
        statusIndicator.visibility = View.VISIBLE
        
        // 优先尝试连接上次成功连接的IP
        val lastAddress = getLastConnectedAddress()
        if (lastAddress != null) {
            Log.d("ClientActivity", "尝试快速连接上次地址: ${lastAddress.first}:${lastAddress.second}")
            lifecycleScope.launch {
                val success = drawingSocketManager.tryQuickConnect(lastAddress.first, lastAddress.second)
                if (!success) {
                    Log.d("ClientActivity", "快速连接失败，开始搜索连接")
                    startAutoConnect()
                } else {
                    Log.d("ClientActivity", "快速连接成功")
                }
            }
        } else {
            Log.d("ClientActivity", "无上次连接记录，开始搜索连接")
            startAutoConnect()
        }
    }
    
    private fun showBrushToggleFlash() {
        // 创建临时圆点视图
        val dotSize = (20 * resources.displayMetrics.density).toInt() // 20dp转换为像素
        val dotView = View(this).apply {
            val layoutParams = android.widget.RelativeLayout.LayoutParams(dotSize, dotSize).apply {
                // 在点击区域中心显示圆点
                addRule(android.widget.RelativeLayout.ALIGN_PARENT_START)
                addRule(android.widget.RelativeLayout.ALIGN_PARENT_TOP)
                val margin = (30 * resources.displayMetrics.density).toInt() // 30dp转换为像素
                setMargins(margin, margin, 0, 0)
            }
            this.layoutParams = layoutParams
            background = resources.getDrawable(R.drawable.brush_toggle_dot, null)
            alpha = 1.0f
        }
        
        // 添加到根布局
        rootLayout.addView(dotView)
        
        // 创建闪烁动画
        val blinkAnimation = android.view.animation.AlphaAnimation(1.0f, 0.3f).apply {
            duration = 200 // 每次闪烁200ms
            repeatCount = 4 // 重复4次，总共1秒
            repeatMode = android.view.animation.Animation.REVERSE
        }
        
        dotView.startAnimation(blinkAnimation)
        
        // 1秒后移除圆点
        handler.postDelayed({
            rootLayout.removeView(dotView)
        }, 1000)
    }
    
    private fun showSwapModeToggleFlash() {
        val dotSize = (8 * resources.displayMetrics.density).toInt()
        val dotView = View(this).apply {
            val layoutParams = android.widget.RelativeLayout.LayoutParams(dotSize, dotSize).apply {
                addRule(android.widget.RelativeLayout.CENTER_IN_PARENT)
            }
            this.layoutParams = layoutParams
            setBackgroundColor(Color.GRAY)
            alpha = 1.0f
        }
        rootLayout.addView(dotView)
        val blinkAnimation = android.view.animation.AlphaAnimation(1.0f, 0f).apply {
            duration = 150
            fillAfter = true
        }
        blinkAnimation.setAnimationListener(object : android.view.animation.Animation.AnimationListener {
            override fun onAnimationStart(animation: android.view.animation.Animation) {}
            override fun onAnimationRepeat(animation: android.view.animation.Animation) {}
            override fun onAnimationEnd(animation: android.view.animation.Animation) {
                if (dotView.parent != null) {
                    (dotView.parent as? ViewGroup)?.removeView(dotView)
                }
            }
        })
        dotView.startAnimation(blinkAnimation)
        handler.postDelayed({
            if (dotView.parent != null) {
                try { (dotView.parent as? ViewGroup)?.removeView(dotView) } catch (_: Exception) {}
            }
        }, 250)
    }
    
    private fun applyBrushTypeToHiddenView() {
        // 将笔刷类型应用到隐藏画板
        if (::hiddenDrawingView.isInitialized) {
            val hiddenViewBrushType = when (currentBrushType) {
                BrushType.NORMAL -> HiddenDrawingView.BrushType.NORMAL
                BrushType.CHALK -> HiddenDrawingView.BrushType.CHALK
            }
            hiddenDrawingView.setBrushType(hiddenViewBrushType)
        }
    }
    
    private fun setupHiddenCanvasSize() {
        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(displayMetrics)
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val screenRatio = screenHeight.toFloat() / screenWidth.toFloat()
        val density = resources.displayMetrics.density
        val dpi = displayMetrics.densityDpi
        
        // SONY DPT RP1特殊处理：13.3英寸屏幕，1650x2200分辨率，206 DPI
        val isDPTDevice = (screenWidth == 1650 && screenHeight == 2200) || 
                         (screenWidth == 2200 && screenHeight == 1650) ||
                         (dpi in 200..210) // DPT RP1的DPI是206
        
        // 新方案：使用与屏幕相同的像素比例，但缩小尺寸避免失真
        val scaleFactor = if (isDPTDevice) {
            0.3f // DPT设备：30%的屏幕尺寸，保持像素比例
        } else {
            0.25f // 普通设备：25%的屏幕尺寸
        }
        
        // 直接使用像素尺寸，保持与屏幕完全相同的比例
        val canvasWidthPx = (screenWidth * scaleFactor).toInt()
        val canvasHeightPx = (screenHeight * scaleFactor).toInt()
        
        val layoutParams = hiddenDrawingView.layoutParams as RelativeLayout.LayoutParams
        layoutParams.width = canvasWidthPx
        layoutParams.height = canvasHeightPx
        
        // SONY DPT RP1兼容性：确保布局参数正确设置
        layoutParams.addRule(RelativeLayout.ALIGN_PARENT_TOP)
        layoutParams.addRule(RelativeLayout.ALIGN_PARENT_END)
        layoutParams.topMargin = 0
        layoutParams.rightMargin = 0
        
        // DPT设备特殊处理：确保画板在正确位置
        if (isDPTDevice) {
            layoutParams.topMargin = 20 // 稍微向下偏移
            layoutParams.rightMargin = 20 // 稍微向左偏移
        }
        
        hiddenDrawingView.layoutParams = layoutParams
        
        Log.d("ClientActivity", "设置隐藏画板像素尺寸: ${layoutParams.width} x ${layoutParams.height}")
        Log.d("ClientActivity", "屏幕信息: ${screenWidth}x${screenHeight}, 缩放比例: $scaleFactor")
        Log.d("ClientActivity", "画板与屏幕比例: ${layoutParams.width.toFloat()/screenWidth} x ${layoutParams.height.toFloat()/screenHeight}")
        Log.d("ClientActivity", "检测到DPT设备: $isDPTDevice")
        Log.d("ClientActivity", "画板位置: top=${layoutParams.topMargin}, right=${layoutParams.rightMargin}")
    }
    
    private fun getDeviceInfo(): String {
        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(displayMetrics)
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val dpi = displayMetrics.densityDpi
        
        val isDPTDevice = (screenWidth == 1650 && screenHeight == 2200) || 
                         (screenWidth == 2200 && screenHeight == 1650) ||
                         (dpi in 200..210)
        
        return if (isDPTDevice) {
            "DPT设备 ${screenWidth}x${screenHeight}"
        } else {
            "普通设备 ${screenWidth}x${screenHeight}"
        }
    }
    
    private fun enableHiddenFeature() {
        isHiddenFeatureEnabled = true
        
        // 安卓5.1兼容性：确保视图正确设置
        hiddenDrawingView.visibility = View.VISIBLE // 改为可见
        hiddenDrawingView.isEnabled = true // 确保可以接收触摸事件
        hiddenDrawingView.isFocusable = true // 安卓5.1需要明确设置焦点
        hiddenDrawingView.isFocusableInTouchMode = true // 触摸模式下可获得焦点
        hiddenDrawingView.isClickable = true // 可点击
        
        // 强制刷新布局，确保在墨水屏设备上正确显示
        hiddenDrawingView.requestLayout()
        hiddenDrawingView.invalidate()
        
        // SONY DPT RP1特殊处理：添加额外的触摸监听器
        hiddenDrawingView.setOnTouchListener { view, event ->
            Log.d("ClientActivity", "DPT额外触摸监听器: action=${event.action}, x=${event.x}, y=${event.y}")
            // 让自定义View处理触摸事件
            view.onTouchEvent(event)
        }
        
        // 检测当前屏幕背景色并设置相应的画笔颜色
        val screenBackgroundColor = detectScreenBackgroundColor()
        hiddenDrawingView.setAutoPenColor(screenBackgroundColor)
        
        // 设置画板为透明背景
        hiddenDrawingView.setCanvasVisible(true)
        
        // 显示保存条 + 笔刷条（多重保障）
        setWritingFeatureSidebarsVisible(true)
        saveButtonsContainer.bringToFront()
        brushButtonsContainer.bringToFront()
        
        saveButtonsContainer.requestLayout()
        saveButtonsContainer.invalidate()
        brushButtonsContainer.requestLayout()
        brushButtonsContainer.invalidate()
        
        handler.postDelayed({
            if (isHiddenFeatureEnabled) {
                setWritingFeatureSidebarsVisible(true)
                saveButtonsContainer.requestLayout()
                saveButtonsContainer.invalidate()
                brushButtonsContainer.requestLayout()
                brushButtonsContainer.invalidate()
                Log.d("ClientActivity", "延迟确认保存条可见性: ${saveButtonsContainer.visibility}")
            }
        }, 100)
        
        handler.postDelayed({
            if (isHiddenFeatureEnabled) {
                setWritingFeatureSidebarsVisible(true)
                saveButtonsContainer.bringToFront()
                brushButtonsContainer.bringToFront()
                Log.d("ClientActivity", "最终确认保存条可见性: ${saveButtonsContainer.visibility}")
            }
        }, 500)
        
        Log.d("ClientActivity", "隐藏功能已开启，保存/笔刷条已显示，状态: ${saveButtonsContainer.visibility}")
        
        // 调试：检查保存按钮的详细状态
        debugSaveButtonStatus()
        
        // 安卓5.1兼容性：测试触摸能力
        val canTouch = hiddenDrawingView.testTouchCapability()
        Log.d("ClientActivity", "隐藏画板触摸能力测试结果: $canTouch")
        
        // 可以添加一些视觉反馈，比如短暂的提示
        // Toast弹窗已移除
        Log.d("ClientActivity", "隐藏功能已激活")
        
        // 启动保存按钮状态监控
        startSaveButtonMonitor()
        updateBrushButtonHighlight()
    }
    
    private fun disableHiddenFeature() {
        try {
            isHiddenFeatureEnabled = false
            // 隐藏与禁用隐藏画板
            if (::hiddenDrawingView.isInitialized) {
                hiddenDrawingView.clearAllSessions()
                hiddenDrawingView.visibility = View.INVISIBLE
                hiddenDrawingView.isEnabled = false
                hiddenDrawingView.isClickable = false
                hiddenDrawingView.isFocusable = false
                hiddenDrawingView.isFocusableInTouchMode = false
            }
            // 隐藏全屏显示容器
            if (::writingDisplayContainer.isInitialized) {
                writingDisplayContainer.visibility = View.GONE
            }
            setWritingFeatureSidebarsVisible(false)
            // 取消任何待处理的后退操作
            undoDelayedRunnable?.let { handler.removeCallbacks(it) }
            undoDelayedRunnable = null
            Log.d("ClientActivity", "隐藏书写功能已关闭")
        } catch (e: Exception) {
            Log.e("ClientActivity", "关闭隐藏功能失败: ${e.message}")
        }
    }
    
    private fun undoLastWriting() {
        // 后退功能：移除最后一次书写内容
        if (writingContentList.isNotEmpty()) {
            val removedBitmap = writingContentList.removeAt(writingContentList.size - 1)
            removedBitmap.recycle() // 回收位图资源
            
            if (writingContentList.isEmpty()) {
                // 如果没有内容了，隐藏显示容器
                writingDisplayContainer.visibility = View.GONE
                Log.d("ClientActivity", "后退完成，所有书写内容已清空")
            } else {
                // 重新创建堆叠显示
                val stackedBitmap = createStackedWritingBitmap()
                writingDisplayView.setImageBitmap(stackedBitmap)
                writingDisplayView.requestLayout()
                writingDisplayView.post { writingDisplayView.invalidate() }
                Log.d("ClientActivity", "后退完成，剩余${writingContentList.size}个书写内容")
            }
        } else {
            Log.d("ClientActivity", "没有可后退的书写内容")
        }
    }
    
    // 添加内存管理和性能优化方法
    private fun optimizeMemoryAndPerformance() {
        try {
            // 强制垃圾回收
            System.gc()
            
            // 检查内存状态
            val memoryUsage = getMemoryUsage()
            
            if (memoryUsage > 0.7f) { // 内存使用超过70%
                Log.w("ClientActivity", "内存使用率过高: ${(memoryUsage * 100).toInt()}%，清理缓存")
                
                // 清理一些旧的位图缓存
                if (writingContentList.size > 4) {
                    val oldBitmap = writingContentList.removeAt(0)
                    oldBitmap.recycle()
                    Log.d("ClientActivity", "清理旧位图缓存，当前缓存数量: ${writingContentList.size}")
                }
                
                // 再次强制垃圾回收
                System.gc()
            }
            
            Log.d("ClientActivity", "内存优化完成，使用率: ${(memoryUsage * 100).toInt()}%")
        } catch (e: Exception) {
            Log.e("ClientActivity", "内存优化时出错: ${e.message}")
        }
    }
    
    // 获取当前内存使用率
    private fun getMemoryUsage(): Float {
        return try {
            val runtime = Runtime.getRuntime()
            val usedMemory = runtime.totalMemory() - runtime.freeMemory()
            val maxMemory = runtime.maxMemory()
            usedMemory.toFloat() / maxMemory.toFloat()
        } catch (e: Exception) {
            Log.e("ClientActivity", "获取内存使用率时出错: ${e.message}")
            0.5f // 返回默认值
        }
    }
    
    // 在显示书写内容前调用内存优化
    private fun displayWritingFullscreen(bitmap: Bitmap) {
        // 先进行内存优化
        optimizeMemoryAndPerformance()
        
        // 全屏显示书写内容，支持垂直堆叠
        runOnUiThread {
            try {
                Log.d("ClientActivity", "准备全屏显示书写内容，原始尺寸: ${bitmap.width} x ${bitmap.height}")
                
                // 检查位图是否有效
                if (bitmap.isRecycled) {
                    Log.w("ClientActivity", "位图已被回收，跳过显示")
                    return@runOnUiThread
                }
                
                // 裁剪掉空白区域，获得紧凑的内容
                val croppedBitmap = cropContentBounds(bitmap)
                Log.d("ClientActivity", "裁剪后尺寸: ${croppedBitmap.width} x ${croppedBitmap.height}")
                
                // 交换模式：如果即将添加第5张图片，先保存第4张到剪贴板并移除它
                if (isSwapModeEnabled() && writingContentList.size == 4) {
                    // 当前有4张图片，即将添加第5张
                    val fourthBitmap = writingContentList[3] // 第4张图片（索引3）
                    Log.d("ClientActivity", "交换模式：检测到即将添加第5张图片，将第4张图片保存到剪贴板并移除")
                    saveBitmapToClipboard(fourthBitmap)
                    
                    // 移除第4张图片（索引3）
                    val removedBitmap = writingContentList.removeAt(3)
                    if (removedBitmap != croppedBitmap) { // 避免回收当前位图
                        removedBitmap.recycle()
                    }
                    Log.d("ClientActivity", "交换模式：第4张图片已从列表中移除，当前列表大小: ${writingContentList.size}")
                }
                
                // 将裁剪后的书写内容添加到列表
                writingContentList.add(croppedBitmap)
                
                // 正常的数量限制检查（现在应该不会触发，因为我们已经特殊处理了第5张的情况）
                while (writingContentList.size > maxWritingCount) {
                    val oldBitmap = writingContentList.removeAt(0)
                    if (oldBitmap != croppedBitmap) { // 避免回收当前位图
                        oldBitmap.recycle()
                    }
                }
                
                // 内存过高时先清理再启动后台任务，避免OOM
                val memoryUsage = getMemoryUsage()
                if (memoryUsage > 0.85f) {
                    Log.w("ClientActivity", "内存使用率过高(${(memoryUsage * 100).toInt()}%)，创建堆叠位图前先清理缓存")
                    clearBitmapCache()
                    System.gc()
                }
                // 异步创建堆叠显示的位图，避免主线程阻塞
                val startThread = {
                    Thread {
                        try {
                            val stackedBitmap = createStackedWritingBitmap()
                        
                        // 回到主线程更新UI
                        runOnUiThread {
                            try {
                                writingDisplayView.setImageBitmap(stackedBitmap)
                                writingDisplayContainer.visibility = View.VISIBLE
                                // 强制立即布局，确保擦除功能可用
                                writingDisplayView.requestLayout()
                                writingDisplayView.post { 
                                    writingDisplayView.invalidate()
                                    Log.d("ClientActivity", "writingDisplayView 布局完成，尺寸: ${writingDisplayView.width}x${writingDisplayView.height}")
                                }
                                Log.d("ClientActivity", "全屏显示书写内容成功，堆叠${writingContentList.size}个内容")
                                
                                // 交换模式：如果刚刚显示了第5张图片（即刚才保存了第4张到剪贴板），自动读取剪贴板并添加为第6张
                                if (isSwapModeEnabled() && writingContentList.size == 4 && lastClipboardTempFile != null) {
                                    Log.d("ClientActivity", "交换模式：检测到刚显示第5张图片，准备自动从剪贴板读取第4张图片作为第6张")
                                    autoInputFromClipboard()
                                }
                            } catch (e: Exception) {
                                Log.e("ClientActivity", "更新UI时出错: ${e.message}")
                            }
                        }
                    } catch (e: OutOfMemoryError) {
                        Log.e("ClientActivity", "创建堆叠位图时内存不足: ${e.message}")
                        runOnUiThread {
                            try {
                                writingDisplayView.setImageBitmap(croppedBitmap)
                                writingDisplayContainer.visibility = View.VISIBLE
                                writingDisplayView.requestLayout()
                                writingDisplayView.post { writingDisplayView.invalidate() }
                                Log.d("ClientActivity", "内存不足，使用原始位图作为备用显示")
                            } catch (e2: Exception) {
                                Log.e("ClientActivity", "显示备用位图时出错: ${e2.message}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("ClientActivity", "创建堆叠位图时出错: ${e.message}")
                        // 回到主线程显示错误状态
                        runOnUiThread {
                            try {
                                // 显示原始位图作为备用
                                writingDisplayView.setImageBitmap(croppedBitmap)
                                writingDisplayContainer.visibility = View.VISIBLE
                                writingDisplayView.requestLayout()
                                writingDisplayView.post { writingDisplayView.invalidate() }
                                Log.d("ClientActivity", "使用原始位图作为备用显示")
                            } catch (e2: Exception) {
                                Log.e("ClientActivity", "显示备用位图时出错: ${e2.message}")
                            }
                        }
                    }
                }.start()
                }
                if (memoryUsage > 0.85f) {
                    handler.postDelayed(startThread, 150)
                } else {
                    startThread()
                }
                
            } catch (e: Exception) {
                Log.e("ClientActivity", "全屏显示书写内容时出错: ${e.message}")
                // 显示错误提示
                try {
                    writingDisplayView.setImageBitmap(bitmap)
                    writingDisplayContainer.visibility = View.VISIBLE
                    writingDisplayView.requestLayout()
                    writingDisplayView.post { writingDisplayView.invalidate() }
                    Log.d("ClientActivity", "使用原始位图显示")
                } catch (e2: Exception) {
                    Log.e("ClientActivity", "显示原始位图时出错: ${e2.message}")
                }
            }
        }
    }
    
    private fun schedulePostWritingFakeTouch() {
        try {
            pendingFakeTouchRunnable?.let { handler.removeCallbacks(it) }
            val scheduledAt = System.currentTimeMillis()
            pendingFakeTouchRunnable = Runnable {
                val now = System.currentTimeMillis()
                val idleDuration = now - lastUserInteractionAt
                if (idleDuration >= POST_WRITING_FAKE_TOUCH_DELAY) {
                    Log.w(
                        "ClientActivity",
                        "书写完成后${idleDuration}ms无交互，触发伪触摸刷新"
                    )
                    performFakeTouchAtBottomCenter()
                } else {
                    Log.d(
                        "ClientActivity",
                        "书写完成后检测到交互，空闲${idleDuration}ms，小于阈值，跳过伪触摸"
                    )
                }
                pendingFakeTouchRunnable = null
            }
            handler.postDelayed(pendingFakeTouchRunnable!!, POST_WRITING_FAKE_TOUCH_DELAY)
            Log.d(
                "ClientActivity",
                "已安排伪触摸刷新任务，将在${POST_WRITING_FAKE_TOUCH_DELAY}ms后检查（计划时间: $scheduledAt）"
            )
        } catch (e: Exception) {
            Log.e("ClientActivity", "安排伪触摸刷新任务失败: ${e.message}")
        }
    }
    
    private fun performFakeTouchAtBottomCenter() {
        try {
            if (!::fakeTouchSink.isInitialized) return
            injectFakeTouchToSink(0)
        } catch (e: Exception) {
            Log.e("ClientActivity", "执行伪触摸时出错: ${e.message}")
        }
    }

    /** 仅在 fakeTouchSink 内派发 DOWN/UP，避免主画布画点、手指擦除或点到按钮 */
    private fun injectFakeTouchToSink(retry: Int) {
        val v = fakeTouchSink
        val w = v.width
        val h = v.height
        if ((w <= 0 || h <= 0) && retry < 10) {
            v.post { injectFakeTouchToSink(retry + 1) }
            return
        }
        if (w <= 0 || h <= 0) {
            Log.w("ClientActivity", "伪触摸跳过：fakeTouchSink 未布局 w=$w h=$h")
            return
        }
        val x = w / 2f
        val y = h / 2f
        val downTime = SystemClock.uptimeMillis()
        val downEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        val upEventTime = downTime + 60
        val upEvent = MotionEvent.obtain(downTime, upEventTime, MotionEvent.ACTION_UP, x, y, 0)
        v.dispatchTouchEvent(downEvent)
        v.dispatchTouchEvent(upEvent)
        downEvent.recycle()
        upEvent.recycle()
        Log.d("ClientActivity", "伪触摸已注入 fakeTouchSink，局部 ($x, $y)")
    }
    
    private fun isEmptyBitmap(bitmap: Bitmap): Boolean {
        // 简单检查位图尺寸
        if (bitmap.width <= 1 || bitmap.height <= 1) return true
        
        // 检查更多采样点，降低透明度阈值，确保能检测到细微的绘画内容
        val samplePoints = mutableListOf<Pair<Int, Int>>()
        
        // 添加更多采样点
        for (x in listOf(bitmap.width / 4, bitmap.width / 2, bitmap.width * 3 / 4)) {
            for (y in listOf(bitmap.height / 4, bitmap.height / 2, bitmap.height * 3 / 4)) {
                if (x < bitmap.width && y < bitmap.height) {
                    samplePoints.add(Pair(x, y))
                }
            }
        }
        
        // 如果任何一个采样点有可见内容，就认为位图不为空
        var hasVisibleContent = false
        for ((x, y) in samplePoints) {
            try {
                val pixel = bitmap.getPixel(x, y)
                if (Color.alpha(pixel) > 5) { // 降低阈值，更容易检测到内容
                    hasVisibleContent = true
                    break
                }
            } catch (e: Exception) {
                Log.e("ClientActivity", "Error checking pixel at ($x, $y): ${e.message}")
            }
        }
        
        val isEmpty = !hasVisibleContent
        Log.d("ClientActivity", "位图空检测结果: $isEmpty (尺寸: ${bitmap.width}x${bitmap.height})")
        return isEmpty
    }
    
    private fun scaleWritingToFullscreen(originalBitmap: Bitmap): Bitmap {
        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(displayMetrics)
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        
        // 计算缩放比例以填充整个屏幕，保持宽高比
        val scaleX = screenWidth.toFloat() / originalBitmap.width
        val scaleY = screenHeight.toFloat() / originalBitmap.height
        val scale = maxOf(scaleX, scaleY) // 使用较大的缩放比例以填充屏幕
        
        val scaledWidth = (originalBitmap.width * scale).toInt()
        val scaledHeight = (originalBitmap.height * scale).toInt()
        
        // 创建全屏大小的位图
        val fullscreenBitmap = try {
            Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            Log.e("ClientActivity", "创建全屏位图时内存不足，返回原始位图: ${e.message}")
            return originalBitmap
        }
        val canvas = Canvas(fullscreenBitmap)
        canvas.drawColor(Color.TRANSPARENT)
        
        // 将缩放后的位图居中绘制到全屏位图上
        val scaledBitmap = try {
            Bitmap.createScaledBitmap(originalBitmap, scaledWidth, scaledHeight, true)
        } catch (e: OutOfMemoryError) {
            Log.e("ClientActivity", "缩放位图时内存不足，返回原始位图: ${e.message}")
            return originalBitmap
        }
        val left = (screenWidth - scaledWidth) / 2f
        val top = (screenHeight - scaledHeight) / 2f
        canvas.drawBitmap(scaledBitmap, left, top, null)
        
        return fullscreenBitmap
    }
    
    private fun createStackedWritingBitmap(): Bitmap {
        try {
            val displayMetrics = DisplayMetrics()
            windowManager.defaultDisplay.getMetrics(displayMetrics)
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels
            
            // 定义安全边距
            val safePadding = 30 // 30像素的安全边距
            val safeWidth = screenWidth - (safePadding * 2) // 减去左右边距
            val safeHeight = screenHeight - (safePadding * 2) // 减去上下边距
            
            // 创建全屏大小的位图
            val stackedBitmap = Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(stackedBitmap)
            canvas.drawColor(Color.TRANSPARENT)
            
            if (writingContentList.isEmpty()) {
                return stackedBitmap
            }
            
            // 计算统一的图片宽度，确保所有图片宽度一致
            val unifiedWidth = if (writingContentList.size >= 2) {
                // 第二笔及以后，都使用60%限制的宽度，保持一致的缩放比例
                val maxWidth = (safeWidth * 0.6f).toInt()
                maxWidth
            } else {
                // 第一笔时，使用安全区域宽度
                safeWidth
            }
            
            // 智能调整：当图片过多时，自动缩小宽度以适应屏幕
            val adjustedWidth = if (writingContentList.size > 3) {
                // 超过3张图片时，根据数量动态调整宽度
                val scaleFactor = when (writingContentList.size) {
                    4 -> 0.8f  // 4张图片时使用80%宽度
                    5 -> 0.7f  // 5张图片时使用70%宽度
                    6 -> 0.6f  // 6张图片时使用60%宽度
                    else -> 0.5f // 更多图片时使用50%宽度
                }
                (unifiedWidth * scaleFactor).toInt()
            } else {
                unifiedWidth
            }
            
            // 检测是否有特别高的图片，需要进一步调整
            var hasVeryHighImage = false
            var maxImageHeight = 0f
            for (bitmap in writingContentList) {
                val scale = adjustedWidth.toFloat() / bitmap.width
                val scaledHeight = bitmap.height * scale
                if (scaledHeight > maxImageHeight) {
                    maxImageHeight = scaledHeight
                }
                // 如果任何图片的高度超过可用高度的40%，认为有高图片
                if (scaledHeight > safeHeight * 0.4f) {
                    hasVeryHighImage = true
                }
            }
            
            // 如果有高图片，进一步缩小宽度
            val finalWidth = if (hasVeryHighImage && writingContentList.size > 2) {
                val heightScaleFactor = (safeHeight * 0.4f) / maxImageHeight
                val widthScaleFactor = minOf(0.8f, heightScaleFactor)
                (adjustedWidth * widthScaleFactor).toInt()
            } else {
                adjustedWidth
            }
            
            // 额外检查：如果调整后的宽度仍然会导致堆叠，进一步缩小
            var finalAdjustedWidth = finalWidth
            var totalHeightWithCurrentWidth = 0f
            for (bitmap in writingContentList) {
                val scale = finalWidth.toFloat() / bitmap.width
                val scaledHeight = bitmap.height * scale
                totalHeightWithCurrentWidth += scaledHeight
                if (writingContentList.indexOf(bitmap) < writingContentList.size - 1) {
                    totalHeightWithCurrentWidth += 30f // 添加间距
                }
            }
            
            // 如果总高度仍然超过可用高度，进一步缩小宽度
            if (totalHeightWithCurrentWidth > safeHeight) {
                val heightRatio = safeHeight / totalHeightWithCurrentWidth
                val additionalWidthScale = minOf(0.9f, heightRatio)
                finalAdjustedWidth = (finalWidth * additionalWidthScale).toInt()
                Log.w("ClientActivity", "内容高度仍然过高，进一步缩小宽度: $finalWidth -> $finalAdjustedWidth, 高度比例: $heightRatio")
            }
            
            Log.d("ClientActivity", "智能宽度调整: 原始宽度=$unifiedWidth, 调整后宽度=$adjustedWidth, 最终宽度=$finalWidth, 防堆叠宽度=$finalAdjustedWidth, 图片数量=${writingContentList.size}, 有高图片=$hasVeryHighImage, 最大高度=$maxImageHeight, 总高度=$totalHeightWithCurrentWidth")
            
            when (writingContentList.size) {
                1 -> {
                    // 第一次书写：限制最多占屏幕60%
                    val bitmap = writingContentList[0]
                    val scaledBitmap = scaleWritingWithLimit(bitmap, safeWidth, safeHeight, 0.6f)
                    
                    // 在安全区域内居中绘制
                    val xOffset = safePadding + (safeWidth - scaledBitmap.width) / 2f
                    val yOffset = safePadding + (safeHeight - scaledBitmap.height) / 2f
                    
                    canvas.drawBitmap(scaledBitmap, xOffset, yOffset, null)
                    Log.d("ClientActivity", "第一次书写，原始尺寸显示: 位置($xOffset, $yOffset), 尺寸(${scaledBitmap.width}x${scaledBitmap.height})")
                }
                else -> {
                    // 多次书写：垂直堆叠显示，新内容在下方，旧内容向上移动
                    // 使用紧凑布局，根据实际内容高度分配空间，在安全区域内显示
                    val totalContentHeight = calculateTotalContentHeightWithConsistentScaling(safeWidth, safeHeight)
                    val availableHeight = safeHeight
                    
                    Log.d("ClientActivity", "开始处理多次书写，数量: ${writingContentList.size}, 统一宽度: $unifiedWidth, 总内容高度: $totalContentHeight, 可用高度: $availableHeight")
                    
                    // 如果内容总高度小于安全区域高度，使用实际高度；否则压缩到安全区域高度
                    val useCompactLayout = totalContentHeight <= availableHeight
                    
                    // 预计算所有缩放后的位图，避免在绘制循环中重复计算
                    val scaledBitmaps = mutableListOf<Bitmap>()
                    for ((index, writingBitmap) in writingContentList.withIndex()) {
                        Log.d("ClientActivity", "缩放第${index + 1}个位图，原始尺寸: ${writingBitmap.width}x${writingBitmap.height}")
                        val scaledBitmap = scaleWritingToUnifiedWidth(writingBitmap, finalAdjustedWidth)
                        Log.d("ClientActivity", "缩放后尺寸: ${scaledBitmap.width}x${scaledBitmap.height}")
                        scaledBitmaps.add(scaledBitmap)
                    }
                    
                    Log.d("ClientActivity", "所有位图缩放完成，共${scaledBitmaps.size}个")
                    
                    for (i in writingContentList.indices) {
                        val scaledBitmap = scaledBitmaps[i]
                        // 反转位置：最新的内容(最后一个)在最下方
                        val reverseIndex = writingContentList.size - 1 - i
                        
                        Log.d("ClientActivity", "处理第${i + 1}个位图，显示顺序: $reverseIndex, 缩放后尺寸: ${scaledBitmap.width}x${scaledBitmap.height}")
                        
                        // 计算实际绘制位置
                        val yPosition = if (useCompactLayout) {
                            // 紧凑布局：居中显示所有内容
                            // 计算内容组的起始Y位置（居中）
                            val contentGroupStartY = (availableHeight - totalContentHeight) / 2f
                            
                            // 累加前面所有内容的高度（按显示顺序），使用与实际绘制一致的缩放比例
                            var currentContentY = safePadding + contentGroupStartY
                            for (displayOrder in 0 until reverseIndex) {
                                val bitmapIndex = writingContentList.size - 1 - displayOrder
                                if (bitmapIndex >= 0 && bitmapIndex < writingContentList.size) {
                                    val bitmap = writingContentList[bitmapIndex]
                                    // 使用调整后的宽度缩放计算高度
                                    val adjustedScale = finalAdjustedWidth.toFloat() / bitmap.width
                                    val scaledHeight = bitmap.height * adjustedScale
                                    currentContentY += scaledHeight
                                    Log.d("ClientActivity", "累加高度: 位图$bitmapIndex, 缩放比例: $adjustedScale, 高度: $scaledHeight, 累计Y: $currentContentY")
                                }
                            }
                            
                            currentContentY
                        } else {
                            // 压缩布局：优先保证图片不重叠，宁可横向拉伸也不能堆叠
                            val currentBitmapHeight = scaledBitmap.height
                            
                            // 计算所有图片的总高度（包括间距）
                            var totalRequiredHeight = 0f
                            for (j in writingContentList.indices) {
                                val tempBitmap = writingContentList[j]
                                val tempScale = finalAdjustedWidth.toFloat() / tempBitmap.width
                                val tempHeight = tempBitmap.height * tempScale
                                totalRequiredHeight += tempHeight
                                if (j < writingContentList.size - 1) {
                                    totalRequiredHeight += 30f // 添加间距
                                }
                            }
                            
                            // 如果总高度超过可用高度，需要调整策略
                            if (totalRequiredHeight > availableHeight) {
                                Log.w("ClientActivity", "内容高度过高，启用防堆叠模式: 总需求高度=$totalRequiredHeight, 可用高度=$availableHeight")
                                
                                // 计算每个图片应该占用的高度，确保不重叠
                                val availableHeightPerImage = (availableHeight - (30f * (writingContentList.size - 1))) / writingContentList.size
                                Log.d("ClientActivity", "防堆叠模式: 每张图片可用高度=$availableHeightPerImage")
                                
                                // 计算当前图片的Y位置
                                var baseY = safePadding.toFloat()
                                for (j in 0 until reverseIndex) {
                                    val bitmapIndex = writingContentList.size - 1 - j
                                    if (bitmapIndex >= 0 && bitmapIndex < writingContentList.size) {
                                        val bitmap = writingContentList[bitmapIndex]
                                        val scale = finalAdjustedWidth.toFloat() / bitmap.width
                                        val height = bitmap.height * scale
                                        
                                        // 如果当前图片高度超过分配高度，使用分配高度
                                        val actualHeight = minOf(height, availableHeightPerImage)
                                        baseY += actualHeight + 30f
                                    }
                                }
                                
                                baseY
                            } else {
                                // 总高度在可用范围内，使用正常布局
                                var baseY = safePadding.toFloat()
                                for (j in 0 until reverseIndex) {
                                    val bitmapIndex = writingContentList.size - 1 - j
                                    if (bitmapIndex >= 0 && bitmapIndex < writingContentList.size) {
                                        val bitmap = writingContentList[bitmapIndex]
                                        val scale = finalAdjustedWidth.toFloat() / bitmap.width
                                        val height = bitmap.height * scale
                                        baseY += height + 30f // 添加间距
                                    }
                                }
                                
                                baseY
                            }
                        }
                        
                        // 在安全区域内居中绘制
                        val xOffset = safePadding + (safeWidth - scaledBitmap.width) / 2f
                        
                        // 验证Y位置是否合理，避免重叠
                        val minY = safePadding.toFloat()
                        val maxY = (safeHeight - scaledBitmap.height).toFloat()
                        val validatedY = yPosition.coerceIn(minY, maxY)
                        
                        // 检查是否会与前面的图片重叠
                        var hasOverlap = false
                        var previousY = 0f
                        if (i > 0) {
                            val previousBitmap = scaledBitmaps[i - 1]
                            previousY = if (useCompactLayout) {
                                // 紧凑布局下的前一个位置
                                var prevY = safePadding + (availableHeight - totalContentHeight) / 2f
                                for (displayOrder in 0 until (reverseIndex + 1)) {
                                    val bitmapIndex = writingContentList.size - 1 - displayOrder
                                    if (bitmapIndex >= 0 && bitmapIndex < writingContentList.size) {
                                        val bitmap = writingContentList[bitmapIndex]
                                        val adjustedScale = finalAdjustedWidth.toFloat() / bitmap.width
                                        val scaledHeight = bitmap.height * adjustedScale
                                        prevY += scaledHeight
                                    }
                                }
                                prevY
                            } else {
                                // 压缩布局下的前一个位置
                                var prevY = safePadding.toFloat()
                                for (j in 0 until (reverseIndex + 1)) {
                                    val bitmapIndex = writingContentList.size - 1 - j
                                    if (bitmapIndex >= 0 && bitmapIndex < writingContentList.size) {
                                        val bitmap = writingContentList[bitmapIndex]
                                        val scale = finalAdjustedWidth.toFloat() / bitmap.width
                                        val height = bitmap.height * scale
                                        prevY += height + 30f
                                    }
                                }
                                prevY
                            }
                            
                            // 检查重叠
                            val currentBottom = validatedY + scaledBitmap.height
                            val previousBottom = previousY + previousBitmap.height
                            hasOverlap = currentBottom > previousY && validatedY < previousBottom
                            
                            if (hasOverlap) {
                                Log.w("ClientActivity", "检测到重叠: 当前图片($validatedY, $currentBottom) 与 前一个图片($previousY, $previousBottom)")
                                // 调整位置避免重叠
                                val adjustedY = previousY + previousBitmap.height + 10f // 添加10px额外间距
                                if (adjustedY + scaledBitmap.height <= safeHeight.toFloat()) {
                                    Log.d("ClientActivity", "调整位置避免重叠: $validatedY -> $adjustedY")
                                    canvas.drawBitmap(scaledBitmap, xOffset, adjustedY, null)
                                } else {
                                    Log.w("ClientActivity", "无法避免重叠，使用原始位置")
                                    canvas.drawBitmap(scaledBitmap, xOffset, validatedY, null)
                                }
                            } else {
                                canvas.drawBitmap(scaledBitmap, xOffset, validatedY, null)
                            }
                        } else {
                            // 第一张图片，直接绘制
                            canvas.drawBitmap(scaledBitmap, xOffset, validatedY, null)
                        }
                        
                        if (validatedY != yPosition) {
                            Log.w("ClientActivity", "Y位置调整: 原始=$yPosition, 调整后=$validatedY, 位图高度=${scaledBitmap.height}")
                        }
                        
                        val centeringInfo = if (useCompactLayout) {
                            val contentGroupStartY = (availableHeight - totalContentHeight) / 2f
                            "居中起始Y: $contentGroupStartY"
                        } else {
                            "压缩模式，间距: 30px, 重叠检测: $hasOverlap"
                        }
                        Log.d("ClientActivity", "绘制第${i+1}个书写内容(索引$i, 显示顺序$reverseIndex)，位置: ($xOffset, $validatedY), 尺寸: (${scaledBitmap.width}x${scaledBitmap.height}), 调整后宽度: $finalAdjustedWidth, 紧凑布局: $useCompactLayout, 总内容高度: $totalContentHeight, 安全区域: ${safeWidth}x${safeHeight}, 边距: ${safePadding}px, $centeringInfo")
                    }
                    
                    // 清理临时位图，避免内存泄漏
                    for (tempBitmap in scaledBitmaps) {
                        // 检查这个临时位图是否在原始列表中，如果不在则回收
                        var isOriginalBitmap = false
                        for (originalBitmap in writingContentList) {
                            if (tempBitmap == originalBitmap) {
                                isOriginalBitmap = true
                                break
                            }
                        }
                        if (!isOriginalBitmap) {
                            tempBitmap.recycle()
                        }
                    }
                }
            }
            
            return stackedBitmap
        } catch (e: Exception) {
            Log.e("ClientActivity", "创建堆叠位图时出错: ${e.message}")
            // 返回空位图作为备用
            val displayMetrics = DisplayMetrics()
            windowManager.defaultDisplay.getMetrics(displayMetrics)
            return try {
                Bitmap.createBitmap(displayMetrics.widthPixels, displayMetrics.heightPixels, Bitmap.Config.ARGB_8888)
            } catch (oom: OutOfMemoryError) {
                Log.e("ClientActivity", "创建备用位图时内存不足: ${oom.message}")
                throw oom // 抛给外层 Thread 的 OutOfMemoryError 捕获处理
            }
        }
    }
    
    private fun scaleWritingToStackedHeight(originalBitmap: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        // 计算缩放比例，保持宽高比
        val scaleX = targetWidth.toFloat() / originalBitmap.width
        val scaleY = targetHeight.toFloat() / originalBitmap.height
        val scale = minOf(scaleX, scaleY) // 使用较小的比例以保持完整显示
        
        val scaledWidth = (originalBitmap.width * scale).toInt()
        val scaledHeight = (originalBitmap.height * scale).toInt()
        
        // 使用高质量矩阵缩放，避免像素化失真
        val matrix = Matrix()
        matrix.setScale(scale, scale)
        
        return Bitmap.createBitmap(originalBitmap, 0, 0, originalBitmap.width, originalBitmap.height, matrix, true).apply {
            density = Bitmap.DENSITY_NONE // 避免额外的密度缩放导致失真
        }
    }
    
    private fun scaleWritingToWidth(originalBitmap: Bitmap, targetWidth: Int): Bitmap {
        // 只缩放宽度适应屏幕，保持原始宽高比
        val scale = targetWidth.toFloat() / originalBitmap.width
        val scaledWidth = targetWidth
        val scaledHeight = (originalBitmap.height * scale).toInt()
        
        // 使用高质量矩阵缩放，避免像素化失真
        val matrix = Matrix()
        matrix.setScale(scale, scale)
        
        return Bitmap.createBitmap(originalBitmap, 0, 0, originalBitmap.width, originalBitmap.height, matrix, true).apply {
            density = Bitmap.DENSITY_NONE // 避免额外的密度缩放导致失真
        }
    }
    
    private fun scaleWritingToFitScreen(originalBitmap: Bitmap, screenWidth: Int, screenHeight: Int): Bitmap {
        // 缩放内容以适应屏幕，确保不超出边界，保持宽高比
        val scaleX = screenWidth.toFloat() / originalBitmap.width
        val scaleY = screenHeight.toFloat() / originalBitmap.height
        
        // 使用较小的缩放比例，确保内容完全在屏幕内
        val scale = minOf(scaleX, scaleY)
        
        val scaledWidth = (originalBitmap.width * scale).toInt()
        val scaledHeight = (originalBitmap.height * scale).toInt()
        
        Log.d("ClientActivity", "适应屏幕缩放: 原始(${originalBitmap.width}x${originalBitmap.height}) -> 缩放(${scaledWidth}x${scaledHeight}), 比例: $scale")
        
        // 使用高质量矩阵缩放，避免像素化失真
        val matrix = Matrix()
        matrix.setScale(scale, scale)
        
        return Bitmap.createBitmap(originalBitmap, 0, 0, originalBitmap.width, originalBitmap.height, matrix, true).apply {
            density = Bitmap.DENSITY_NONE // 避免额外的密度缩放导致失真
        }
    }
    
    private fun scaleWritingWithLimit(originalBitmap: Bitmap, screenWidth: Int, screenHeight: Int, maxScreenRatio: Float): Bitmap {
        // 计算适应屏幕的缩放比例
        val scaleX = screenWidth.toFloat() / originalBitmap.width
        val scaleY = screenHeight.toFloat() / originalBitmap.height
        val fullScale = minOf(scaleX, scaleY)
        
        // 计算限制比例下的最大尺寸
        val maxWidth = (screenWidth * maxScreenRatio).toInt()
        val maxHeight = (screenHeight * maxScreenRatio).toInt()
        
        // 计算限制比例下的缩放
        val limitScaleX = maxWidth.toFloat() / originalBitmap.width
        val limitScaleY = maxHeight.toFloat() / originalBitmap.height
        val limitScale = minOf(limitScaleX, limitScaleY)
        
        // 使用较小的缩放比例（不超过限制）
        val scale = minOf(fullScale, limitScale)
        
        val scaledWidth = (originalBitmap.width * scale).toInt()
        val scaledHeight = (originalBitmap.height * scale).toInt()
        
        Log.d("ClientActivity", "限制缩放: 原始(${originalBitmap.width}x${originalBitmap.height}) -> 缩放(${scaledWidth}x${scaledHeight}), 比例: $scale, 限制: ${maxScreenRatio * 100}%")
        
        // 使用高质量矩阵缩放，避免像素化失真
        val matrix = Matrix()
        matrix.setScale(scale, scale)
        
        return Bitmap.createBitmap(originalBitmap, 0, 0, originalBitmap.width, originalBitmap.height, matrix, true).apply {
            density = Bitmap.DENSITY_NONE // 避免额外的密度缩放导致失真
        }
    }
    
    private fun calculateTotalContentHeight(screenWidth: Int): Float {
        // 计算所有内容在屏幕宽度下的总高度，所有书写都使用原始尺寸
        var totalHeight = 0f
        for (bitmap in writingContentList) {
            val scale = screenWidth.toFloat() / bitmap.width
            val scaledHeight = bitmap.height * scale
            totalHeight += scaledHeight
        }
        return totalHeight
    }
    
    private fun calculateTotalContentHeightWithConsistentScaling(screenWidth: Int, screenHeight: Int): Float {
        // 计算所有内容在屏幕宽度下的总高度，使用统一的宽度缩放逻辑
        var totalHeight = 0f
        
        // 计算统一的图片宽度，确保所有图片宽度一致
        val unifiedWidth = if (writingContentList.size >= 2) {
            // 第二笔及以后，都使用60%限制的宽度，保持一致的缩放比例
            val maxWidth = (screenWidth * 0.6f).toInt()
            maxWidth
        } else {
            // 第一笔时，使用安全区域宽度
            screenWidth
        }
        
        // 智能调整：当图片过多时，自动缩小宽度以适应屏幕
        val adjustedWidth = if (writingContentList.size > 3) {
            // 超过3张图片时，根据数量动态调整宽度
            val scaleFactor = when (writingContentList.size) {
                4 -> 0.8f  // 4张图片时使用80%宽度
                5 -> 0.7f  // 5张图片时使用70%宽度
                6 -> 0.6f  // 6张图片时使用60%宽度
                else -> 0.5f // 更多图片时使用50%宽度
            }
            (unifiedWidth * scaleFactor).toInt()
        } else {
            unifiedWidth
        }
        
        // 检测是否有特别高的图片，需要进一步调整
        var hasVeryHighImage = false
        var maxImageHeight = 0f
        for (bitmap in writingContentList) {
            val scale = adjustedWidth.toFloat() / bitmap.width
            val scaledHeight = bitmap.height * scale
            if (scaledHeight > maxImageHeight) {
                maxImageHeight = scaledHeight
            }
            // 如果任何图片的高度超过可用高度的40%，认为有高图片
            if (scaledHeight > screenHeight * 0.4f) {
                hasVeryHighImage = true
            }
        }
        
        // 如果有高图片，进一步缩小宽度
        val finalWidth = if (hasVeryHighImage && writingContentList.size > 2) {
            val heightScaleFactor = (screenHeight * 0.4f) / maxImageHeight
            val widthScaleFactor = minOf(0.8f, heightScaleFactor)
            (adjustedWidth * widthScaleFactor).toInt()
        } else {
            adjustedWidth
        }
        
        // 额外检查：如果调整后的宽度仍然会导致堆叠，进一步缩小
        var finalAdjustedWidth = finalWidth
        var totalHeightWithCurrentWidth = 0f
        for (bitmap in writingContentList) {
            val scale = finalWidth.toFloat() / bitmap.width
            val scaledHeight = bitmap.height * scale
            totalHeightWithCurrentWidth += scaledHeight
            if (writingContentList.indexOf(bitmap) < writingContentList.size - 1) {
                totalHeightWithCurrentWidth += 30f // 添加间距
            }
        }
        
        // 如果总高度仍然超过可用高度，进一步缩小宽度
        if (totalHeightWithCurrentWidth > screenHeight) {
            val heightRatio = screenHeight / totalHeightWithCurrentWidth
            val additionalWidthScale = minOf(0.9f, heightRatio)
            finalAdjustedWidth = (finalWidth * additionalWidthScale).toInt()
            Log.w("ClientActivity", "计算总高度时检测到内容过高，进一步缩小宽度: $finalWidth -> $finalAdjustedWidth, 高度比例: $heightRatio")
        }
        
        Log.d("ClientActivity", "计算总高度: 列表大小=${writingContentList.size}, 统一宽度=$unifiedWidth, 调整后宽度=$adjustedWidth, 最终宽度=$finalWidth, 防堆叠宽度=$finalAdjustedWidth, 屏幕宽度=$screenWidth, 有高图片=$hasVeryHighImage, 最大高度=$maxImageHeight, 总高度=$totalHeightWithCurrentWidth")
        
        for ((index, bitmap) in writingContentList.withIndex()) {
            // 使用防堆叠宽度缩放计算高度
            val finalScale = finalAdjustedWidth.toFloat() / bitmap.width
            val scaledHeight = bitmap.height * finalScale
            totalHeight += scaledHeight
            
            Log.d("ClientActivity", "位图$index: 原始尺寸=${bitmap.width}x${bitmap.height}, 防堆叠缩放比例=$finalScale, 缩放后高度=$scaledHeight, 累计总高度=$totalHeight")
        }
        
        Log.d("ClientActivity", "总高度计算完成: $totalHeight")
        return totalHeight
    }
    
    private fun scaleWritingToUnifiedWidth(originalBitmap: Bitmap, targetWidth: Int): Bitmap {
        try {
            // 使用统一的宽度进行缩放，保持原始宽高比
            val scale = targetWidth.toFloat() / originalBitmap.width
            val scaledWidth = targetWidth
            val scaledHeight = (originalBitmap.height * scale).toInt()
            
            Log.d("ClientActivity", "统一宽度缩放: 原始(${originalBitmap.width}x${originalBitmap.height}) -> 目标宽度: $targetWidth, 缩放比例: $scale, 目标尺寸: ${scaledWidth}x${scaledHeight}")
            
            // 使用Bitmap.createScaledBitmap进行缩放，更可靠
            val scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, scaledWidth, scaledHeight, true)
            
            // 设置密度避免额外缩放
            scaledBitmap.density = Bitmap.DENSITY_NONE
            
            Log.d("ClientActivity", "统一宽度缩放完成: ${scaledBitmap.width}x${scaledBitmap.height}")
            return scaledBitmap
            
        } catch (e: OutOfMemoryError) {
            Log.e("ClientActivity", "统一宽度缩放时内存不足，返回原始位图: ${e.message}")
            return originalBitmap
        } catch (e: Exception) {
            Log.e("ClientActivity", "统一宽度缩放失败: ${e.message}")
            // 返回原始位图作为备用
            return originalBitmap
        }
    }
    
    // getContentHeightSum函数已移除，直接在循环中计算位置
    
    private fun cropContentBounds(originalBitmap: Bitmap): Bitmap {
        // 找到实际内容的边界，裁剪掉空白区域
        val width = originalBitmap.width
        val height = originalBitmap.height
        
        if (width <= 0 || height <= 0) {
            return originalBitmap
        }
        
        var minX = width
        var maxX = -1
        var minY = height
        var maxY = -1
        
        // 优化：减少扫描密度，提高性能
        val scanStep = maxOf(1, minOf(width, height) / 100) // 根据图片大小动态调整扫描步长
        
        // 扫描每个像素，找到非透明像素的边界
        for (y in 0 until height step scanStep) {
            for (x in 0 until width step scanStep) {
                try {
                    val pixel = originalBitmap.getPixel(x, y)
                    val alpha = Color.alpha(pixel)
                    
                    if (alpha > 0) { // 找到非透明像素
                        minX = minOf(minX, x)
                        maxX = maxOf(maxX, x)
                        minY = minOf(minY, y)
                        maxY = maxOf(maxY, y)
                    }
                } catch (e: Exception) {
                    // 忽略边界像素访问错误
                    continue
                }
            }
        }
        
        // 如果没有找到任何内容，返回原始位图
        if (minX >= width || maxX < 0 || minY >= height || maxY < 0) {
            Log.d("ClientActivity", "未找到有效内容，返回原始位图")
            return originalBitmap
        }
        
        // 添加一些边距，避免内容太紧贴边缘
        val padding = maxOf(5, scanStep) // 根据扫描步长调整边距
        minX = maxOf(0, minX - padding)
        maxX = minOf(width - 1, maxX + padding)
        minY = maxOf(0, minY - padding)
        maxY = minOf(height - 1, maxY + padding)
        
        val croppedWidth = maxX - minX + 1
        val croppedHeight = maxY - minY + 1
        
        // 检查裁剪尺寸是否合理
        if (croppedWidth <= 0 || croppedHeight <= 0 || croppedWidth > width || croppedHeight > height) {
            Log.w("ClientActivity", "裁剪尺寸无效，返回原始位图: ${croppedWidth}x${croppedHeight}")
            return originalBitmap
        }
        
        Log.d("ClientActivity", "内容边界: ($minX, $minY) -> ($maxX, $maxY), 裁剪尺寸: ${croppedWidth} x ${croppedHeight}, 扫描步长: $scanStep")
        
        try {
            // 创建裁剪后的位图
            return Bitmap.createBitmap(originalBitmap, minX, minY, croppedWidth, croppedHeight)
        } catch (e: OutOfMemoryError) {
            Log.e("ClientActivity", "裁剪位图时内存不足，返回原始位图: ${e.message}")
            return originalBitmap
        } catch (e: Exception) {
            Log.e("ClientActivity", "创建裁剪位图时出错: ${e.message}")
            return originalBitmap
        }
    }
    
    private fun detectScreenBackgroundColor(): Boolean {
        // 检测当前屏幕的背景颜色，返回true表示白色背景，false表示黑色背景
        try {
            // 方法1：检查isWhiteCanvas变量
            val detectedWhite = isWhiteCanvas
            var finalDetectedWhite = detectedWhite
            
            // 方法2：优先从bitmapCache检测（最新的图片）
            var bitmapCacheDetected = false
            bitmapCache?.let { bitmap ->
                try {
                    val centerPixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                    val topLeftPixel = bitmap.getPixel(0, 0)
                    val topRightPixel = bitmap.getPixel(bitmap.width - 1, 0)
                    val bottomLeftPixel = bitmap.getPixel(0, bitmap.height - 1)
                    val bottomRightPixel = bitmap.getPixel(bitmap.width - 1, bitmap.height - 1)
                    
                    val whiteCount = listOf(
                        isPixelWhite(centerPixel),
                        isPixelWhite(topLeftPixel),
                        isPixelWhite(topRightPixel),
                        isPixelWhite(bottomLeftPixel),
                        isPixelWhite(bottomRightPixel)
                    ).count { it }
                    // 至少 4/5 为白才判为白底：黑底上用白笔在边角书写时 2～3 点易误判，右上角一笔即可占满「白」采样
                    finalDetectedWhite = whiteCount >= 4
                    bitmapCacheDetected = true
                    Log.d("ClientActivity", "从bitmapCache检测背景色 - 白色像素数量: $whiteCount/5, 检测结果: ${if (finalDetectedWhite) "白色" else "黑色"}")
                } catch (e: Exception) {
                    Log.e("ClientActivity", "从bitmapCache检测背景色失败: ${e.message}")
                }
            }
            
            // 方法3：如果bitmapCache检测失败，尝试从currentBitmap检测
            if (!bitmapCacheDetected) {
                currentBitmap?.let { bitmap ->
                    val samplePoints = listOf(
                        Pair(bitmap.width / 4, bitmap.height / 4),
                        Pair(bitmap.width / 2, bitmap.height / 2),
                        Pair(bitmap.width * 3 / 4, bitmap.height / 4),
                        Pair(bitmap.width / 4, bitmap.height * 3 / 4),
                        Pair(bitmap.width * 3 / 4, bitmap.height * 3 / 4)
                    )
                    
                    var whitePixelCount = 0
                    var totalPixelCount = 0
                    
                    for ((x, y) in samplePoints) {
                        try {
                            if (x >= 0 && x < bitmap.width && y >= 0 && y < bitmap.height) {
                                val pixel = bitmap.getPixel(x, y)
                                totalPixelCount++
                                if (isPixelWhite(pixel)) {
                                    whitePixelCount++
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("ClientActivity", "Error sampling pixel at ($x, $y): ${e.message}")
                        }
                    }
                    
                    if (totalPixelCount > 0) {
                        val whiteRatio = whitePixelCount.toFloat() / totalPixelCount
                        finalDetectedWhite = whiteRatio > 0.5f
                        
                        Log.d("ClientActivity", "从currentBitmap检测背景色 - isWhiteCanvas: $detectedWhite, 位图检测: $finalDetectedWhite (白色像素比例: ${whiteRatio})")
                    }
                }
            }
            
            // 不再用窗口 decorView 背景覆盖位图结论：主题/系统色可能与画布无关，黑底模式下易误判为白底

            // 如果检测结果与当前isWhiteCanvas不一致，更新isWhiteCanvas
            if (finalDetectedWhite != isWhiteCanvas) {
                Log.d("ClientActivity", "检测到背景色变化: ${if (isWhiteCanvas) "白色" else "黑色"} -> ${if (finalDetectedWhite) "白色" else "黑色"}")
                isWhiteCanvas = finalDetectedWhite
                saveBackgroundColor(isWhiteCanvas)
                updateHiddenDrawingPenColor()
            }
            
            Log.d("ClientActivity", "背景色检测完成，最终结果: ${if (finalDetectedWhite) "白色" else "黑色"}")
            return finalDetectedWhite
            
        } catch (e: Exception) {
            Log.e("ClientActivity", "背景色检测失败: ${e.message}")
            // 默认假设是黑色背景
            return false
        }
    }
    
    private fun isColorWhite(color: Int): Boolean {
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)
        return red > 200 && green > 200 && blue > 200
    }
    
    private fun initializeEraserFunction() {
        // 创建一个初始的空白位图，确保擦除功能可以立即使用
        imageView.post {
            if (bitmapCache == null) {
                val width = if (imageView.width > 0) imageView.width else 1650
                val height = if (imageView.height > 0) imageView.height else 2200
                
                try {
                    val initialBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(initialBitmap)
                    val backgroundColor = if (isWhiteCanvas) Color.WHITE else Color.BLACK
                    canvas.drawColor(backgroundColor)
                    
                    bitmapCache = initialBitmap
                    imageView.setImageBitmap(initialBitmap)
                } catch (e: OutOfMemoryError) {
                    Log.e("ClientActivity", "初始化擦除功能时内存不足: ${e.message}")
                    return@post
                }
                
                // 设置触摸监听器
                if (!touchListenerSet) {
                    setupTouchListener()
                    touchListenerSet = true
                    Log.d("ClientActivity", "初始化时启用手指触摸擦除功能")
                }
            }
        }
    }
    
    private fun updateHiddenDrawingPenColor() {
        // 当背景色发生变化时，同步更新隐藏画板的画笔颜色
        if (::hiddenDrawingView.isInitialized) {
            hiddenDrawingView.setAutoPenColor(isWhiteCanvas)
            Log.d("ClientActivity", "同步更新隐藏画板画笔颜色，白色背景: $isWhiteCanvas")
        }
        updateBrushButtonHighlight()
    }
    
    private fun startAutoConnect() {
        lifecycleScope.launch {
            drawingSocketManager.startAutoConnect()
        }
    }

    private fun startBluetoothAutoConnectConfigured() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val lastMac = prefs.getString(KEY_LAST_BT_MAC, null)
        bluetoothManager.setPreferredDeviceAddress(lastMac)

        bluetoothManager.onRfcommConnected = { device ->
            try {
                prefs.edit().putString(KEY_LAST_BT_MAC, device.address.uppercase()).apply()
                Log.d("ClientActivity", "saved last BT peer MAC: ${device.address}")
            } catch (_: Exception) {}
        }

        bluetoothManager.onImageReceived = { imageData ->
            updateImage(imageData)
            forceBackgroundDetection()
        }
        bluetoothManager.startSearch()
    }
    
    // 保存成功连接的IP和端口
    private fun saveLastConnectedAddress(ip: String, port: Int) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_LAST_CONNECTED_IP, ip)
            .putInt(KEY_LAST_CONNECTED_PORT, port)
            .apply()
        Log.d("ClientActivity", "已保存上次连接地址: $ip:$port")
    }
    
    // 获取上次连接的IP和端口
    private fun getLastConnectedAddress(): Pair<String, Int>? {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val ip = prefs.getString(KEY_LAST_CONNECTED_IP, null)
        val port = prefs.getInt(KEY_LAST_CONNECTED_PORT, -1)
        
        return if (ip != null && port != -1) {
            Pair(ip, port)
        } else {
            null
        }
    }
    
    // 保存笔刷类型
    private fun saveBrushType(brushType: BrushType) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_LAST_BRUSH_TYPE, brushType.name)
            .apply()
        Log.d("ClientActivity", "已保存笔刷类型: $brushType")
    }
    
    // 获取上次的笔刷类型
    private fun getLastBrushType(): BrushType {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val brushTypeName = prefs.getString(KEY_LAST_BRUSH_TYPE, BrushType.NORMAL.name)
        return try {
            BrushType.valueOf(brushTypeName ?: BrushType.NORMAL.name)
        } catch (e: IllegalArgumentException) {
            BrushType.NORMAL
        }
    }
    
    // 保存背景颜色
    private fun saveBackgroundColor(isWhite: Boolean) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_LAST_BACKGROUND_COLOR, isWhite)
            .apply()
        Log.d("ClientActivity", "已保存背景颜色: ${if (isWhite) "白色" else "黑色"}")
    }
    
    // 获取上次的背景颜色
    private fun getLastBackgroundColor(): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return prefs.getBoolean(KEY_LAST_BACKGROUND_COLOR, false) // 默认黑色背景
    }
    
    // Swap mode 运行时切换（第一个点和第二个点同时按住3秒）
    private fun isSwapModeEnabled(): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return prefs.getBoolean(KEY_SWAP_MODE, DEFAULT_SWAP_MODE)
    }
    
    private fun setSwapModeEnabled(enabled: Boolean) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(KEY_SWAP_MODE, enabled)
            .apply()
    }
    
    // 将位图保存到剪贴板
    private fun saveBitmapToClipboard(bitmap: Bitmap) {
        try {
            val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            
            // 方法1：尝试使用ContentProvider方式（推荐）
            try {
                // 将位图保存为临时文件到应用内部存储
                val tempFile = File(cacheDir, "clipboard_temp_${System.currentTimeMillis()}.png")
                val outputStream = FileOutputStream(tempFile)
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                outputStream.close()
                
                // 保存文件引用供后续读取
                lastClipboardTempFile = tempFile
                
                // 使用FileProvider创建URI（如果配置了的话）
                val uri = Uri.fromFile(tempFile)
                val clipData = ClipData.newUri(contentResolver, "书写内容", uri)
                clipboardManager.setPrimaryClip(clipData)
                
                Log.d("ClientActivity", "第4张书写内容已保存到剪贴板（文件方式），文件大小: ${tempFile.length()} bytes，路径: ${tempFile.absolutePath}")
            } catch (e: Exception) {
                Log.w("ClientActivity", "文件方式保存失败，尝试文本方式: ${e.message}")
                
                // 方法2：保存为文本描述（备用方案）
                val clipData = ClipData.newPlainText("书写内容", "第4张书写内容已保存（${bitmap.width}x${bitmap.height}像素）")
                clipboardManager.setPrimaryClip(clipData)
                
                Log.d("ClientActivity", "第4张书写内容描述已保存到剪贴板（文本方式）")
            }
            
            // 显示提示信息
            runOnUiThread {
                // Toast弹窗已移除
                Log.d("ClientActivity", "第4张书写内容已保存到剪贴板")
            }
        } catch (e: Exception) {
            Log.e("ClientActivity", "保存到剪贴板完全失败: ${e.message}")
            runOnUiThread {
                // Toast弹窗已移除
                Log.d("ClientActivity", "保存到剪贴板失败")
            }
        }
    }
    
    // 从剪贴板读取位图
    private fun getBitmapFromClipboard(): Bitmap? {
        try {
            // 方法1：优先使用保存的临时文件引用
            lastClipboardTempFile?.let { tempFile ->
                if (tempFile.exists()) {
                    try {
                        val bitmap = BitmapFactory.decodeFile(tempFile.absolutePath)
                        if (bitmap != null) {
                            Log.d("ClientActivity", "成功从临时文件引用读取图片: ${bitmap.width}x${bitmap.height}")
                            return@getBitmapFromClipboard bitmap
                        }
                    } catch (e: Exception) {
                        Log.w("ClientActivity", "从临时文件引用读取图片失败: ${e.message}")
                    }
                }
                Unit // 明确返回Unit
            }
            
            // 方法2：从剪贴板系统读取
            val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipData = clipboardManager.primaryClip
            
            if (clipData != null && clipData.itemCount > 0) {
                val item = clipData.getItemAt(0)
                
                // 尝试从URI读取图片
                item.uri?.let { uri ->
                    try {
                        val inputStream = contentResolver.openInputStream(uri)
                        val bitmap = BitmapFactory.decodeStream(inputStream)
                        inputStream?.close()
                        
                        if (bitmap != null) {
                            Log.d("ClientActivity", "成功从剪贴板URI读取图片: ${bitmap.width}x${bitmap.height}")
                            return@getBitmapFromClipboard bitmap
                        }
                    } catch (e: Exception) {
                        Log.w("ClientActivity", "从URI读取图片失败: ${e.message}")
                    }
                    Unit // 明确返回Unit，避免if表达式作为let的返回值
                }
            }
            
            // 方法3：尝试找到最新的临时文件
            try {
                val tempFiles = cacheDir.listFiles { file ->
                    file.name.startsWith("clipboard_temp_") && file.name.endsWith(".png")
                }
                
                val latestTempFile = tempFiles?.maxByOrNull { it.lastModified() }
                latestTempFile?.let { file ->
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    if (bitmap != null) {
                        Log.d("ClientActivity", "成功从最新临时文件读取图片: ${bitmap.width}x${bitmap.height}")
                        return@getBitmapFromClipboard bitmap
                    }
                    Unit // 明确返回Unit
                }
            } catch (e: Exception) {
                Log.w("ClientActivity", "从临时文件目录读取图片失败: ${e.message}")
            }
            
            Log.w("ClientActivity", "剪贴板中没有找到可用的图片数据")
            return null
        } catch (e: Exception) {
            Log.e("ClientActivity", "从剪贴板读取图片失败: ${e.message}")
            return null
        }
    }
    
    // 自动从剪贴板输入图片
    private fun autoInputFromClipboard() {
        Thread {
            try {
                Log.d("ClientActivity", "开始自动从剪贴板读取图片")
                
                // 短暂延迟，确保UI更新完成
                Thread.sleep(500)
                
                val clipboardBitmap = getBitmapFromClipboard()
                if (clipboardBitmap != null) {
                    Log.d("ClientActivity", "成功从剪贴板读取图片，准备自动添加为第6张图片")
                    
                    // 在主线程中执行添加操作
                    runOnUiThread {
                        try {
                            // 直接调用displayWritingFullscreen来添加这个图片
                            // 但需要标记这是自动输入，避免无限循环
                            displayWritingFromClipboard(clipboardBitmap)
                        } catch (e: Exception) {
                            Log.e("ClientActivity", "自动添加剪贴板图片时出错: ${e.message}")
                        }
                    }
                } else {
                    Log.w("ClientActivity", "无法从剪贴板读取图片，跳过自动输入")
                }
            } catch (e: Exception) {
                Log.e("ClientActivity", "自动从剪贴板输入图片失败: ${e.message}")
            }
        }.start()
    }
    
    // 从剪贴板显示书写内容（避免无限循环的特殊版本）
    private fun displayWritingFromClipboard(bitmap: Bitmap) {
        try {
            Log.d("ClientActivity", "从剪贴板添加书写内容，尺寸: ${bitmap.width} x ${bitmap.height}")
            
            // 检查位图是否有效
            if (bitmap.isRecycled) {
                Log.w("ClientActivity", "剪贴板位图已被回收，跳过显示")
                return
            }
            
            // 裁剪掉空白区域，获得紧凑的内容
            val croppedBitmap = cropContentBounds(bitmap)
            Log.d("ClientActivity", "剪贴板图片裁剪后尺寸: ${croppedBitmap.width} x ${croppedBitmap.height}")
            
            // 将裁剪后的书写内容添加到列表（不触发剪贴板保存）
            writingContentList.add(croppedBitmap)
            
            // 清空剪贴板临时文件引用，避免再次触发自动输入
            lastClipboardTempFile = null
            
            // 如果超过最大数量，移除最旧的内容
            while (writingContentList.size > maxWritingCount) {
                val oldBitmap = writingContentList.removeAt(0)
                if (oldBitmap != croppedBitmap) { // 避免回收当前位图
                    oldBitmap.recycle()
                }
            }
            
            // 异步创建堆叠显示的位图
            Thread {
                try {
                    val stackedBitmap = createStackedWritingBitmap()
                    
                    // 回到主线程更新UI
                    runOnUiThread {
                        try {
                            writingDisplayView.setImageBitmap(stackedBitmap)
                            writingDisplayContainer.visibility = View.VISIBLE
                            writingDisplayView.requestLayout()
                            writingDisplayView.post { writingDisplayView.invalidate() }
                            Log.d("ClientActivity", "剪贴板图片添加成功，当前显示${writingContentList.size}个内容")
                            
                            // 显示提示信息
                            // Toast弹窗已移除
                            Log.d("ClientActivity", "已自动添加剪贴板中的第4张图片")
                        } catch (e: Exception) {
                            Log.e("ClientActivity", "更新剪贴板图片UI时出错: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ClientActivity", "创建剪贴板图片堆叠位图时出错: ${e.message}")
                }
            }.start()
            
        } catch (e: Exception) {
            Log.e("ClientActivity", "显示剪贴板书写内容时出错: ${e.message}")
        }
    }
    
    fun clearScreen() {
        // 清除前先检测屏幕上四个角落的颜色来判断背景色
        var detectedBackgroundColor = if (isWhiteCanvas) Color.WHITE else Color.BLACK
        var detectedIsWhite = isWhiteCanvas
        
        // 从bitmapCache检测四个角落的颜色
        bitmapCache?.let { cache ->
            try {
                Log.d("ClientActivity", "开始检测屏幕四个角落的颜色，位图尺寸: ${cache.width}x${cache.height}")
                
                // 检测四个角落
                val topLeftPixel = cache.getPixel(0, 0)
                val topRightPixel = cache.getPixel(cache.width - 1, 0)
                val bottomLeftPixel = cache.getPixel(0, cache.height - 1)
                val bottomRightPixel = cache.getPixel(cache.width - 1, cache.height - 1)
                
                // 判断每个角落是白色还是黑色
                val topLeftWhite = isPixelWhite(topLeftPixel)
                val topRightWhite = isPixelWhite(topRightPixel)
                val bottomLeftWhite = isPixelWhite(bottomLeftPixel)
                val bottomRightWhite = isPixelWhite(bottomRightPixel)
                
                val whiteCornerCount = listOf(
                    topLeftWhite,
                    topRightWhite,
                    bottomLeftWhite,
                    bottomRightWhite
                ).count { it }
                
                // 如果至少有一个角落是白色，就认为是白色画布；如果所有角落都是黑色，则是黑色画布
                detectedIsWhite = whiteCornerCount > 0
                detectedBackgroundColor = if (detectedIsWhite) Color.WHITE else Color.BLACK
                
                // 详细日志
                Log.d("ClientActivity", "=== 四个角落颜色检测 ===")
                Log.d("ClientActivity", "左上角(0,0): RGB(${Color.red(topLeftPixel)},${Color.green(topLeftPixel)},${Color.blue(topLeftPixel)}) -> ${if (topLeftWhite) "白色" else "黑色"}")
                Log.d("ClientActivity", "右上角(${cache.width - 1},0): RGB(${Color.red(topRightPixel)},${Color.green(topRightPixel)},${Color.blue(topRightPixel)}) -> ${if (topRightWhite) "白色" else "黑色"}")
                Log.d("ClientActivity", "左下角(0,${cache.height - 1}): RGB(${Color.red(bottomLeftPixel)},${Color.green(bottomLeftPixel)},${Color.blue(bottomLeftPixel)}) -> ${if (bottomLeftWhite) "白色" else "黑色"}")
                Log.d("ClientActivity", "右下角(${cache.width - 1},${cache.height - 1}): RGB(${Color.red(bottomRightPixel)},${Color.green(bottomRightPixel)},${Color.blue(bottomRightPixel)}) -> ${if (bottomRightWhite) "白色" else "黑色"}")
                Log.d("ClientActivity", "白色角落数量: $whiteCornerCount/4, 检测结果: ${if (detectedIsWhite) "白色画布" else "黑色画布"}")
                
            } catch (e: Exception) {
                Log.e("ClientActivity", "清除时四个角落颜色检测失败: ${e.message}", e)
                // 检测失败时使用当前isWhiteCanvas的值
            }
        } ?: run {
            Log.d("ClientActivity", "清除时bitmapCache为null，使用当前isWhiteCanvas值: ${if (isWhiteCanvas) "白色" else "黑色"}")
        }
        
        // 更新背景色状态
        if (detectedIsWhite != isWhiteCanvas) {
            Log.d("ClientActivity", "检测到背景色变化: ${if (isWhiteCanvas) "白色" else "黑色"} -> ${if (detectedIsWhite) "白色" else "黑色"}")
            isWhiteCanvas = detectedIsWhite
            saveBackgroundColor(isWhiteCanvas)
        } else {
            Log.d("ClientActivity", "背景色未变化，保持: ${if (isWhiteCanvas) "白色" else "黑色"}")
        }
        
        // 使用检测到的背景色清除屏幕
        val backgroundColor = detectedBackgroundColor
        
        val emptyBitmap = try {
            Bitmap.createBitmap(
                imageView.width,
                imageView.height,
                Bitmap.Config.ARGB_8888
            )
        } catch (e: OutOfMemoryError) {
            Log.e("ClientActivity", "清除屏幕时创建emptyBitmap内存不足: ${e.message}")
            return
        }
        
        val canvas = Canvas(emptyBitmap)
        canvas.drawColor(backgroundColor)
        
        currentBitmap = emptyBitmap
        bitmapCache?.recycle()
        try {
            bitmapCache = emptyBitmap.copy(Bitmap.Config.ARGB_8888, true)
        } catch (e: OutOfMemoryError) {
            Log.e("ClientActivity", "清除屏幕时复制emptyBitmap内存不足，使用原始位图: ${e.message}")
            bitmapCache = emptyBitmap
        }
        // 重置 markerCanvas，确保下次写字时绑定到新的 bitmapCache
        markerCanvas = null
        currentMarkerBitmap = null
        // 更新坐标变换缓存（bitmapCache 已换成新的空白位图）
        imageView.post { updateMarkerTransform() }

        // 更新所有视图层次的背景色
        window.decorView.setBackgroundColor(backgroundColor)
        rootLayout.setBackgroundColor(backgroundColor)
        imageView.setBackgroundColor(backgroundColor)
        // 必须显示 bitmapCache（可写副本），而不是 emptyBitmap（原始只读副本）
        imageView.setImageBitmap(bitmapCache)
        
        // 同时清除隐藏画板的全屏显示和状态
        clearAllWritingContent()
        
        // 确保书写按钮使用正确的颜色
        updateHiddenDrawingPenColor()
        
        if (isHiddenFeatureEnabled) {
            setWritingFeatureSidebarsVisible(true)
        }
        
        Log.d("ClientActivity", "清除屏幕完成，当前背景色: ${if (isWhiteCanvas) "白色" else "黑色"}")
    }
    
    fun clearAllWritingContent() {
        if (isHiddenFeatureEnabled) {
            writingDisplayContainer.visibility = View.GONE
            hiddenDrawingView.clearAllSessions()
            
            // 清除书写内容列表
            writingContentList.forEach { it.recycle() }
            writingContentList.clear()
            
            setWritingFeatureSidebarsVisible(true)
            
            // 取消任何待处理的延迟任务
            undoDelayedRunnable?.let { handler.removeCallbacks(it) }
            undoDelayedRunnable = null
            
            Log.d("ClientActivity", "清除所有手写内容和状态，保存/笔刷条保持可见")
        }
    }
    
    // checkCombinationClick方法已移除，因为重连功能已整合到组合按钮中
    
    // 测试方法
    fun testMethod() {
        Log.e("ClientActivity", "!!! 测试方法被调用成功 !!!")
    }
    
    // 强制背景色检测方法
    fun forceBackgroundDetection() {
        Log.e("ClientActivity", "!!! 强制背景色检测被调用 !!!")
        try {
            bitmapCache?.let { bitmap ->
                Log.d("ClientActivity", "开始强制背景色检测，位图尺寸: ${bitmap.width}x${bitmap.height}")
                
                // 检查多个点的颜色来判断背景色
                val centerPixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                val topLeftPixel = bitmap.getPixel(0, 0)
                val topRightPixel = bitmap.getPixel(bitmap.width - 1, 0)
                val bottomLeftPixel = bitmap.getPixel(0, bitmap.height - 1)
                val bottomRightPixel = bitmap.getPixel(bitmap.width - 1, bitmap.height - 1)
                
                val whiteCount = listOf(
                    isPixelWhite(centerPixel),
                    isPixelWhite(topLeftPixel),
                    isPixelWhite(topRightPixel),
                    isPixelWhite(bottomLeftPixel),
                    isPixelWhite(bottomRightPixel)
                ).count { it }
                
                val wasWhiteCanvas = isWhiteCanvas
                isWhiteCanvas = whiteCount >= 4
                
                Log.d("ClientActivity", "强制背景色检测 - 白色像素数量: $whiteCount/5, 检测结果: ${if (isWhiteCanvas) "白色" else "黑色"}")
                Log.d("ClientActivity", "像素颜色检测 - 中心:${Color.red(centerPixel)},${Color.green(centerPixel)},${Color.blue(centerPixel)} (${if (isPixelWhite(centerPixel)) "白" else "黑"})")
                
                if (wasWhiteCanvas != isWhiteCanvas) {
                    Log.d("ClientActivity", "背景色发生变化: ${if (wasWhiteCanvas) "白色" else "黑色"} -> ${if (isWhiteCanvas) "白色" else "黑色"}")
                    // 保存背景颜色设置
                    saveBackgroundColor(isWhiteCanvas)
                    
                    // 背景色发生变化时，同步更新隐藏画板的画笔颜色
                    updateHiddenDrawingPenColor()
                } else {
                    Log.d("ClientActivity", "背景色未变化，保持: ${if (isWhiteCanvas) "白色" else "黑色"}")
                }
            } ?: Log.w("ClientActivity", "无法进行背景色检测：bitmapCache为null")
        } catch (e: Exception) {
            Log.e("ClientActivity", "强制背景色检测异常: ${e.message}", e)
        }
    }
    
    private fun imagePayloadFormatHint(data: ByteArray): String = when {
        data.size >= 3 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() -> "JPEG"
        data.size >= 4 && data[0] == 0x89.toByte() && data[1] == 'P'.code.toByte() -> "PNG"
        else -> "unknown"
    }

    /** @return decoded bitmap, or null if decode failed (not OOM — decodeByteArray swallows that into null) */
    private fun decodeReceivedImage(imageData: ByteArray, sampleSize: Int): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
            inSampleSize = sampleSize.coerceAtLeast(1)
        }
        return try {
            BitmapFactory.decodeByteArray(imageData, 0, imageData.size, options)
        } catch (e: OutOfMemoryError) {
            Log.w("ClientActivity", "decode OOM (inSampleSize=$sampleSize): ${e.message}")
            null
        }
    }

    /** 优先全分辨率解码；仅在 OOM 时降级 half-res（旧逻辑在内存>70%就 half-res，导致时清时糊） */
    private fun decodeReceivedImagePreferFull(imageData: ByteArray): Pair<Bitmap?, Int> {
        decodeReceivedImage(imageData, 1)?.let { return it to 1 }
        Log.w("ClientActivity", "全分辨率 decode 失败，清理位图缓存后重试")
        clearBitmapCache()
        System.gc()
        System.runFinalization()
        decodeReceivedImage(imageData, 1)?.let { return it to 1 }
        Log.e("ClientActivity", "全分辨率仍失败，降级 inSampleSize=2（画面可能发糊）")
        return (decodeReceivedImage(imageData, 2) to 2)
    }

    fun updateImage(imageData: ByteArray) {
        Log.e("ClientActivity", "!!! updateImage方法被调用 !!!")
        Log.d("ClientActivity", "=== updateImage方法开始执行 ===")
        Log.d(
            "ClientActivity",
            "updateImage: ${imageData.size} bytes, 格式≈${imagePayloadFormatHint(imageData)}（客户端不再二次压缩，仅 decode）"
        )
        try {
            val memoryUsage = getMemoryUsage()
            if (memoryUsage > 0.75f) {
                Log.w(
                    "ClientActivity",
                    "接收图片前内存 ${(memoryUsage * 100).toInt()}%，清理旧缓存（仍全分辨率 decode）"
                )
                clearBitmapCache()
                System.gc()
            }

            val (originalBitmap, sampleUsed) = decodeReceivedImagePreferFull(imageData)
            if (originalBitmap == null) {
                Log.e("ClientActivity", "位图解码失败：originalBitmap为null")
                return
            }
            Log.d(
                "ClientActivity",
                "位图解码成功: ${originalBitmap.width}x${originalBitmap.height}, inSampleSize=$sampleUsed"
            )
            if (sampleUsed > 1) {
                Log.w("ClientActivity", "当前帧使用了降采样 decode，墨水屏可能偏糊")
            }
            
            // 立即进行背景色检测，不依赖ImageView尺寸
            try {
                // 检查多个点的颜色来判断背景色（包括中心和四个角落）
                val centerPixel = originalBitmap.getPixel(originalBitmap.width / 2, originalBitmap.height / 2)
                val topLeftPixel = originalBitmap.getPixel(0, 0)
                val topRightPixel = originalBitmap.getPixel(originalBitmap.width - 1, 0)
                val bottomLeftPixel = originalBitmap.getPixel(0, originalBitmap.height - 1)
                val bottomRightPixel = originalBitmap.getPixel(originalBitmap.width - 1, originalBitmap.height - 1)
                
                // 添加更多采样点，提高检测准确性
                val midLeftPixel = originalBitmap.getPixel(0, originalBitmap.height / 2)
                val midRightPixel = originalBitmap.getPixel(originalBitmap.width - 1, originalBitmap.height / 2)
                val midTopPixel = originalBitmap.getPixel(originalBitmap.width / 2, 0)
                val midBottomPixel = originalBitmap.getPixel(originalBitmap.width / 2, originalBitmap.height - 1)
                
                val whiteCount = listOf(
                    isPixelWhite(centerPixel),
                    isPixelWhite(topLeftPixel),
                    isPixelWhite(topRightPixel),
                    isPixelWhite(bottomLeftPixel),
                    isPixelWhite(bottomRightPixel),
                    isPixelWhite(midLeftPixel),
                    isPixelWhite(midRightPixel),
                    isPixelWhite(midTopPixel),
                    isPixelWhite(midBottomPixel)
                ).count { it }
                
                val wasWhiteCanvas = isWhiteCanvas
                // 使用9个采样点，需要至少4个点是白色才判断为白色背景（降低误判，但允许边缘有少量内容）
                isWhiteCanvas = whiteCount >= 4
                
                // 添加详细的调试日志，显示所有采样点的颜色值
                Log.d("ClientActivity", "=== 背景色检测详情 ===")
                Log.d("ClientActivity", "位图尺寸: ${originalBitmap.width}x${originalBitmap.height}")
                Log.d("ClientActivity", "中心点(${originalBitmap.width / 2},${originalBitmap.height / 2}): RGB(${Color.red(centerPixel)},${Color.green(centerPixel)},${Color.blue(centerPixel)}) -> ${if (isPixelWhite(centerPixel)) "白色" else "黑色"}")
                Log.d("ClientActivity", "左上角(0,0): RGB(${Color.red(topLeftPixel)},${Color.green(topLeftPixel)},${Color.blue(topLeftPixel)}) -> ${if (isPixelWhite(topLeftPixel)) "白色" else "黑色"}")
                Log.d("ClientActivity", "右上角(${originalBitmap.width - 1},0): RGB(${Color.red(topRightPixel)},${Color.green(topRightPixel)},${Color.blue(topRightPixel)}) -> ${if (isPixelWhite(topRightPixel)) "白色" else "黑色"}")
                Log.d("ClientActivity", "左下角(0,${originalBitmap.height - 1}): RGB(${Color.red(bottomLeftPixel)},${Color.green(bottomLeftPixel)},${Color.blue(bottomLeftPixel)}) -> ${if (isPixelWhite(bottomLeftPixel)) "白色" else "黑色"}")
                Log.d("ClientActivity", "右下角(${originalBitmap.width - 1},${originalBitmap.height - 1}): RGB(${Color.red(bottomRightPixel)},${Color.green(bottomRightPixel)},${Color.blue(bottomRightPixel)}) -> ${if (isPixelWhite(bottomRightPixel)) "白色" else "黑色"}")
                Log.d("ClientActivity", "白色像素数量: $whiteCount/9, 检测结果: ${if (isWhiteCanvas) "白色" else "黑色"}")
                Log.d("ClientActivity", "检测前状态: ${if (wasWhiteCanvas) "白色" else "黑色"}, 检测后状态: ${if (isWhiteCanvas) "白色" else "黑色"}")
                
                if (wasWhiteCanvas != isWhiteCanvas) {
                    Log.d("ClientActivity", "背景色发生变化: ${if (wasWhiteCanvas) "白色" else "黑色"} -> ${if (isWhiteCanvas) "白色" else "黑色"}")
                    // 保存背景颜色设置
                    saveBackgroundColor(isWhiteCanvas)
                    
                    clearBitmapCache()
                    // 背景色发生变化时，同步更新隐藏画板的画笔颜色
                    updateHiddenDrawingPenColor()
                } else {
                    Log.d("ClientActivity", "背景色未变化，保持: ${if (isWhiteCanvas) "白色" else "黑色"}")
                    // 即使背景色没有变化，也要确保更新隐藏画板的画笔颜色，以防之前的更新失败
                    updateHiddenDrawingPenColor()
                }
                
                // 更新背景色
                val backgroundColor = if (isWhiteCanvas) Color.WHITE else Color.BLACK
                
                // 在主线程更新 UI
                val finalBitmap = originalBitmap
                imageView.post {
                    updateBackgroundColors(backgroundColor)
                    manageBitmapCache(finalBitmap)
                    imageView.setImageBitmap(bitmapCache)
                    updateMarkerTransform()   // bitmapCache 已更新，刷新坐标变换缓存
                    
                    // 设置触摸监听器（如果还没有设置）
                    if (!touchListenerSet) {
                        setupTouchListener()
                        touchListenerSet = true
                        Log.d("ClientActivity", "手指触摸擦除功能已启用")
                    }
                }
            } catch (e: IllegalArgumentException) {
                Log.e("ClientActivity", "Error accessing pixels: ${e.message}", e)
            } catch (e: Exception) {
                Log.e("ClientActivity", "背景色检测异常: ${e.message}", e)
            }
        } catch (e: OutOfMemoryError) {
            Log.e("ClientActivity", "接收图片时 OOM: ${e.message}，已清理缓存")
            clearBitmapCache()
            System.gc()
            System.runFinalization()
        } catch (e: Exception) {
            Log.e("ClientActivity", "Error updating image: ${e.message}")
            Log.e("ClientActivity", "Exception details: ", e)
        }
    }

    private fun isPixelWhite(pixel: Int): Boolean {
        try {
            val red = Color.red(pixel)
            val green = Color.green(pixel)
            val blue = Color.blue(pixel)
            val alpha = Color.alpha(pixel)
            
            // 放宽白色判断标准，更容易检测到白色背景
            // 方法1：纯白色或接近白色（RGB值都比较高）
            val isHighValue = red > 180 && green > 180 && blue > 180
            
            // 方法2：允许一定的颜色偏差，但RGB值应该比较接近（灰度色调）
            val maxComponent = maxOf(red, green, blue)
            val minComponent = minOf(red, green, blue)
            val isGrayish = (maxComponent - minComponent) < 50  // RGB差异小于50，说明是灰度色
            
            // 方法3：亮度检测（使用标准亮度公式）
            val brightness = (0.299 * red + 0.587 * green + 0.114 * blue)
            val isBright = brightness > 200
            
            // 综合考虑：高RGB值 + 灰度色调 + 高亮度
            val result = isHighValue && isGrayish && isBright
            
            // 只在详细日志模式下输出每个像素的检测信息（避免日志过多）
            if (red > 150 || green > 150 || blue > 150) {  // 只记录可能接近白色的像素
                Log.d("ClientActivity", "像素检测: RGB($red,$green,$blue) A=$alpha 亮度=${brightness.toInt()} -> ${if (result) "白色" else "非白色"} (高值=$isHighValue,灰度=$isGrayish,高亮=$isBright)")
            }
            
            return result
        } catch (e: Exception) {
            Log.e("ClientActivity", "Error checking pixel color: ${e.message}")
            return false
        }
    }
    
    /** 悬停：logcat 过滤 tag StylusHover；无 HOVER_* 时可看 TOUCH_MOVE p≈0 */
    private fun logStylusHoverDiagnostic(event: MotionEvent) {
        val masked = event.actionMasked
        if (masked != MotionEvent.ACTION_HOVER_MOVE &&
            masked != MotionEvent.ACTION_HOVER_ENTER &&
            masked != MotionEvent.ACTION_HOVER_EXIT
        ) {
            return
        }
        if (event.pointerCount <= 0) return
        val pi = 0
        val now = System.currentTimeMillis()
        if (masked == MotionEvent.ACTION_HOVER_MOVE) {
            if (now - lastStylusHoverMoveLogMs < 150L) return
            lastStylusHoverMoveLogMs = now
        }
        val x = event.getX(pi)
        val y = event.getY(pi)
        val tool = try {
            event.getToolType(pi)
        } catch (_: Exception) {
            MotionEvent.TOOL_TYPE_UNKNOWN
        }
        val pressure = try {
            event.getPressure(pi)
        } catch (_: Exception) {
            event.pressure
        }
        val dist = runCatching { event.getAxisValue(MotionEvent.AXIS_DISTANCE, pi) }.getOrElse { Float.NaN }
        val actionName = when (masked) {
            MotionEvent.ACTION_HOVER_MOVE -> "HOVER_MOVE"
            MotionEvent.ACTION_HOVER_ENTER -> "HOVER_ENTER"
            MotionEvent.ACTION_HOVER_EXIT -> "HOVER_EXIT"
            else -> "?"
        }
        Log.i(
            "StylusHover",
            "$actionName xy=(${x.toInt()},${y.toInt()}) tool=$tool p=$pressure dist=$dist " +
                "src=0x${Integer.toHexString(event.source)} devId=${event.deviceId}"
        )
    }

    /** StylusDiag：一眼看出是手指还是电磁笔、哪台设备、压力是否正常 */
    private fun motionEventToolDiag(event: MotionEvent, pointerIndex: Int): String {
        val tt = event.getToolType(pointerIndex)
        val ttName = when (tt) {
            MotionEvent.TOOL_TYPE_UNKNOWN -> "UNKNOWN"
            MotionEvent.TOOL_TYPE_FINGER -> "FINGER"
            MotionEvent.TOOL_TYPE_STYLUS -> "STYLUS"
            MotionEvent.TOOL_TYPE_ERASER -> "ERASER"
            MotionEvent.TOOL_TYPE_MOUSE -> "MOUSE"
            else -> "OTHER($tt)"
        }
        val pressure = try {
            event.getPressure(pointerIndex)
        } catch (_: Exception) {
            -1f
        }
        val size = try {
            event.getSize(pointerIndex)
        } catch (_: Exception) {
            -1f
        }
        val dev = try {
            InputDevice.getDevice(event.deviceId)
        } catch (_: Exception) {
            null
        }
        val devLabel = dev?.let { d ->
            buildString {
                append(d.name?.replace("\"", "'") ?: "?")
                append(" sources=0x${Integer.toHexString(d.sources)}")
                d.vendorId.takeIf { it != 0 }?.let { append(" vid=0x${it.toString(16)}") }
                d.productId.takeIf { it != 0 }?.let { append(" pid=0x${it.toString(16)}") }
            }
        } ?: "no_device"
        return "tool=$ttName pressure=$pressure size=$size ptr=${event.getPointerId(pointerIndex)} devId=${event.deviceId} \"$devLabel\""
    }

    /** 物理屏像素（含系统栏区域），用于裁剪 DHW 矩形，避免 ioctl Bad address */
    private fun getRealScreenSizePx(): Pair<Int, Int> {
        val p = Point()
        try {
            windowManager.defaultDisplay.getRealSize(p)
        } catch (_: Exception) {
            val dm = DisplayMetrics()
            windowManager.defaultDisplay.getMetrics(dm)
            p.set(dm.widthPixels, dm.heightPixels)
        }
        return p.x to p.y
    }

    /** 合并同一帧内多次 layout/post 的注册，减少对驱动的重复 ioctl */
    private fun scheduleApplyDhwArea(delayMs: Long = 0) {
        imageView.removeCallbacks(dhwApplyRunnable)
        if (delayMs > 0) {
            imageView.postDelayed(dhwApplyRunnable, delayMs)
        } else {
            imageView.post(dhwApplyRunnable)
        }
    }

    private fun applyDhwAreaFromImageView() {
        scheduleApplyDhwArea(0)
    }

    private fun applyDhwAreaFromImageViewWork() {
        val util = sonyDhw ?: return
        if (imageView.width <= 0 || imageView.height <= 0) {
            Log.d("StylusDiag", "applyDhwArea SKIP imageView=${imageView.width}x${imageView.height}")
            return
        }
        val (realW, realH) = getRealScreenSizePx()
        val loc = IntArray(2)
        imageView.getLocationOnScreen(loc)
        var sl = loc[0]
        var st = loc[1]
        var sr = sl + imageView.width
        var sb = st + imageView.height
        sl = sl.coerceIn(0, (realW - 1).coerceAtLeast(0))
        st = st.coerceIn(0, (realH - 1).coerceAtLeast(0))
        sr = sr.coerceIn(sl + 1, realW)
        sb = sb.coerceIn(st + 1, realH)
        val pen = markerStrokeWidth.toInt()

        // DPT-RP1 上 portrait=true 常先 ioctl Bad address，再 false 才成功；失败一次可能短暂污染驱动态，故优先 false
        fun tryAreas(tag: String): Boolean {
            util.addDhwArea(sl, st, sr, sb, pen, portrait = false)
            if (util.isDhwAreaReady()) {
                Log.d("StylusDiag", "DHW OK ($tag) portrait=false rect=($sl,$st,$sr,$sb) real=${realW}x${realH}")
                return true
            }
            util.addDhwArea(sl, st, sr, sb, pen, portrait = true)
            if (util.isDhwAreaReady()) {
                Log.d("StylusDiag", "DHW OK ($tag) portrait=true rect=($sl,$st,$sr,$sb)")
                return true
            }
            return false
        }

        Log.d(
            "ClientActivity",
            "DHW 尝试: rawOnScreen=(${loc[0]},${loc[1]})+${imageView.width}x${imageView.height} → " +
                "clip=($sl,$st,$sr,$sb) real=${realW}x${realH}"
        )

        var ok = tryAreas("viewClip")
        if (!ok && realW > 0 && realH > 0) {
            util.addDhwArea(0, 0, realW, realH, pen, portrait = false)
            ok = util.isDhwAreaReady()
            if (!ok) {
                util.addDhwArea(0, 0, realW, realH, pen, portrait = true)
                ok = util.isDhwAreaReady()
            }
            if (ok) Log.d("StylusDiag", "DHW OK fullscreen 0,0 ${realW}x${realH}")
        }

        if (ok) {
            // 区域已就绪。DHW state 由每笔 ACTION_DOWN/UP 切换，这里保持 off（原装逻辑）
            util.setDhwState(false)
            dhwAreaRegisterRetryCount = 0
            Log.d("StylusDiag", "DHW 区域就绪，state 保持 off，等待 ACTION_DOWN 再开")
        } else {
            util.setDhwState(false)
            Log.w(
                "StylusDiag",
                "DHW 仍失败（view 与全屏均试过 portrait T/F），setDhwState(false)，走软件刷新"
            )
            if (dhwAreaRegisterRetryCount < 3) {
                dhwAreaRegisterRetryCount++
                scheduleApplyDhwArea(400)
            }
        }
    }

    private fun ensureStylusKeepaliveRunnable(): Runnable {
        if (stylusKeepaliveRunnable == null) {
            stylusKeepaliveRunnable = Runnable {
                if (isFinishing) return@Runnable
                if (!::imageView.isInitialized) return@Runnable
                val t = System.currentTimeMillis()
                if (t >= stylusSessionUntil) return@Runnable
                try {
                    imageView.removeCallbacks(dhwApplyRunnable)
                    applyDhwAreaFromImageViewWork()
                    imageView.invalidate()
                } catch (e: Exception) {
                    Log.w("ClientActivity", "stylusKeepalive: ${e.message}")
                }
                if (System.currentTimeMillis() < stylusSessionUntil) {
                    stylusKeepaliveRunnable?.let { r -> handler.postDelayed(r, stylusKeepaliveIntervalMs) }
                }
            }
        }
        return stylusKeepaliveRunnable!!
    }

    private fun ensureStylusKeepaliveScheduled() {
        val r = ensureStylusKeepaliveRunnable()
        handler.removeCallbacks(r)
        handler.postDelayed(r, stylusKeepaliveIntervalMs)
    }

    private fun cancelStylusKeepalive() {
        stylusKeepaliveRunnable?.let { handler.removeCallbacks(it) }
    }

    /** 笔 DOWN/UP 或节流后的 MOVE：延长会话并排队周期性轻量维持（不重开 DHW squiggle） */
    private fun refreshStylusSession(fromMove: Boolean = false) {
        val now = System.currentTimeMillis()
        if (fromMove && now - lastStylusSessionBumpAt < 2000L) return
        lastStylusSessionBumpAt = now
        stylusSessionUntil = now + stylusSessionDurationMs
        ensureStylusKeepaliveScheduled()
    }

    private fun setupTouchListener() {
        if (!imageViewMarkerLayoutHooked) {
            imageViewMarkerLayoutHooked = true
            imageView.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                val nw = right - left
                val nh = bottom - top
                if (nw <= 0 || nh <= 0) return@addOnLayoutChangeListener
                val ow = oldRight - oldLeft
                val oh = oldBottom - oldTop
                if (nw == ow && nh == oh && markerCanvas != null) return@addOnLayoutChangeListener
                updateMarkerTransform()
                scheduleApplyDhwArea(0)
            }
        }
        initSonyFastInvalidate()
        sonyDhw = SonySystemUtil.getInstance()
        updateMarkerTransform()
        imageView.post {
            updateMarkerTransform()
            scheduleApplyDhwArea(0)
        }

        imageView.setOnTouchListener { view, event ->
            val actionIndex = event.actionIndex.coerceIn(0, event.pointerCount - 1)
            val toolType = event.getToolType(actionIndex)
            val isFingerInput = toolType == MotionEvent.TOOL_TYPE_FINGER

            if (event.actionMasked == MotionEvent.ACTION_DOWN && isFingerInput) {
                Log.i(
                    "StylusDiag",
                    "FINGER_DOWN(主画布走擦除) ${motionEventToolDiag(event, actionIndex)}"
                )
            }

            if (!isFingerInput) {
                // 必须用当前 pointer 的坐标：getHistoricalX(i) 等价于 pointer0 的历史，笔若不在 index0 会整段连错线/看不见
                val pi = actionIndex
                fun ex() = event.getX(pi)
                fun ey() = event.getY(pi)
                // 电磁笔书写
                // DHW 可用：ACTION_MOVE 只写 bitmapCache，由 DHW squiggle 提供实时视觉反馈
                //           ACTION_UP 关闭 DHW，做一次最终局部刷新
                // 仅 ioctl 注册成功才算 DHW 可用；sonyDhw 非空但 index=-1 时须走软件路径（否则 Bad address 后首段常丢墨）
                val dhwActive = (sonyDhw?.isDhwAreaReady() == true)
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        updateMarkerTransform()
                        val now = System.currentTimeMillis()
                        val idleMs = if (lastStylusPointerUpAt == 0L) {
                            stylusIdleWakeAfterMs
                        } else {
                            now - lastStylusPointerUpAt
                        }
                        val inStylusSession = now < stylusSessionUntil
                        if (!inStylusSession && idleMs >= stylusIdleWakeAfterMs) {
                            Log.i(
                                "StylusDiag",
                                "闲置后落笔: ${if (lastStylusPointerUpAt == 0L) "首笔/未记录抬笔" else "${idleMs}ms"} " +
                                    "→ 同步 applyDhwAreaWork + invalidate 全图（post 会晚于本帧 MOVE，导致首段在 DHW 就绪前绘制）"
                            )
                            imageView.removeCallbacks(dhwApplyRunnable)
                            applyDhwAreaFromImageViewWork()
                            imageView.invalidate()
                        }
                        refreshStylusSession(fromMove = false)
                        if (isDrawingWithStylus) {
                            applyStylusStrokeUp(lastStylusX, lastStylusY, dhwActive)
                        }
                        stylusDiagStrokeId++
                        val bc = bitmapCache
                        Log.i(
                            "StylusDiag",
                            "DOWN#$stylusDiagStrokeId xy=(${ex().toInt()},${ey().toInt()}) ptrs=${event.pointerCount} pi=$pi " +
                                "${motionEventToolDiag(event, actionIndex)} " +
                                "iv=${imageView.width}x${imageView.height} " +
                                "bc=${if (bc == null) "null" else "${bc.width}x${bc.height}"} " +
                                "markerCanvas=${markerCanvas != null} " +
                                "scale=$bitmapScale off=($bitmapOffsetX,$bitmapOffsetY) " +
                                "dhw=${sonyDhw != null} dhwReady=${sonyDhw?.isDhwAreaReady() == true} fastInv=${sonyFastInvalidateMethod != null} " +
                                "whiteBg=$isWhiteCanvas"
                        )
                        // 原装逻辑：每笔 DOWN 时开 DHW squiggle，UP 时关
                        beginStylusStrokeCore(
                            ex(), ey(), dhwActive,
                            requestUnbuffered = true,
                            unbufferedEvent = event,
                            sampleEventTime = event.eventTime
                        )
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (isDrawingWithStylus) {
                            refreshStylusSession(fromMove = true)
                            if (stylusDiagFirstMoveLoggedStroke != stylusDiagStrokeId) {
                                stylusDiagFirstMoveLoggedStroke = stylusDiagStrokeId
                                Log.i(
                                    "StylusDiag",
                                    "MOVE#$stylusDiagStrokeId 1stMove hist=${event.historySize} " +
                                        "${motionEventToolDiag(event, actionIndex)} " +
                                        "markerCanvas=${markerCanvas != null} bc=${bitmapCache != null}"
                                )
                            }
                            val breakSq = stylusStrokeBreakDistanceSq()
                            fun emitStylusTo(tx: Float, ty: Float, sampleTime: Long) {
                                val distSq = stylusStrokeJumpDistSq(lastStylusX, lastStylusY, tx, ty)
                                val timeGapMs =
                                    if (lastStylusSampleEventTime > 0L) sampleTime - lastStylusSampleEventTime else 0L
                                val breakSpace = distSq > breakSq
                                val breakTime = stylusStrokeBreakByTimeGap(distSq, breakSq, timeGapMs)
                                if (breakSpace || breakTime) {
                                    applyStylusStrokeUp(lastStylusX, lastStylusY, dhwActive)
                                    stylusDiagStrokeId++
                                    updateMarkerTransform()
                                    beginStylusStrokeCore(
                                        tx, ty, dhwActive,
                                        requestUnbuffered = false,
                                        unbufferedEvent = null,
                                        sampleEventTime = sampleTime
                                    )
                                    Log.i(
                                        "StylusDiag",
                                        "MOVE 拆笔#$stylusDiagStrokeId → (${tx.toInt()},${ty.toInt()}) " +
                                            "space=$breakSpace time=$breakTime gap=${timeGapMs}ms"
                                    )
                                    return
                                }
                                drawSegmentAndInvalidate(lastStylusX, lastStylusY, tx, ty)
                                if (dhwActive) expandStrokeBounds(tx, ty)
                                lastStylusX = tx
                                lastStylusY = ty
                                lastStylusSampleEventTime = sampleTime
                                viewToBitmapXY(tx, ty)?.let { (bx, by) ->
                                    drawingSocketManager.enqueueStrokePoint(bx, by)
                                }
                            }
                            for (i in 0 until event.historySize) {
                                emitStylusTo(
                                    event.getHistoricalX(pi, i),
                                    event.getHistoricalY(pi, i),
                                    event.getHistoricalEventTime(i)
                                )
                            }
                            emitStylusTo(ex(), ey(), event.eventTime)
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (isDrawingWithStylus) {
                            applyStylusStrokeUp(ex(), ey(), dhwActive)
                        }
                    }
                }
                return@setOnTouchListener true
            }

            // 手指 → 擦除
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isErasing = true
                    lastX = event.x
                    lastY = event.y
                    erase(event.x, event.y)
                    eraseHiddenDrawing(event.x, event.y)
                    Log.d("ClientActivity", "开始擦除: (${event.x}, ${event.y})")
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isErasing) {
                        interpolateErase(lastX, lastY, event.x, event.y)
                        interpolateEraseHiddenDrawing(lastX, lastY, event.x, event.y)
                        lastX = event.x
                        lastY = event.y
                    }
                }
                MotionEvent.ACTION_UP -> {
                    isErasing = false
                    invalidateHandler.post {
                        if (pendingMainInvalidate) {
                            imageView.invalidate()
                            pendingMainInvalidate = false
                        }
                        if (pendingWritingInvalidate) {
                            writingDisplayView.invalidate()
                            pendingWritingInvalidate = false
                        }
                    }
                    Log.d("ClientActivity", "结束擦除")
                }
            }
            true
        }
    }

    /** 扩展本笔画包围盒（view 坐标） */
    private fun expandStrokeBounds(vx: Float, vy: Float) {
        if (vx < strokeBoundsLeft)   strokeBoundsLeft   = vx
        if (vy < strokeBoundsTop)    strokeBoundsTop    = vy
        if (vx > strokeBoundsRight)  strokeBoundsRight  = vx
        if (vy > strokeBoundsBottom) strokeBoundsBottom = vy
    }

    private fun stylusStrokeJumpDistSq(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        return dx * dx + dy * dy
    }

    /** 卡住丢 UP 时 MOVE 会误连两笔：超过此间距视为新笔划（略大以免快速连笔误判） */
    private fun stylusStrokeBreakDistanceSq(): Float {
        val w = if (::imageView.isInitialized) imageView.width else 0
        val h = if (::imageView.isInitialized) imageView.height else 0
        val m = minOf(w, h).coerceAtLeast(1)
        val base = maxOf(200f, m * 0.11f)
        return base * base
    }

    /**
     * 快写时两笔起点可能很近，单靠距离拆笔不够；同一笔内采样间隔多小于 ~18ms，
     * 丢 UP 后下一笔首点常见约 20ms～90ms 间隔且位移中等 — 用窄时间窗补拆笔。
     * 上限 95ms：更长间隔多为慢拖笔，避免误拆。
     */
    private fun stylusStrokeBreakByTimeGap(distSq: Float, spaceBreakSq: Float, timeGapMs: Long): Boolean {
        if (lastStylusSampleEventTime <= 0L) return false
        if (timeGapMs < 18L || timeGapMs > 95L) return false
        if (distSq < 26f * 26f) return false
        if (distSq >= spaceBreakSq) return false
        val midCapSq = (150f * 150f).coerceAtMost(spaceBreakSq * 0.92f)
        if (distSq > midCapSq) return false
        return true
    }

    /** 抬笔：与 onTouch 中 ACTION_UP 逻辑一致 */
    private fun applyStylusStrokeUp(endX: Float, endY: Float, dhwActive: Boolean) {
        if (!isDrawingWithStylus) return
        Log.i(
            "StylusDiag",
            "UP#$stylusDiagStrokeId dhwActive=$dhwActive markerCanvas=${markerCanvas != null} end=($endX,$endY)"
        )
        if (dhwActive) sonyDhw?.setDhwState(false)
        if (dhwActive) {
            val udx = endX - lastStylusX
            val udy = endY - lastStylusY
            if (udx * udx + udy * udy < stylusMicroMovePx * stylusMicroMovePx) {
                drawMarkerDotAt(endX, endY)
            }
            drawMarkerSegmentToBitmap(lastStylusX, lastStylusY, endX, endY)
            expandStrokeBounds(endX, endY)
            val sw = markerStrokeWidth + 2f
            invalidateFast(
                (strokeBoundsLeft - sw).toInt().coerceAtLeast(0),
                (strokeBoundsTop - sw).toInt().coerceAtLeast(0),
                (strokeBoundsRight + sw).toInt() + 1,
                (strokeBoundsBottom + sw).toInt() + 1
            )
        } else {
            drawSegmentAndInvalidate(lastStylusX, lastStylusY, endX, endY)
        }
        viewToBitmapXY(endX, endY)?.let { (bx, by) ->
            drawingSocketManager.enqueueStrokePoint(bx, by)
        }
        drawingSocketManager.enqueueStrokeEnd()
        isDrawingWithStylus = false
        lastStylusSampleEventTime = 0L
        lastStylusPointerUpAt = System.currentTimeMillis()
        refreshStylusSession(fromMove = false)
    }

    /**
     * 落笔（新笔划起点）。synthetic=true 时用于 MOVE 内检测到「跳点」后开新笔，不调 requestUnbufferedDispatch。
     */
    private fun beginStylusStrokeCore(
        vx: Float,
        vy: Float,
        dhwActive: Boolean,
        requestUnbuffered: Boolean,
        unbufferedEvent: MotionEvent?,
        sampleEventTime: Long
    ) {
        if (requestUnbuffered && unbufferedEvent != null) {
            imageView.requestUnbufferedDispatch(unbufferedEvent)
        }
        if (dhwActive) sonyDhw?.setDhwState(true)
        isDrawingWithStylus = true
        lastStylusX = vx
        lastStylusY = vy
        strokeBoundsLeft = vx
        strokeBoundsTop = vy
        strokeBoundsRight = vx
        strokeBoundsBottom = vy
        drawMarkerDotAt(vx, vy)
        val swd = maxOf(6f, markerStrokeWidth * 0.35f + 4f)
        invalidateFast(
            (vx - swd).toInt().coerceAtLeast(0),
            (vy - swd).toInt().coerceAtLeast(0),
            (vx + swd).toInt() + 1,
            (vy + swd).toInt() + 1
        )
        viewToBitmapXY(vx, vy)?.let { (bx, by) ->
            drawingSocketManager.enqueueStrokeStart(
                bx, by,
                bitmapMarkerPaint.color,
                bitmapMarkerPaint.strokeWidth
            )
        }
        lastStylusSampleEventTime = sampleEventTime
    }

    /** 位移过小时 drawLine 几乎无像素，补一个端点圆，避免「压下去第一笔看不见」 */
    private val stylusMicroMovePx = 2.8f

    /** 写入 bitmapCache 并局部刷新 imageView（view 坐标系，直接做坐标变换） */
    private fun drawSegmentAndInvalidate(vx1: Float, vy1: Float, vx2: Float, vy2: Float) {
        val dx = vx2 - vx1
        val dy = vy2 - vy1
        if (dx * dx + dy * dy < stylusMicroMovePx * stylusMicroMovePx) {
            drawMarkerDotAt(vx2, vy2)
        }
        drawMarkerSegmentToBitmap(vx1, vy1, vx2, vy2)
        val sw = markerStrokeWidth + 2f
        // 全部走 A2 快速模式（含轨迹段），避免 GC16 积压堵塞抬笔刷新
        invalidateFast(
            (minOf(vx1, vx2) - sw).toInt().coerceAtLeast(0),
            (minOf(vy1, vy2) - sw).toInt().coerceAtLeast(0),
            (maxOf(vx1, vx2) + sw).toInt() + 1,
            (maxOf(vy1, vy2) + sw).toInt() + 1
        )
    }

    /**
     * 更新坐标变换缓存 + Paint 属性。
     * 在 bitmapCache 变化或 isWhiteCanvas 变化时调用，非热路径。
     */
    private fun updateMarkerTransform() {
        if (imageView.width <= 0 || imageView.height <= 0) {
            Log.w(
                "StylusDiag",
                "updateMarkerTransform SKIP: imageView ${imageView.width}x${imageView.height} (未布局完成)"
            )
            return
        }
        // 没有收到图片时自动创建空白画布，保证书写功能始终可用（须尊重当前黑/白底设置，不可强制白底）
        if (bitmapCache == null) {
            val empty = Bitmap.createBitmap(imageView.width, imageView.height, Bitmap.Config.ARGB_8888)
            val bg = if (isWhiteCanvas) Color.WHITE else Color.BLACK
            empty.eraseColor(bg)
            bitmapCache = empty
            imageView.setImageBitmap(empty)
            Log.i(
                "StylusDiag",
                "updateMarkerTransform: 新建空白 bitmapCache ${empty.width}x${empty.height} bg=${if (isWhiteCanvas) "W" else "K"}"
            )
        }
        val bitmap = bitmapCache ?: run {
            Log.e("StylusDiag", "updateMarkerTransform: bitmapCache 仍为 null（异常）")
            return
        }
        bitmapScale   = minOf(
            imageView.width.toFloat()  / bitmap.width,
            imageView.height.toFloat() / bitmap.height
        )
        bitmapOffsetX = (imageView.width  - bitmap.width  * bitmapScale) / 2f
        bitmapOffsetY = (imageView.height - bitmap.height * bitmapScale) / 2f
        // paint 属性也在此一次性更新，热路径里不再碰 Paint
        bitmapMarkerPaint.strokeWidth = markerStrokeWidth / bitmapScale
        bitmapMarkerPaint.color = if (isWhiteCanvas) Color.BLACK else Color.WHITE
        // 同步更新 markerCanvas
        if (markerCanvas == null || currentMarkerBitmap !== bitmap) {
            markerCanvas = Canvas(bitmap)
            currentMarkerBitmap = bitmap
            Log.d(
                "StylusDiag",
                "updateMarkerTransform: 绑定 markerCanvas → bitmap ${bitmap.width}x${bitmap.height}"
            )
        }
    }

    /**
     * 热路径：在 bitmapCache 上画一条线段。
     * 只做 4 次算术 + 1 次 drawLine，零 GC，零 Paint 更新。
     */
    private fun drawMarkerSegmentToBitmap(vx1: Float, vy1: Float, vx2: Float, vy2: Float) {
        val c = markerCanvas ?: run {
            if (stylusDiagLastNullCanvasStroke != stylusDiagStrokeId) {
                stylusDiagLastNullCanvasStroke = stylusDiagStrokeId
                Log.w(
                    "StylusDiag",
                    "drawMarkerSegmentToBitmap SKIP#$stylusDiagStrokeId: markerCanvas=null " +
                        "bc=${bitmapCache?.let { "${it.width}x${it.height}" } ?: "null"}"
                )
            }
            return
        }
        val s = bitmapScale
        val ox = bitmapOffsetX
        val oy = bitmapOffsetY
        c.drawLine(
            (vx1 - ox) / s, (vy1 - oy) / s,
            (vx2 - ox) / s, (vy2 - oy) / s,
            bitmapMarkerPaint
        )
    }

    /** 落笔触点：零长度 drawLine 常无像素，用圆保证 DOWN 即有墨迹 */
    private fun drawMarkerDotAt(vx: Float, vy: Float) {
        val c = markerCanvas ?: run {
            Log.w(
                "StylusDiag",
                "drawMarkerDotAt SKIP#$stylusDiagStrokeId: markerCanvas=null (落笔点未绘制)"
            )
            return
        }
        val s = bitmapScale
        val ox = bitmapOffsetX
        val oy = bitmapOffsetY
        val px = (vx - ox) / s
        val py = (vy - oy) / s
        val sw = bitmapMarkerPaint.strokeWidth
        // 落笔点仅作「触点」提示，约为线宽的 26% 直径（原先用半线宽整圆偏大）
        val r = (sw * 0.13f).coerceIn(0.35f, sw * 0.22f)
        // STROKE 下 drawCircle 只描边，呈空心圆；触点需实心圆
        val prevStyle = bitmapMarkerPaint.style
        bitmapMarkerPaint.style = Paint.Style.FILL
        c.drawCircle(px, py, r, bitmapMarkerPaint)
        bitmapMarkerPaint.style = prevStyle
    }

    /**
     * 将 imageView 视图坐标转换为 bitmapCache 的像素坐标（整数）。
     * 供笔划传输使用，不在绘制热路径上。
     */
    private fun viewToBitmapXY(vx: Float, vy: Float): Pair<Int, Int>? {
        val bitmap = bitmapCache ?: return null
        val bx = ((vx - bitmapOffsetX) / bitmapScale).toInt().coerceIn(0, bitmap.width - 1)
        val by = ((vy - bitmapOffsetY) / bitmapScale).toInt().coerceIn(0, bitmap.height - 1)
        return Pair(bx, by)
    }

    /** 仅供其他模块（擦除坐标转换）使用的旧版 viewToBitmapCoords，不在书写热路径上 */
    private fun viewToBitmapCoords(vx: Float, vy: Float): Triple<Float, Float, Float>? {
        val bitmap = bitmapCache ?: return null
        if (imageView.width <= 0 || imageView.height <= 0) return null
        val scale = minOf(
            imageView.width.toFloat() / bitmap.width,
            imageView.height.toFloat() / bitmap.height
        )
        val offsetX = (imageView.width - bitmap.width * scale) / 2f
        val offsetY = (imageView.height - bitmap.height * scale) / 2f
        return Triple((vx - offsetX) / scale, (vy - offsetY) / scale, scale)
    }

    /** 确保 markerCanvas 绑定到当前 bitmapCache（供非热路径使用） */
    private fun ensureMarkerCanvas(): Canvas? {
        val bitmap = bitmapCache ?: return null
        if (markerCanvas == null || currentMarkerBitmap !== bitmap) {
            markerCanvas = Canvas(bitmap)
            currentMarkerBitmap = bitmap
        }
        return markerCanvas
    }
    
    private fun hiddenViewToImageViewPoint(hiddenViewX: Float, hiddenViewY: Float): Pair<Float, Float>? {
        return try {
            if (!::hiddenDrawingView.isInitialized) return null
            val hiddenViewLocation = IntArray(2)
            hiddenDrawingView.getLocationOnScreen(hiddenViewLocation)
            val imageViewLocation = IntArray(2)
            imageView.getLocationOnScreen(imageViewLocation)
            val screenX = hiddenViewLocation[0] + hiddenViewX
            val screenY = hiddenViewLocation[1] + hiddenViewY
            Pair(screenX - imageViewLocation[0], screenY - imageViewLocation[1])
        } catch (e: Exception) {
            Log.w("ClientActivity", "隐藏画板坐标转 imageView 坐标失败: ${e.message}")
            null
        }
    }
    
    private fun interpolateErase(startX: Float, startY: Float, endX: Float, endY: Float) {
        val dx = endX - startX
        val dy = endY - startY
        val distance = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        // 优化：减少插值步骤，使用更大的步长，避免过多绘制调用
        val stepSize = eraserRadius * 0.6f  // 增大步长，减少步骤数
        val steps = (distance / stepSize).toInt().coerceAtLeast(1).coerceAtMost(20)  // 限制最大步骤数
        
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val x = startX + dx * t
            val y = startY + dy * t
            erase(x, y)
        }
    }
    
    private fun viewToBitmapCoords(view: ImageView, bitmap: Bitmap, viewX: Float, viewY: Float): Triple<Float, Float, Float>? {
        if (view.width <= 0 || view.height <= 0) return null
        val viewW = view.width.toFloat()
        val viewH = view.height.toFloat()
        val bitmapW = bitmap.width.toFloat()
        val bitmapH = bitmap.height.toFloat()
        val scale = minOf(viewW / bitmapW, viewH / bitmapH)
        val drawW = bitmapW * scale
        val drawH = bitmapH * scale
        val offsetX = (viewW - drawW) / 2
        val offsetY = (viewH - drawH) / 2
        val bitmapX = (viewX - offsetX) / scale
        val bitmapY = (viewY - offsetY) / scale
        return Triple(bitmapX, bitmapY, scale)
    }
    
    private fun erase(x: Float, y: Float) {
        // 如果bitmapCache为null，尝试从currentBitmap恢复
        if (bitmapCache == null) {
            val current = currentBitmap
            if (current != null) {
                try {
                    bitmapCache = current.copy(Bitmap.Config.ARGB_8888, true)
                    Log.w("ClientActivity", "bitmapCache为null，从currentBitmap恢复")
                } catch (e: OutOfMemoryError) {
                    Log.e("ClientActivity", "恢复bitmapCache时内存不足，使用原始位图: ${e.message}")
                    bitmapCache = current
                }
            }
        }
        
        bitmapCache?.let { bitmap ->
            val coords = viewToBitmapCoords(imageView, bitmap, x, y) ?: return@let
            val (bitmapX, bitmapY, scale) = coords
            if (bitmapX !in 0f..bitmap.width.toFloat() || bitmapY !in 0f..bitmap.height.toFloat()) return@let
            
            // 复用Canvas对象，避免频繁创建
            if (eraseCanvas == null || currentEraseBitmap != bitmap) {
                eraseCanvas = Canvas(bitmap)
                currentEraseBitmap = bitmap
            }
            
            // 复用Paint对象，避免频繁创建和GC
            if (eraserPaint == null) {
                eraserPaint = Paint().apply {
                    style = Paint.Style.FILL
                    isAntiAlias = true
                }
            }
            
            // 更新颜色（只在背景色变化时更新）
            val targetColor = if (isWhiteCanvas) Color.WHITE else Color.BLACK
            if (eraserPaint?.color != targetColor) {
                eraserPaint?.color = targetColor
            }
            
            val radiusInBitmap = eraserRadius / scale
            eraseCanvas?.drawCircle(bitmapX, bitmapY, radiusInBitmap, eraserPaint!!)
            
            // 延迟刷新，减少invalidate调用频率
            if (!pendingMainInvalidate) {
                pendingMainInvalidate = true
                invalidateHandler.postDelayed({
                    imageView.invalidate()
                    pendingMainInvalidate = false
                }, 16) // 约60fps刷新率
            }
        } ?: run {
            Log.w("ClientActivity", "无法擦除主屏幕：bitmapCache为null，且无法从currentBitmap恢复")
            // 如果仍然无法擦除，重置isErasing状态，避免卡在擦除状态
            isErasing = false
        }
    }
    
    private fun eraseHiddenDrawing(x: Float, y: Float) {
        if (::hiddenDrawingView.isInitialized) {
            // 如果隐藏功能已启用，擦除隐藏画板的内容
            // 注意：x, y 是 ImageView 的相对坐标，需要转换为隐藏画板的相对坐标
            if (isHiddenFeatureEnabled) {
                try {
                    // 获取隐藏画板在屏幕上的位置
                    val hiddenViewLocation = IntArray(2)
                    hiddenDrawingView.getLocationOnScreen(hiddenViewLocation)
                    
                    // 获取 ImageView 在屏幕上的位置
                    val imageViewLocation = IntArray(2)
                    imageView.getLocationOnScreen(imageViewLocation)
                    
                    // 将 ImageView 相对坐标转换为屏幕绝对坐标
                    val screenX = imageViewLocation[0] + x
                    val screenY = imageViewLocation[1] + y
                    
                    // 将屏幕绝对坐标转换为隐藏画板的相对坐标
                    val hiddenViewX = screenX - hiddenViewLocation[0]
                    val hiddenViewY = screenY - hiddenViewLocation[1]
                    
                    // 检查坐标是否在隐藏画板范围内，避免无效的擦除操作
                    if (hiddenViewX >= 0 && hiddenViewX <= hiddenDrawingView.width &&
                        hiddenViewY >= 0 && hiddenViewY <= hiddenDrawingView.height) {
                        hiddenDrawingView.eraseAtPosition(hiddenViewX, hiddenViewY, eraserRadius, isWhiteCanvas)
                    }
                    // 如果不在范围内，静默跳过，不输出日志（减少日志噪音）
                } catch (e: Exception) {
                    Log.w("ClientActivity", "擦除隐藏画板时坐标转换失败: ${e.message}")
                }
            }
            
            // 擦除已显示的书写内容（无论隐藏功能是否启用）
            eraseDisplayedWriting(x, y)
        }
    }
    
    private fun interpolateEraseHiddenDrawing(startX: Float, startY: Float, endX: Float, endY: Float) {
        if (::hiddenDrawingView.isInitialized) {
            // 如果隐藏功能已启用，对隐藏画板进行插值擦除
            if (isHiddenFeatureEnabled) {
                try {
                    // 获取隐藏画板在屏幕上的位置
                    val hiddenViewLocation = IntArray(2)
                    hiddenDrawingView.getLocationOnScreen(hiddenViewLocation)
                    
                    // 获取 ImageView 在屏幕上的位置
                    val imageViewLocation = IntArray(2)
                    imageView.getLocationOnScreen(imageViewLocation)
                    
                    // 将 ImageView 相对坐标转换为屏幕绝对坐标
                    val startScreenX = imageViewLocation[0] + startX
                    val startScreenY = imageViewLocation[1] + startY
                    val endScreenX = imageViewLocation[0] + endX
                    val endScreenY = imageViewLocation[1] + endY
                    
                    // 将屏幕绝对坐标转换为隐藏画板的相对坐标
                    val startHiddenViewX = startScreenX - hiddenViewLocation[0]
                    val startHiddenViewY = startScreenY - hiddenViewLocation[1]
                    val endHiddenViewX = endScreenX - hiddenViewLocation[0]
                    val endHiddenViewY = endScreenY - hiddenViewLocation[1]
                    
                    // 检查至少有一个点在隐藏画板范围内，才进行插值擦除
                    val startInBounds = startHiddenViewX >= 0 && startHiddenViewX <= hiddenDrawingView.width &&
                                      startHiddenViewY >= 0 && startHiddenViewY <= hiddenDrawingView.height
                    val endInBounds = endHiddenViewX >= 0 && endHiddenViewX <= hiddenDrawingView.width &&
                                    endHiddenViewY >= 0 && endHiddenViewY <= hiddenDrawingView.height
                    
                    if (startInBounds || endInBounds) {
                        hiddenDrawingView.interpolateEraseAtPosition(startHiddenViewX, startHiddenViewY, endHiddenViewX, endHiddenViewY, eraserRadius, isWhiteCanvas)
                    }
                    // 如果都不在范围内，静默跳过
                } catch (e: Exception) {
                    Log.w("ClientActivity", "插值擦除隐藏画板时坐标转换失败: ${e.message}")
                }
            }
            
            // 对已显示的书写内容进行插值擦除（无论隐藏功能是否启用）
            interpolateEraseDisplayedWriting(startX, startY, endX, endY)
        }
    }
    
    private fun eraseDisplayedWriting(x: Float, y: Float) {
        if (writingDisplayContainer.visibility == View.VISIBLE) {
            try {
                // 获取当前显示的位图
                val currentBitmap = (writingDisplayView.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                currentBitmap?.let { bitmap ->
                    // 视图坐标转位图坐标，writingDisplayView 尺寸为 0 时使用 imageView 作为参考
                    var coords = viewToBitmapCoords(writingDisplayView, bitmap, x, y)
                    if (coords == null) {
                        // Fallback：writingDisplayView 布局未完成，使用 imageView 的尺寸（两者都是 match_parent）
                        coords = viewToBitmapCoords(imageView, bitmap, x, y)
                        if (coords == null) {
                            Log.w("ClientActivity", "无法转换擦除坐标，两个 view 尺寸都为 0")
                            return@let
                        }
                        Log.d("ClientActivity", "writingDisplayView 尺寸为 0，使用 imageView 作为坐标转换参考")
                    }
                    val (bitmapX, bitmapY, scale) = coords
                    if (bitmapX !in 0f..bitmap.width.toFloat() || bitmapY !in 0f..bitmap.height.toFloat()) return@let
                    
                    // 避免每次擦除都复制位图，直接使用可变位图
                    if (writingDisplayBitmap == null || 
                        writingDisplayBitmap!!.width != bitmap.width || 
                        writingDisplayBitmap!!.height != bitmap.height) {
                        writingDisplayBitmap?.recycle()
                        
                        // 如果 bitmap 本身就是可变的，直接使用，无需 copy（避免高内存时OOM）
                        if (bitmap.isMutable) {
                            writingDisplayBitmap = bitmap
                            writingDisplayCanvas = Canvas(writingDisplayBitmap!!)
                            Log.d("ClientActivity", "书写位图本身可变，直接使用，无需复制")
                        } else {
                            // 只有不可变时才 copy，并做内存检查
                            val memoryUsage = getMemoryUsage()
                            if (memoryUsage > 0.85f) {
                                Log.w("ClientActivity", "擦除书写内容时内存过高(${(memoryUsage*100).toInt()}%)，跳过以避免OOM")
                                writingDisplayBitmap = null
                                writingDisplayCanvas = null
                                return@let
                            }
                            try {
                                writingDisplayBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                                writingDisplayCanvas = Canvas(writingDisplayBitmap!!)
                                writingDisplayView.setImageBitmap(writingDisplayBitmap)
                                writingDisplayView.requestLayout()
                                writingDisplayView.post { writingDisplayView.invalidate() }
                                Log.d("ClientActivity", "书写位图不可变，已创建可变副本用于擦除")
                            } catch (e: OutOfMemoryError) {
                                Log.e("ClientActivity", "复制书写位图时内存不足，跳过擦除: ${e.message}")
                                writingDisplayBitmap = null
                                writingDisplayCanvas = null
                                return@let
                            }
                        }
                    }
                    
                    // 复用Canvas和Paint
                    if (writingDisplayCanvas == null) {
                        writingDisplayCanvas = Canvas(writingDisplayBitmap!!)
                    }
                    
                    if (eraserPaint == null) {
                        eraserPaint = Paint().apply {
                            style = Paint.Style.FILL
                            isAntiAlias = true
                        }
                    }
                    
                    // 更新颜色
                    val targetColor = if (isWhiteCanvas) Color.WHITE else Color.BLACK
                    if (eraserPaint?.color != targetColor) {
                        eraserPaint?.color = targetColor
                    }
                    
                    val radiusInBitmap = eraserRadius / scale
                    writingDisplayCanvas?.drawCircle(bitmapX, bitmapY, radiusInBitmap, eraserPaint!!)
                    // 延迟刷新，避免频繁刷新
                    if (!pendingWritingInvalidate) {
                        pendingWritingInvalidate = true
                        invalidateHandler.postDelayed({
                            writingDisplayView.invalidate()
                            pendingWritingInvalidate = false
                        }, 16)
                    }
                }
            } catch (e: Exception) {
                Log.w("ClientActivity", "擦除显示的书写内容时出错: ${e.message}")
            }
        }
    }
    
    private fun interpolateEraseDisplayedWriting(startX: Float, startY: Float, endX: Float, endY: Float) {
        val dx = endX - startX
        val dy = endY - startY
        val distance = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        // 优化：减少插值步骤，使用更大的步长
        val stepSize = eraserRadius * 0.6f  // 增大步长，减少步骤数
        val steps = (distance / stepSize).toInt().coerceAtLeast(1).coerceAtMost(20)  // 限制最大步骤数
        
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val x = startX + dx * t
            val y = startY + dy * t
            eraseDisplayedWriting(x, y)
        }
    }
    
    private fun clearBitmapCache() {
        // 必须先解除 ImageView 对位图的引用，再回收，否则会崩溃 "trying to use a recycled bitmap"
        imageView.setImageBitmap(null)
        // 不清空 writingDisplayView，保留最后的 writingDisplayBitmap，让擦除功能持续可用
        
        // 清理所有位图缓存
        bitmapQueue.forEach { bitmap ->
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
        bitmapQueue.clear()
        bitmapCache?.recycle()
        bitmapCache = null
        
        // 清理涂抹相关的Canvas和资源
        eraseCanvas = null
        currentEraseBitmap = null
        writingDisplayCanvas = null
        // 不回收 writingDisplayBitmap，因为 writingDisplayView 可能仍在使用它
        // 只清空引用，让 GC 在合适时机回收
        writingDisplayBitmap = null
        invalidateHandler.removeCallbacksAndMessages(null)
        pendingMainInvalidate = false
        pendingWritingInvalidate = false
    }
    
    // 清除所有图片缓存，包括书写内容
    private fun clearAllImageCache() {
        try {
            // 清理位图缓存
            clearBitmapCache()
            
            // 清理书写内容列表
            writingContentList.forEach { bitmap ->
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
            writingContentList.clear()
            
            // 清理隐藏画板的缓存
            if (::hiddenDrawingView.isInitialized) {
                hiddenDrawingView.clearAllSessions()
            }
            
            // 清理当前位图
            currentBitmap?.recycle()
            currentBitmap = null
            
            // 不清理保存的截图文件，让用户手动管理
            // clearSavedScreenshots()
            
            // 强制垃圾回收
            System.gc()
            
            Log.d("ClientActivity", "已清理所有图片缓存和资源，保存的截图保留")
        } catch (e: Exception) {
            Log.e("ClientActivity", "清理图片缓存时出错: ${e.message}")
        }
    }
    
    private fun clearSavedScreenshots() {
        try {
            // 删除保存的截图文件
            val saveDir = File(filesDir, SAVE_DIRECTORY)
            if (saveDir.exists()) {
                val files = saveDir.listFiles()
                files?.forEach { file ->
                    if (file.name.startsWith("screenshot_") && file.name.endsWith(".png")) {
                        file.delete()
                        Log.d("ClientActivity", "已删除保存的截图: ${file.name}")
                    }
                }
            }
            
            // 清空保存路径记录
            savedScreenshots.clear()
            
            Log.d("ClientActivity", "已清理所有保存的截图")
        } catch (e: Exception) {
            Log.e("ClientActivity", "清理保存的截图失败: ${e.message}")
        }
    }
    
    private fun manageBitmapCache(newBitmap: Bitmap) {
        try {
            // 检查内存状态，如果内存紧张则减少缓存数量
            val memoryUsage = getMemoryUsage()
            val currentMaxCache = if (memoryUsage > 0.8f) 1 else maxCacheSize
            
            // 回收旧的位图
            while (bitmapQueue.size >= currentMaxCache) {
                val oldBitmap = bitmapQueue.removeFirst()
                if (!oldBitmap.isRecycled) {
                    oldBitmap.recycle()
                }
            }
            
            // 创建可修改的位图副本用于bitmapCache（用于擦除功能）
            // 注意：必须创建副本，因为原始位图可能被回收或修改
            if (bitmapCache?.isRecycled == false) {
                bitmapCache?.recycle()
            }
            try {
                bitmapCache = newBitmap.copy(Bitmap.Config.ARGB_8888, true)
            } catch (e: OutOfMemoryError) {
                Log.e("ClientActivity", "创建bitmapCache副本时内存不足，使用原始位图: ${e.message}")
                bitmapCache = newBitmap
            }
            
            // 同时更新currentBitmap，确保擦除功能可以从currentBitmap恢复
            currentBitmap?.recycle()
            try {
                currentBitmap = newBitmap.copy(Bitmap.Config.ARGB_8888, true)
            } catch (e: OutOfMemoryError) {
                Log.e("ClientActivity", "创建currentBitmap副本时内存不足，使用原始位图: ${e.message}")
                currentBitmap = newBitmap
            }
            
            // 将原始位图添加到队列（用于历史记录）
            bitmapQueue.addLast(newBitmap)
            
            if (memoryUsage > 0.8f) {
                Log.d("ClientActivity", "内存紧张，减少位图缓存数量到: $currentMaxCache")
            }
            
            Log.d("ClientActivity", "位图缓存已更新，bitmapCache和currentBitmap已同步")
        } catch (e: Exception) {
            Log.e("ClientActivity", "Error managing bitmap cache: ${e.message}", e)
            // 如果创建副本失败，至少尝试直接赋值（作为后备方案）
            if (bitmapCache == null) {
                bitmapCache = newBitmap
                currentBitmap = newBitmap
                Log.w("ClientActivity", "位图缓存创建副本失败，使用原始位图（可能导致擦除功能异常）")
            }
        }
    }
    
    private fun updateBackgroundColors(color: Int) {
        try {
            window.decorView.setBackgroundColor(color)
            rootLayout.setBackgroundColor(color)
            imageView.setBackgroundColor(color)
        } catch (e: Exception) {
            Log.e("ClientActivity", "Error updating background colors: ${e.message}")
        }
    }

    // Zona de toque invisible en la esquina superior izquierda (donde la "×").
    // Modo V2: MANTENER PULSADO 4 SEGUNDOS muestra el QR (y consume el gesto,
    // así no se dispara a la vez el triple toque de reconexión de V1).
    // Modo V1: la zona DEJA PASAR los toques a los botones de debajo, con lo
    // que el triple toque (reconectar) y los combos de cierre siguen intactos.
    private fun addV2TapZone() {
        if (v2TapZoneAdded) return
        v2TapZoneAdded = true
        val d = resources.displayMetrics.density
        val zone = View(this).apply {
            isFocusable = false
            setBackgroundColor(Color.TRANSPARENT)
            setOnTouchListener { _, event ->
                // En V1 no consumir nada: los toques llegan a btnCombined y cía.
                if (!isServerMode) return@setOnTouchListener false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v2HoldRunnable?.let { handler.removeCallbacks(it) }
                        v2HoldRunnable = Runnable {
                            if (isServerMode) {
                                Log.d("ClientActivity", "Pulsación larga 4s → mostrar QR")
                                showServerInfo()
                            }
                        }
                        handler.postDelayed(v2HoldRunnable!!, 4000L)
                        // Combo de cierre (idéntico a btnCombined en V1): a los
                        // 2s marca "arriba pulsado"; si abajo-izquierda también
                        // está en pulsación larga, cierra la app.
                        v2CloseComboRunnable?.let { handler.removeCallbacks(it) }
                        v2CloseComboRunnable = Runnable {
                            isLeftTopLongPressed = true
                            if (isLeftBottomLongPressed) {
                                Log.d("ClientActivity", "Combo de cierre en V2: cerrando app")
                                drawingSocketManager.disconnect()
                                try { Thread.sleep(250) } catch (_: InterruptedException) {}
                                finishAffinity()
                                System.exit(0)
                            }
                        }
                        handler.postDelayed(v2CloseComboRunnable!!, LEFT_TOP_LONG_PRESS_DURATION)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v2HoldRunnable?.let { handler.removeCallbacks(it) }
                        v2HoldRunnable = null
                        v2CloseComboRunnable?.let { handler.removeCallbacks(it) }
                        v2CloseComboRunnable = null
                        isLeftTopLongPressed = false
                        true
                    }
                    else -> true
                }
            }
        }
        // Área grande: toda la franja superior izquierda (300dp × 250dp desde
        // la esquina), más fácil de acertar en la e-ink.
        val lp = RelativeLayout.LayoutParams((300 * d).toInt(), (250 * d).toInt()).apply {
            addRule(RelativeLayout.ALIGN_PARENT_TOP)
            addRule(RelativeLayout.ALIGN_PARENT_START)
            marginStart = 0
            topMargin = 0
        }
        rootLayout.addView(zone, lp)
        zone.bringToFront() // por encima de btnCombined para recibir los toques
    }

    // ── Modo V2: overlay con IP:puerto + QR para conectar fácilmente ─────────
    private fun showServerInfo() {
        runOnUiThread {
            try {
                val ip = NetworkUtils.getLocalIpAddress(this)
                val port = 8888
                val connText = "$ip:$port"
                // El QR incluye la MAC Bluetooth si está disponible: el móvil
                // puede conectar por BT directo, sin necesidad de red WiFi.
                val btMac = bluetoothManager.getOwnMacAddress()
                val plainText = if (btMac != null) "$connText|BT:$btMac" else connText
                // Ofuscado: el QR lleva Base64 opaco; solo la app lo entiende.
                val qrText = QrCodec.encode(plainText)
                if (serverInfoView == null) {
                    // Overlay a pantalla completa: tocar FUERA del recuadro cierra el QR.
                    val overlay = FrameLayout(this).apply {
                        isClickable = true
                        isFocusable = true
                        setBackgroundColor(0x99000000.toInt())
                        setOnClickListener { hideServerInfo() }
                    }
                    val container = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        setBackgroundColor(Color.WHITE)
                        setPadding(48, 48, 48, 48)
                        // Consume el toque para que tocar DENTRO no cierre.
                        isClickable = true
                        setOnClickListener { }
                    }
                    val title = TextView(this).apply {
                        text = "Modo V2 — escanea el QR o escribe la IP en el móvil"
                        setTextColor(Color.BLACK)
                        textSize = 18f
                        gravity = Gravity.CENTER
                        setPadding(0, 0, 0, 24)
                    }
                    val qr = ImageView(this).apply {
                        setImageBitmap(generateQr(qrText, 480))
                        layoutParams = LinearLayout.LayoutParams(480, 480)
                    }
                    val label = TextView(this).apply {
                        text = connText
                        setTextColor(Color.BLACK)
                        textSize = 30f
                        gravity = Gravity.CENTER
                        setPadding(0, 24, 0, 0)
                    }
                    container.addView(title)
                    container.addView(qr)
                    container.addView(label)
                    if (btMac != null) {
                        val btLabel = TextView(this).apply {
                            text = "BT: $btMac"
                            setTextColor(Color.DKGRAY)
                            textSize = 16f
                            gravity = Gravity.CENTER
                            setPadding(0, 8, 0, 0)
                        }
                        container.addView(btLabel)
                    }
                    val clp = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply { gravity = Gravity.CENTER }
                    overlay.addView(container, clp)
                    val olp = RelativeLayout.LayoutParams(
                        RelativeLayout.LayoutParams.MATCH_PARENT,
                        RelativeLayout.LayoutParams.MATCH_PARENT
                    )
                    rootLayout.addView(overlay, olp)
                    serverInfoView = overlay
                }
                serverInfoView?.visibility = View.VISIBLE
            } catch (e: Exception) {
                Log.e("ClientActivity", "showServerInfo: ${e.message}", e)
            }
        }
    }

    private fun hideServerInfo() {
        runOnUiThread { serverInfoView?.visibility = View.GONE }
    }

    private fun generateQr(text: String, size: Int): Bitmap {
        // Corrección de errores ALTA (H, 30%): necesaria para poder tapar el
        // centro con el logo sin que deje de escanear.
        val hints = hashMapOf<EncodeHintType, Any>(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
            EncodeHintType.MARGIN to 2
        )
        val bits = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (bits.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        // Logo YAO (negro sobre blanco) centrado, ~21% del ancho, con marco
        // blanco alrededor para no romper los módulos vecinos.
        try {
            val logo = BitmapFactory.decodeResource(resources, R.drawable.yao_logo)
            if (logo != null) {
                val ls = (size * 0.21f).toInt()
                val pad = (ls * 0.10f).toInt()
                val left = (size - ls) / 2
                val top = (size - ls) / 2
                val canvas = Canvas(bmp)
                val white = Paint().apply { color = Color.WHITE }
                canvas.drawRect(
                    (left - pad).toFloat(), (top - pad).toFloat(),
                    (left + ls + pad).toFloat(), (top + ls + pad).toFloat(), white
                )
                val scaled = Bitmap.createScaledBitmap(logo, ls, ls, true)
                canvas.drawBitmap(scaled, left.toFloat(), top.toFloat(), Paint().apply { isFilterBitmap = true })
            }
        } catch (e: Exception) {
            Log.w("ClientActivity", "generateQr logo overlay failed: ${e.message}")
        }
        return bmp
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("SERVER_MODE", false)) {
            isServerMode = true
            addV2TapZone() // si venimos de modo V1, la zona del QR no existía
            drawingSocketManager.startAsServer() // internamente detiene el modo cliente
            bluetoothManager.startAsServer()
            bluetoothManager.ensureSearching() // barrido auto BT también en V2
            return
        }
        // Volver a modo cliente V1: apagar el servidor V2 si estaba activo.
        if (isServerMode) {
            isServerMode = false
            hideServerInfo()
            drawingSocketManager.stopServerMode()
            bluetoothManager.stopServerMode()
            bluetoothManager.startSearch()
        }
        val manualIp = intent.getStringExtra("MANUAL_IP")
        if (manualIp != null) {
            lifecycleScope.launch {
                drawingSocketManager.connectToIp(manualIp)
            }
        } else {
            startAutoConnect()
        }
    }
    
    // Renueva el WakeLock cada 4h: acquire() sobre un lock no-referenciado ya
    // retenido reinicia su timeout de 10h, así nunca llega a vencer.
    private fun scheduleWakeLockRenewal() {
        if (wakeLockRenewRunnable != null) return
        wakeLockRenewRunnable = object : Runnable {
            override fun run() {
                try {
                    wakeLock?.let { if (it.isHeld) it.acquire(10 * 60 * 60 * 1000L) }
                } catch (e: Exception) {
                    Log.e("ClientActivity", "WakeLock renewal failed: ${e.message}")
                }
                handler.postDelayed(this, 4 * 60 * 60 * 1000L)
            }
        }
        handler.postDelayed(wakeLockRenewRunnable!!, 4 * 60 * 60 * 1000L)
    }

    override fun onResume() {
        super.onResume()

        // 确保 WakeLock 在恢复时保持激活
        try {
            wakeLock?.let {
                if (!it.isHeld) {
                    it.acquire(10 * 60 * 60 * 1000L)
                    if (it.isHeld) {
                        Log.d("ClientActivity", "onResume: WakeLock已重新获取")
                    } else {
                        Log.w("ClientActivity", "onResume: WakeLock重新获取失败")
                    }
                } else {
                    Log.d("ClientActivity", "onResume: WakeLock仍然保持激活")
                }
            } ?: run {
                // 如果WakeLock为null，重新创建
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                @Suppress("DEPRECATION")
                wakeLock = powerManager.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "ClientActivity::WakeLock"
                )
                wakeLock?.setReferenceCounted(false)
                wakeLock?.acquire(10 * 60 * 60 * 1000L)
                if (wakeLock?.isHeld == true) {
                    Log.d("ClientActivity", "onResume: WakeLock已重新创建并获取（FULL_WAKE_LOCK）")
                }
            }
        } catch (e: Exception) {
            Log.e("ClientActivity", "onResume: 处理WakeLock失败: ${e.message}", e)
        }
        if (System.currentTimeMillis() < stylusSessionUntil) {
            ensureStylusKeepaliveScheduled()
        }
    }
    
    override fun onPause() {
        super.onPause()
        // 不在onPause中释放WakeLock，保持设备唤醒
        Log.d("ClientActivity", "onPause: 保持WakeLock激活状态")
        cancelStylusKeepalive()
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        logStylusHoverDiagnostic(event)
        return super.dispatchGenericMotionEvent(event)
    }
    
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        ev?.let { event ->
            if (event.actionMasked == MotionEvent.ACTION_MOVE && event.pointerCount > 0) {
                val pi = 0
                if (event.getToolType(pi) == MotionEvent.TOOL_TYPE_STYLUS) {
                    val p = try {
                        event.getPressure(pi)
                    } catch (_: Exception) {
                        1f
                    }
                    if (p < 0.02f) {
                        val now = System.currentTimeMillis()
                        if (now - lastTouchStylusLowPressureLogMs >= 280L) {
                            lastTouchStylusLowPressureLogMs = now
                            Log.i(
                                "StylusHover",
                                "TOUCH_MOVE stylus p≈0 xy=(${event.x.toInt()},${event.y.toInt()}) (若无 HOVER_* 可看此项)"
                            )
                        }
                    }
                }
            }
            val screenHeight = resources.displayMetrics.heightPixels
            val bottomThreshold = screenHeight - BOTTOM_GESTURE_THRESHOLD
            
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // 检测是否在底部区域
                    if (event.y >= bottomThreshold) {
                        gestureStartY = event.y
                        gestureStartX = event.x
                        isGestureStarted = true
                        isBottomTouchIntercepted = false
                        Log.d("ClientActivity", "检测到底部触摸，开始拦截手势: y=${event.y}, screenHeight=$screenHeight, bottomThreshold=$bottomThreshold")
                        // 不立即拦截DOWN事件，让子视图有机会处理（如按钮点击）
                    } else {
                        isGestureStarted = false
                        isBottomTouchIntercepted = false
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isGestureStarted) {
                        val deltaY = gestureStartY - event.y // 向上滑动为正值
                        val deltaX = Math.abs(event.x - gestureStartX)
                        
                        // 更早拦截：只要检测到向上移动就拦截
                        if (deltaY > 0) { // 任何向上的移动
                            // 如果是明显的向上滑动（向上移动距离大于横向移动距离），立即拦截
                            if (deltaY > MIN_SWIPE_DISTANCE || (deltaY > 10 && deltaY > deltaX * 0.5)) {
                                if (!isBottomTouchIntercepted) {
                                    Log.d("ClientActivity", "拦截底部上滑手势: deltaY=$deltaY, deltaX=$deltaX, y=${event.y}")
                                    isBottomTouchIntercepted = true
                                }
                                // 消费所有后续事件，阻止系统处理
                                return true
                            } else {
                                // 不是有效的上滑手势，继续处理
                            }
                        } else {
                            // 不是向上移动，继续处理
                        }
                    } else {
                        // 手势未开始，继续处理
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isBottomTouchIntercepted) {
                        // 如果已经拦截了手势，消费UP事件
                        Log.d("ClientActivity", "消费底部手势的UP/CANCEL事件")
                        isGestureStarted = false
                        isBottomTouchIntercepted = false
                        return true
                    }
                    // 检查是否在底部区域且是快速上滑（可能是系统手势）
                    if (isGestureStarted) {
                        val deltaY = gestureStartY - event.y
                        val deltaX = Math.abs(event.x - gestureStartX)
                        if (deltaY > MIN_SWIPE_DISTANCE && deltaY > deltaX) {
                            Log.d("ClientActivity", "拦截底部快速上滑手势（UP事件）: deltaY=$deltaY")
                            isGestureStarted = false
                            return true
                        } else {
                            // 不是有效的上滑手势
                        }
                    } else {
                        // 手势未开始
                    }
                    isGestureStarted = false
                    isBottomTouchIntercepted = false
                }
                else -> {
                    // 其他动作类型，如果已经拦截则继续拦截
                    if (isBottomTouchIntercepted) {
                        return true
                    } else {
                        // 未拦截，继续处理
                    }
                }
            }
        }
        
        // 正常处理其他触摸事件
        val result = super.dispatchTouchEvent(ev)
        
        // 如果事件在底部区域且没有被子视图处理，可能需要拦截
        ev?.let { event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val screenHeight = resources.displayMetrics.heightPixels
                val bottomThreshold = screenHeight - BOTTOM_GESTURE_THRESHOLD
                if (event.y >= bottomThreshold && !result) {
                    // 如果底部触摸没有被处理，可能是系统手势，尝试拦截
                    Log.d("ClientActivity", "底部触摸未被处理，可能被系统手势捕获")
                }
            }
        }
        
        return result
    }
    
    // Lanza la comprobación OTA solo si han pasado ≥30 días desde la última
    // comprobación con éxito. Si el check-in falla (sin internet), NO se marca
    // como hecha → se reintenta en el siguiente arranque. Todo silencioso.
    private fun maybeCheckOta() {
        try {
            val prefs = getSharedPreferences("ota", Context.MODE_PRIVATE)
            val lastCheck = prefs.getLong("last_check_ms", 0L)
            val now = System.currentTimeMillis()
            val monthMs = 30L * 24 * 60 * 60 * 1000
            if (now - lastCheck < monthMs) return

            val license = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                .getString("received_license_name", "") ?: ""

            lifecycleScope.launch {
                val ok = OtaUpdater.checkAndUpdate(
                    this@ClientActivity,
                    owner = license,   // mejor dato disponible; el panel lo registra
                    license = license
                )
                if (ok) {
                    prefs.edit().putLong("last_check_ms", now).apply()
                }
            }
        } catch (e: Exception) {
            Log.e("ClientActivity", "maybeCheckOta error: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        
        // 释放 WakeLock
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d("ClientActivity", "onDestroy: WakeLock已释放")
                } else {
                    Log.w("ClientActivity", "onDestroy: WakeLock未持有，无需释放")
                }
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.e("ClientActivity", "onDestroy: 释放WakeLock失败: ${e.message}", e)
        }
        
        // El CLIENT_DISCONNECT lo envía drawingSocketManager.release() →
        // disconnect() en un hilo plano. El lifecycleScope.launch de antes era
        // inútil: super.onDestroy() ya había cancelado el scope y nunca corría.

        // 清理所有位图资源
        bitmapQueue.forEach { bitmap ->
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
        bitmapQueue.clear()
        bitmapCache?.recycle()
        bitmapCache = null
        cancelStylusKeepalive()
        handler.removeCallbacksAndMessages(null)
        pendingFakeTouchRunnable = null
        
        // 清理隐藏功能资源
        if (::hiddenDrawingView.isInitialized) {
            hiddenDrawingView.clearAllSessions()
        }
        
        // 清理书写内容列表
        writingContentList.forEach { it.recycle() }
        writingContentList.clear()
        
        // 清理延迟任务
        undoDelayedRunnable?.let { handler.removeCallbacks(it) }
        undoDelayedRunnable = null
        
        // 清理长按相关的任务和Handler
        leftBottomLongPressRunnable?.let { leftBottomLongPressHandler.removeCallbacks(it) }
        leftTopLongPressRunnable?.let { leftTopLongPressHandler.removeCallbacks(it) }
        leftBottomLongPressRunnable = null
        leftTopLongPressRunnable = null
        leftBottomLongPressHandler.removeCallbacksAndMessages(null)
        leftTopLongPressHandler.removeCallbacksAndMessages(null)
        
        // 清理保存按钮相关的任务和Handler
        longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
        longPressRunnable = null
        longPressHandler.removeCallbacksAndMessages(null)
        
        swapModeComboRunnable?.let { swapModeComboHandler.removeCallbacks(it) }
        swapModeComboRunnable = null
        swapModeComboHandler.removeCallbacksAndMessages(null)
        
        // 清理保存按钮状态监控
        handler.removeCallbacksAndMessages(null)
        
        drawingSocketManager.release()
        bluetoothManager.release()
    }

    private fun startBlinkAnimation(isConnected: Boolean) {
        statusIndicator.visibility = View.VISIBLE
        var isLightGray = true
        blinkAnimation.apply {
            removeAllUpdateListeners()  // 清除之前的监听器
            addUpdateListener { animator ->
                if ((animator.animatedFraction * 4).toInt() > (animator.animatedFraction * 4 - 0.5).toInt()) {
                    // 使用灰色和深灰色交替，在白色和黑色背景上都可见
                    statusIndicator.setTextColor(if (isLightGray) Color.GRAY else Color.DKGRAY)
                    isLightGray = !isLightGray
                }
            }
            start()
        }
    }

} 