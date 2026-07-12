package com.example.newdrawingapp

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import kotlin.math.sqrt
import kotlin.random.Random
import java.util.*

class HiddenDrawingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var drawPath: Path = Path()
    private var drawPaint: Paint = Paint()
    private var canvasBitmap: Bitmap? = null
    private var drawCanvas: Canvas? = null
    private var isDrawing = false
    private var lastX: Float = 0f
    private var lastY: Float = 0f
    private var activeInputMode = InputMode.NONE
    
    // 缩放比例，用于坐标转换
    private var scaleX: Float = 1f
    private var scaleY: Float = 1f
    
    // 书写会话管理（现在每次书写都是独立的）
    private var currentSession: WritingSession? = null
    private val sessionTimeout = 1000L // 1秒超时
    private val continueTimeout = 300L // 0.3秒内可以继续书写
    private val handler = Handler(Looper.getMainLooper())
    private var sessionTimeoutRunnable: Runnable? = null
    // 容错：无动作强制完成
    private val inactivityTimeout = 2000L // 2秒无动作强制完成
    private var inactivityRunnable: Runnable? = null
    private var lastActivityAt: Long = 0L
    
    // 笔刷类型管理
    enum class BrushType {
        NORMAL,    // 普通笔刷
        CHALK      // 粉笔笔刷
    }
    private var currentBrushType = BrushType.NORMAL
    
    // 添加对象池
    private class ObjectPool<T>(private val maxSize: Int, private val factory: () -> T) {
        private val pool = ArrayList<T>()
        
        fun acquire(): T {
            val size = pool.size
            return if (size > 0) {
                pool.removeAt(size - 1)
            } else {
                factory()
            }
        }
        
        fun release(obj: T) {
            if (pool.size < maxSize) {
                pool.add(obj)
            }
        }
    }
    
    // 路径点数据类
    private data class PathPoint(
        var x: Float,
        var y: Float,
        var width: Float
    )
    
    // 高级粉笔参数 - 优化为DPT RP1兼容版本，减少复杂度防止死机
    private class ChalkParams {
        var textureSize = 300           // 减少纹理尺寸：从400改为300，降低内存占用
        var textureAlpha = 200          // 提高纹理透明度：从180改为200，减少空隙感但保持效果
        var textureDensity = 180.0f     // 增加纹理密度：从150.0f增加到180.0f，增加20%密度
        var strokeBlur = 0.0f
        var jitterStrength = 0.015f     // 减少抖动强度：从0.02f改为0.015f，降低计算复杂度
        var pressureEffect = 1.1f       // 减少压力效果：从1.2f改为1.1f
        var grainSize = 0.0025f         // 稍微增大颗粒：从0.002f改为0.0025f
        var grainDensity = 60.0f        // 增加颗粒密度：从50.0f增加到60.0f，增加20%密度
        var squareSize = 0.025f         // 稍微增大方块：从0.02f改为0.025f
        var squaresPerTouch = 6         // 增加方形粉尘数量：从5增加到6，增加20%密度
        var tinyDustSize = 0.06f        // 稍微增大细小粉尘：从0.05f改为0.06f
        var tinyDustCount = 10          // 增加细小粉尘数量：从8增加到10，增加25%密度
        var tinyDustSpread = 0.2f       // 减少扩散范围：从0.25f改为0.2f，降低计算复杂度
        var tinyDustAlpha = 50          // 提高透明度：从40改为50，减少空隙感
        var startEndDustSize = 0.035f   // 稍微增大起止点粉尘：从0.03f改为0.035f
        var startEndDustCount = 12      // 增加起止点粉尘数量：从10增加到12，增加20%密度
        var startEndDustSpread = 0.25f  // 减少起止点扩散：从0.3f改为0.25f
        var startEndDustAlpha = 140     // 提高起止点透明度：从120改为140
        var chalkJitter = 0.15f         // 减少抖动：从0.2f改为0.15f，降低计算复杂度
        
        // 边缘不规则效果参数 - 优化为DPT RP1兼容
        var edgeRoughness = 0.3f        // 减少边缘粗糙度：从0.4f改为0.3f
        var edgeVariation = 0.12f       // 增加边缘变化幅度：从0.1f增加到0.12f，增加20%变化
        var strokeWidthVariation = 0.1f // 进一步减少笔触宽度变化，让粗细更固定：从0.3f改为0.1f
    }
    
    private val chalkParams = ChalkParams()
    private val random = Random.Default
    private var chalkTexture: Bitmap? = null
    private val chalkPaint = Paint()
    private val tempPaint = Paint()
    private var cachedChalkShader: BitmapShader? = null
    
    // 对象池
    private val pointPool = ObjectPool<PathPoint>(100) { PathPoint(0f, 0f, 0f) }
    private val pathPool = ObjectPool<Path>(20) { Path() }
    
    // 当前路径点列表
    private val currentPoints = mutableListOf<PathPoint>()
    
    // 触摸容差
    private val TOUCH_TOLERANCE = 2f
    private val TOUCH_OUT_OF_BOUNDS_TOLERANCE = 150f
    
    // 书写会话数据类
    data class WritingSession(
        val paths: MutableList<Path> = mutableListOf(),
        val bitmap: Bitmap? = null,
        var isCompleted: Boolean = false
    )
    
    // 回调接口
    interface OnWritingCompletedListener {
        fun onWritingCompleted(sessionBitmap: Bitmap)
        fun onRequestBackgroundColorDetection(): Boolean // 请求检测背景色，返回是否为白色背景
        fun getCurrentWritingCount(): Int // 获取当前书写数量
    }
    private enum class InputMode {
        NONE,
        STYLUS
    }
    
    private var onWritingCompletedListener: OnWritingCompletedListener? = null
    
    init {
        setupDrawing()
    }
    
    private fun setupDrawing() {
        drawPaint.apply {
            color = Color.BLACK
            isAntiAlias = true // 精确的抗锯齿控制
            strokeWidth = 12f // 全分辨率画布需要更粗的笔刷：3f * 4 = 12f
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND // 圆角连接
            strokeCap = Paint.Cap.ROUND // 圆形端点
            isFilterBitmap = false // 关闭位图过滤，避免羽化
            isDither = false // 关闭抖动，避免模糊
            // 设置精确的抗锯齿参数
            pathEffect = null // 确保没有路径效果
        }
        
        // 初始化粉笔画笔
        chalkPaint.apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND  // 改为圆形端点，减少锯齿
            strokeJoin = Paint.Join.ROUND  // 圆角连接，更圆滑
            isAntiAlias = true // 开启抗锯齿，减少锯齿效果
            isFilterBitmap = true // 开启位图过滤
            isDither = true // 开启抖动
        }
        
        // 初始化临时画笔
        tempPaint.apply {
            isAntiAlias = true // 开启抗锯齿，减少锯齿效果
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND // 圆角连接，更圆滑
            strokeCap = Paint.Cap.ROUND // 圆形端点，更圆滑
            isFilterBitmap = true // 开启位图过滤
            isDither = true // 开启抖动
        }
        
        // 创建粉笔纹理
        createChalkTexture()
        
        // 初始设置视图为透明背景
        setBackgroundColor(Color.TRANSPARENT)
    }
    
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            // 使用全分辨率画布，避免放大失真
            val fullWidth = 1650  // DPT RP1 全分辨率宽度
            val fullHeight = 2200 // DPT RP1 全分辨率高度
            
            try {
                canvasBitmap = Bitmap.createBitmap(fullWidth, fullHeight, Bitmap.Config.ARGB_8888).apply {
                    density = Bitmap.DENSITY_NONE // 避免密度缩放
                    setHasAlpha(true)
                }
                drawCanvas = Canvas(canvasBitmap!!).apply {
                    // 设置精确的抗锯齿模式，避免羽化
                    val paintFlags = Paint.ANTI_ALIAS_FLAG // 只使用抗锯齿，避免过度过滤
                    drawFilter = android.graphics.PaintFlagsDrawFilter(
                        Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG, // 清除这些标志
                        paintFlags // 只保留抗锯齿
                    )
                }
                drawCanvas?.drawColor(Color.TRANSPARENT)
            } catch (e: OutOfMemoryError) {
                Log.e("HiddenDrawingView", "初始化画布时内存不足，尝试创建较小画布: ${e.message}")
                try {
                    // 降级：使用一半尺寸
                    canvasBitmap = Bitmap.createBitmap(fullWidth / 2, fullHeight / 2, Bitmap.Config.ARGB_8888).apply {
                        density = Bitmap.DENSITY_NONE
                        setHasAlpha(true)
                    }
                    drawCanvas = Canvas(canvasBitmap!!).apply {
                        val paintFlags = Paint.ANTI_ALIAS_FLAG
                        drawFilter = android.graphics.PaintFlagsDrawFilter(
                            Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG,
                            paintFlags
                        )
                    }
                    drawCanvas?.drawColor(Color.TRANSPARENT)
                    Log.w("HiddenDrawingView", "使用降级尺寸画布: ${canvasBitmap!!.width}x${canvasBitmap!!.height}")
                } catch (e2: OutOfMemoryError) {
                    Log.e("HiddenDrawingView", "创建降级画布仍然内存不足，绘画功能不可用: ${e2.message}")
                    canvasBitmap = null
                    drawCanvas = null
                }
            }
            
            // 记录缩放比例，用于坐标转换
            scaleX = fullWidth.toFloat() / w
            scaleY = fullHeight.toFloat() / h
            
            Log.d("HiddenDrawingView", "全分辨率画布设置完成: ${fullWidth}x${fullHeight}, 视图尺寸: ${w}x${h}, 缩放比例: ${scaleX}x${scaleY}")
        }
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvasBitmap?.let { bitmap ->
            // 创建缩放矩阵，将全分辨率画布缩放到视图大小
            val matrix = Matrix()
            matrix.setScale(1f / scaleX, 1f / scaleY)
            
            // 创建精确的绘制画笔，避免羽化
            val bitmapPaint = Paint().apply {
                isAntiAlias = true // 保留抗锯齿
                isFilterBitmap = true // 开启过滤，因为需要缩小显示
                isDither = false // 关闭抖动，保持边缘清晰
            }
            canvas.drawBitmap(bitmap, matrix, bitmapPaint)
        }
        
        // 当前正在绘制的路径应该直接绘制到内部画布上，而不是显示画布
        // 这里不需要绘制当前路径，因为它会实时更新到canvasBitmap中
    }
    
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // SONY DPT RP1兼容性：检查视图是否可用和可见
        if (!isEnabled || visibility != View.VISIBLE) {
            Log.w("HiddenDrawingView", "视图不可用或不可见，isEnabled=$isEnabled, visibility=$visibility")
            return false
        }
        
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        val rawX = event.x
        val rawY = event.y
        
        // 如果触摸点超过可容忍范围（极端误触），直接忽略
        var clampedViewX = rawX
        var clampedViewY = rawY
        
        // 如果触摸点超过可容忍范围（极端误触），依旧将其截取到边界，维持连贯绘制
        if (rawX < -TOUCH_OUT_OF_BOUNDS_TOLERANCE || rawX > viewWidth + TOUCH_OUT_OF_BOUNDS_TOLERANCE ||
            rawY < -TOUCH_OUT_OF_BOUNDS_TOLERANCE || rawY > viewHeight + TOUCH_OUT_OF_BOUNDS_TOLERANCE) {
            Log.w(
                "HiddenDrawingView",
                "触摸点超出可容忍范围: ($rawX, $rawY) vs 视图尺寸 ${width}x${height}，强制裁剪到边界"
            )
            clampedViewX = rawX.coerceIn(0f, viewWidth)
            clampedViewY = rawY.coerceIn(0f, viewHeight)
        } else {
            // 将触摸点限制在视图范围内，避免书写被截断
            val adjustedX = rawX.coerceIn(0f, viewWidth)
            val adjustedY = rawY.coerceIn(0f, viewHeight)
            if (adjustedX != rawX || adjustedY != rawY) {
                Log.w(
                    "HiddenDrawingView",
                    "触摸点超出视图范围: ($rawX, $rawY) vs 视图尺寸 ${width}x${height}，自动调整为 ($adjustedX, $adjustedY)"
                )
            }
            clampedViewX = adjustedX
            clampedViewY = adjustedY
        }
        
        // 将触摸坐标转换为全分辨率画布坐标
        val x = clampedViewX * scaleX
        val y = clampedViewY * scaleY
        
        Log.d("HiddenDrawingView", "坐标转换: 触摸(${event.x}, ${event.y}) -> 全分辨率($x, $y), 缩放比例: ${scaleX}x${scaleY}")
        
        // SONY DPT RP1特殊处理：电子纸设备的触摸事件处理
        Log.d("HiddenDrawingView", "DPT触摸事件: action=${event.action}, actionMasked=${event.action and MotionEvent.ACTION_MASK}, x=$x, y=$y, 视图尺寸: ${width}x${height}")
        
        // 书写框内所有输入（手指或笔）均作为书写处理
        // 擦除逻辑由 imageView 的触摸监听器负责，此处不区分工具类型
        val actionMasked = event.actionMasked
        val actionIndex = event.actionIndex.coerceIn(0, event.pointerCount - 1)
        val toolType = event.getToolType(actionIndex)
        Log.d("HiddenDrawingView", "书写框触摸: actionMasked=$actionMasked, toolType=$toolType, pos=($x, $y)")
        when (actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeInputMode = InputMode.STYLUS
                Log.d("HiddenDrawingView", "开始书写: ($x, $y), toolType=$toolType")
                startDrawing(x, y)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                when (activeInputMode) {
                    InputMode.STYLUS -> {
                        if (isDrawing) {
                            val historySize = event.historySize
                            for (i in 0 until historySize) {
                                val historicalViewX = event.getHistoricalX(i).coerceIn(0f, viewWidth)
                                val historicalViewY = event.getHistoricalY(i).coerceIn(0f, viewHeight)
                                val historicalX = historicalViewX * scaleX
                                val historicalY = historicalViewY * scaleY
                                continueDrawing(historicalX, historicalY)
                            }
                            continueDrawing(x, y)
                        } else {
                            Log.w("HiddenDrawingView", "收到MOVE事件但isDrawing=false，尝试恢复绘制状态")
                            startDrawing(x, y)
                            continueDrawing(x, y)
                        }
                        return true
                    }
                    InputMode.NONE -> {
                        // 兼容异常序列：没有DOWN事件时兜底
                        activeInputMode = InputMode.STYLUS
                        startDrawing(x, y)
                        continueDrawing(x, y)
                        return true
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (activeInputMode == InputMode.STYLUS) {
                    Log.d("HiddenDrawingView", "书写结束: ($x, $y), action=$actionMasked")
                    finishDrawing()
                }
                activeInputMode = InputMode.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
            else -> {
                Log.d("HiddenDrawingView", "DPT其他触摸事件: $actionMasked")
                return false
            }
        }
    }
    
    private fun startDrawing(x: Float, y: Float) {
        // DPT RP1优化：检查内存状态，防止死机
        checkMemoryStatus()
        
        // 在开始绘画前检查内存，如果内存不足则先清理
        val memoryUsage = getMemoryUsage()
        if (memoryUsage > 0.85f) {
            Log.w("HiddenDrawingView", "开始绘画时内存使用率过高(${(memoryUsage * 100).toInt()}%)，强制清理")
            System.gc()
            System.runFinalization()
            System.gc()
        }
        
        // 取消之前的超时任务
        sessionTimeoutRunnable?.let { handler.removeCallbacks(it) }
        
        // 在开始绘画前重新检测背景色并设置画笔颜色
        onWritingCompletedListener?.let { listener ->
            val isWhiteBackground = listener.onRequestBackgroundColorDetection()
            setAutoPenColor(isWhiteBackground)
        }
        
        // 如果当前没有会话或者上一个会话已经完成，创建新会话
        if (currentSession == null || currentSession?.isCompleted == true) {
            currentSession = WritingSession()
            Log.d("HiddenDrawingView", "创建新的书写会话")
        } else {
            Log.d("HiddenDrawingView", "继续当前书写会话，已有${currentSession?.paths?.size ?: 0}笔")
        }
        
        if (currentBrushType == BrushType.CHALK) {
            // DPT RP1优化：确保粉笔纹理存在
            if (chalkTexture == null || cachedChalkShader == null) {
                Log.d("HiddenDrawingView", "粉笔绘制时检测到纹理缺失，重新创建")
                createChalkTexture()
            }
            
            // 粉笔模式：清空当前点列表，开始收集路径点
            currentPoints.clear()
            val width = drawPaint.strokeWidth * 1.5f // 粉笔笔刷加粗1.75倍（从2.5倍缩小为70%）
            val newPoint = pointPool.acquire()
            newPoint.x = x
            newPoint.y = y
            newPoint.width = width
            currentPoints.add(newPoint)
            
            // 添加起始点粉尘效果
            drawCanvas?.let { canvas ->
                addStartEndDust(canvas, newPoint, drawPaint, true)
            }
        } else {
            // 普通笔刷模式
            drawPath.reset()
            drawPath.moveTo(x, y)
            
            // 在起始点绘制一个小圆点，确保有内容显示
            drawCanvas?.let { canvas ->
                canvas.drawCircle(x, y, drawPaint.strokeWidth / 2f, drawPaint)
            }
        }
        
        lastX = x
        lastY = y
        isDrawing = true
        // 记录活动时间并启动无动作定时器
        lastActivityAt = System.currentTimeMillis()
        scheduleInactivityWatchdog()
        
        Log.d("HiddenDrawingView", "开始绘画: ($x, $y), 当前画笔颜色: ${Integer.toHexString(drawPaint.color)}, 笔刷类型: $currentBrushType")
    }
    
    private fun continueDrawing(x: Float, y: Float) {
        if (isDrawing) {
            if (currentBrushType == BrushType.CHALK) {
                // 粉笔模式：收集路径点
                val width = drawPaint.strokeWidth * 1.5f // 粉笔笔刷加粗1.75倍（从2.5倍缩小为70%）
                if (currentPoints.isNotEmpty()) {
                    val lastPoint = currentPoints.last()
                    val dx = x - lastPoint.x
                    val dy = y - lastPoint.y
                    val distance = kotlin.math.sqrt((dx * dx + dy * dy).toDouble())
                    
                    if (distance > TOUCH_TOLERANCE) {
                        // 进一步减少抖动
                        val jitterX = (random.nextFloat() - 0.5f) * chalkParams.chalkJitter * drawPaint.strokeWidth * 0.15f
                        val jitterY = (random.nextFloat() - 0.5f) * chalkParams.chalkJitter * drawPaint.strokeWidth * 0.15f
                        
                        // 使用贝塞尔曲线平滑处理
                        val midX = (x + lastPoint.x) / 2
                        val midY = (y + lastPoint.y) / 2
                        
                        val newPoint = pointPool.acquire()
                        newPoint.x = midX + jitterX
                        newPoint.y = midY + jitterY
                        newPoint.width = width * (0.99f + random.nextFloat() * 0.02f) // 进一步减小宽度变化
                        currentPoints.add(newPoint)
                        
                        val pointCount = currentPoints.size
                        if (pointCount >= 2) {
                            val fromIndex = kotlin.math.max(0, pointCount - 5) // 增加处理的点数
                            drawCanvas?.let { canvas ->
                                drawChalkPath(canvas, currentPoints.subList(fromIndex, pointCount), drawPaint)
                            }
                        }
                        
                        lastX = x
                        lastY = y
                        invalidate()
                    }
                }
            } else {
                // 普通笔刷模式：使用距离判断，确保横着和竖着的笔画都能正常显示
                val dx = x - lastX
                val dy = y - lastY
                val distance = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                
                // 进一步降低阈值到0.1f，确保即使很短的横线也能被捕获
                // 使用距离判断而不是分别判断dx和dy，避免横着画时被跳过
                if (distance > 0.1f) {
                    // 绘制线段到画布上
                    drawCanvas?.let { canvas ->
                        canvas.drawLine(lastX, lastY, x, y, drawPaint)
                        Log.v("HiddenDrawingView", "绘制线段到画布: ($lastX, $lastY) -> ($x, $y), 距离=$distance")
                    } ?: Log.w("HiddenDrawingView", "drawCanvas为null，无法绘制")
                    
                    drawPath.quadTo(lastX, lastY, (x + lastX) / 2, (y + lastY) / 2)
                    lastX = x
                    lastY = y
                    
                    invalidate()
                } else if (distance > 0.001f) {
                    // 即使距离很小（但大于0.001），也至少绘制一个小圆点，确保短横线也能显示
                    // 使用strokeWidth的一半作为半径，确保点足够明显
                    drawCanvas?.let { canvas ->
                        val pointRadius = drawPaint.strokeWidth / 2f
                        canvas.drawCircle(x, y, pointRadius, drawPaint)
                        Log.v("HiddenDrawingView", "绘制小点: ($x, $y), 距离=$distance, 半径=$pointRadius")
                    }
                    lastX = x
                    lastY = y
                    invalidate()
                } else {
                    // 距离极小（可能是同一个点），但也要确保至少绘制一个点，避免短横线消失
                    // 这种情况通常发生在用户快速画短横线时，多个MOVE事件坐标几乎相同
                    drawCanvas?.let { canvas ->
                        val pointRadius = drawPaint.strokeWidth / 3f
                        canvas.drawCircle(x, y, pointRadius, drawPaint)
                        Log.v("HiddenDrawingView", "绘制极小点: ($x, $y), 距离=$distance")
                    }
                    lastX = x
                    lastY = y
                    invalidate()
                }
            }
        }
        // 每次移动都刷新活动时间与无动作定时器
        lastActivityAt = System.currentTimeMillis()
        scheduleInactivityWatchdog()
    }
    
    private fun finishDrawing() {
        if (isDrawing) {
            if (currentBrushType == BrushType.CHALK) {
                // 粉笔模式：处理结束点粉尘效果
                if (currentPoints.isNotEmpty()) {
                    val lastPoint = currentPoints.last()
                    drawCanvas?.let { canvas ->
                        addStartEndDust(canvas, lastPoint, drawPaint, false)
                    }
                    
                    // 创建一个虚拟路径添加到会话中，确保会话不为空
                    val dummyPath = Path()
                    dummyPath.moveTo(currentPoints.first().x, currentPoints.first().y)
                    dummyPath.lineTo(lastPoint.x, lastPoint.y)
                    
                    currentSession?.let { session ->
                        session.paths.add(dummyPath)
                        Log.d("HiddenDrawingView", "粉笔模式：虚拟路径添加到会话，当前会话路径数: ${session.paths.size}")
                    } ?: Log.w("HiddenDrawingView", "粉笔模式：currentSession为null，无法添加路径")
                    
                    // 释放路径点到对象池
                    for (point in currentPoints) {
                        pointPool.release(point)
                    }
                    currentPoints.clear()
                }
            } else {
                // 普通笔刷模式：完成当前路径的绘制
                // 确保最后一段也被绘制，即使距离很小（处理短横线的情况）
                drawPath.lineTo(lastX, lastY)
                drawCanvas?.let { canvas ->
                    canvas.drawPath(drawPath, drawPaint)
                    
                    // 如果路径为空或很短，确保至少绘制一个点（处理快速画短横线的情况）
                    val pathBounds = android.graphics.RectF()
                    drawPath.computeBounds(pathBounds, true)
                    val pathWidth = pathBounds.width()
                    val pathHeight = pathBounds.height()
                    val pathSize = kotlin.math.sqrt((pathWidth * pathWidth + pathHeight * pathHeight).toDouble()).toFloat()
                    
                    if (pathSize < drawPaint.strokeWidth * 2) {
                        // 路径很短，确保在最后位置绘制一个点
                        canvas.drawCircle(lastX, lastY, drawPaint.strokeWidth / 2f, drawPaint)
                        Log.d("HiddenDrawingView", "路径很短($pathSize)，在结束位置绘制点: ($lastX, $lastY)")
                    }
                    
                    Log.d("HiddenDrawingView", "最终绘制路径到画布，路径大小: $pathSize")
                } ?: Log.w("HiddenDrawingView", "finishDrawing: drawCanvas为null")
                
                // 将路径添加到当前会话
                currentSession?.let { session ->
                    session.paths.add(Path(drawPath))
                    Log.d("HiddenDrawingView", "路径添加到会话，当前会话路径数: ${session.paths.size}")
                } ?: Log.w("HiddenDrawingView", "currentSession为null，无法添加路径")
            }
            
            isDrawing = false
            invalidate()
            
            Log.d("HiddenDrawingView", "完成一笔绘画，笔刷类型: $currentBrushType")
            
            // 设置超时检查，1秒内没有新的绘画则完成会话
            sessionTimeoutRunnable = Runnable {
                completeCurrentSession()
            }
            handler.postDelayed(sessionTimeoutRunnable!!, sessionTimeout)
            // 抬笔后不再需要无动作定时器
            inactivityRunnable?.let { handler.removeCallbacks(it) }
            inactivityRunnable = null
        } else {
            Log.w("HiddenDrawingView", "finishDrawing被调用但isDrawing=false")
        }
    }
    
    private fun completeCurrentSession() {
        currentSession?.let { session ->
            if (!session.isCompleted && session.paths.isNotEmpty()) {
                session.isCompleted = true
                
                Log.d("HiddenDrawingView", "完成书写会话，共${session.paths.size}笔，笔刷类型: $currentBrushType")
                
                try {
                    // 首先创建当前会话的位图（在清空画布之前）
                    val currentWritingCount = onWritingCompletedListener?.getCurrentWritingCount() ?: 0
                    val sessionBitmap = createSessionBitmap(session, currentWritingCount)
                    
                    // 检查位图是否有内容
                    val hasContent = checkBitmapHasContent(sessionBitmap)
                    Log.d("HiddenDrawingView", "会话位图检查结果: hasContent=$hasContent, 尺寸: ${sessionBitmap.width}x${sessionBitmap.height}")
                    
                    if (hasContent) {
                        // 然后立即清除当前画布和所有绘制内容
                        clearCurrentCanvas()
                        invalidate() // 强制重绘，清除残影
                        
                        // 最后通知监听器会话完成，传递位图用于全屏显示
                        onWritingCompletedListener?.onWritingCompleted(sessionBitmap)
                    } else {
                        Log.w("HiddenDrawingView", "会话位图为空，不触发完成回调")
                    }
                } catch (e: OutOfMemoryError) {
                    Log.e("HiddenDrawingView", "完成会话时内存不足，清空画布并跳过此次书写: ${e.message}")
                    // 清空画布，避免下次绘画累积
                    clearCurrentCanvas()
                    invalidate()
                    // 不通知监听器，静默跳过这次书写
                } catch (e: Exception) {
                    Log.e("HiddenDrawingView", "完成会话时出错: ${e.message}")
                    clearCurrentCanvas()
                    invalidate()
                }
            } else {
                Log.w("HiddenDrawingView", "会话已完成或路径为空，不处理: isCompleted=${session.isCompleted}, pathsSize=${session.paths.size}")
            }
        } ?: Log.w("HiddenDrawingView", "currentSession为null，无法完成会话")
    }
    
    private fun checkBitmapHasContent(bitmap: Bitmap): Boolean {
        // 快速检查位图是否有内容
        for (x in 0 until bitmap.width step 10) {
            for (y in 0 until bitmap.height step 10) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.alpha(pixel) > 0) {
                    return true
                }
            }
        }
        return false
    }
    
    // 清空当前会话，准备下一次绘画
    
    private fun createSessionBitmap(session: WritingSession, currentWritingCount: Int = 0): Bitmap {
        // 检查内存使用率
        val memoryUsage = getMemoryUsage()
        if (memoryUsage > 0.85f) {
            Log.w("HiddenDrawingView", "创建会话位图前内存使用率过高(${(memoryUsage * 100).toInt()}%)，强制清理")
            System.gc()
            Thread.sleep(50) // 给 GC 一点时间
        }
        
        // 使用全分辨率创建会话位图，避免失真
        val fullWidth = 1650
        val fullHeight = 2200
        val bitmap = try {
            Bitmap.createBitmap(fullWidth, fullHeight, Bitmap.Config.ARGB_8888).apply {
                density = Bitmap.DENSITY_NONE // 避免密度缩放
                setHasAlpha(true)
            }
        } catch (e: OutOfMemoryError) {
            Log.e("HiddenDrawingView", "创建全分辨率会话位图时内存不足，尝试创建较小尺寸: ${e.message}")
            // 降级：尝试创建一半尺寸的位图
            try {
                Bitmap.createBitmap(fullWidth / 2, fullHeight / 2, Bitmap.Config.ARGB_8888).apply {
                    density = Bitmap.DENSITY_NONE
                    setHasAlpha(true)
                }
            } catch (e2: OutOfMemoryError) {
                Log.e("HiddenDrawingView", "创建降级尺寸位图仍然内存不足: ${e2.message}")
                throw e2 // 重新抛出，让调用者处理
            }
        }
        val canvas = Canvas(bitmap).apply {
            // 设置精确的抗锯齿模式，避免羽化
            val paintFlags = Paint.ANTI_ALIAS_FLAG // 只使用抗锯齿
            drawFilter = android.graphics.PaintFlagsDrawFilter(
                Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG, // 清除这些标志
                paintFlags // 只保留抗锯齿
            )
        }
        canvas.drawColor(Color.TRANSPARENT)
        
        // 检测是否使用了降级尺寸
        val isDownscaled = (bitmap.width < fullWidth)
        val scale = if (isDownscaled) 0.5f else 1.0f
        
        Log.d("HiddenDrawingView", "创建会话位图，路径数量: ${session.paths.size}, 画笔颜色: ${Integer.toHexString(drawPaint.color)}, 尺寸: ${bitmap.width}x${bitmap.height}, 缩放: $scale")
        
        // 根据当前书写数量计算笔迹加粗系数
        val thickenMultiplier = calculateThickenMultiplier(currentWritingCount)
        Log.d("HiddenDrawingView", "当前书写数量: $currentWritingCount, 加粗系数: $thickenMultiplier")
        
        // 如果使用降级尺寸，应用缩放
        if (isDownscaled) {
            canvas.scale(scale, scale)
        }
        
        // 对于粉笔模式，我们直接复制画布内容，因为粉笔效果已经在绘制时应用了
        if (currentBrushType == BrushType.CHALK) {
            // 粉笔模式：直接从隐藏画布复制内容
            drawCanvas?.let { sourceCanvas ->
                val sourceBitmap = canvasBitmap
                if (sourceBitmap != null && !sourceBitmap.isRecycled) {
                    canvas.drawBitmap(sourceBitmap, 0f, 0f, null)
                    Log.d("HiddenDrawingView", "粉笔模式：从隐藏画布复制内容，源位图尺寸: ${sourceBitmap.width}x${sourceBitmap.height}")
                } else {
                    Log.w("HiddenDrawingView", "粉笔模式：源位图为null或已回收，sourceBitmap=$sourceBitmap")
                }
            } ?: Log.w("HiddenDrawingView", "粉笔模式：drawCanvas为null")
        } else {
            // 普通笔刷模式：重新绘制路径
            for ((index, path) in session.paths.withIndex()) {
                val enhancedPaint = Paint(drawPaint).apply {
                    alpha = 255 // 确保完全不透明
                    val baseWidth = drawPaint.strokeWidth // 使用您设置的原始粗细
                    strokeWidth = baseWidth * thickenMultiplier // 应用加粗系数
                }
                canvas.drawPath(path, enhancedPaint)
                Log.d("HiddenDrawingView", "普通笔刷绘制路径 $index, 画笔设置: width=${enhancedPaint.strokeWidth}, 加粗系数=${thickenMultiplier}")
            }
        }
        
        // 改进内容检测：扫描更多点，并检查所有颜色通道
        var hasContent = false
        var checkedPixels = 0
        var nonTransparentPixels = 0
        
        // 扫描更密集的网格点
        for (x in 0 until width step 20) {
            for (y in 0 until height step 20) {
                checkedPixels++
                val pixel = bitmap.getPixel(x, y)
                val alpha = Color.alpha(pixel)
                val red = Color.red(pixel)
                val green = Color.green(pixel)
                val blue = Color.blue(pixel)
                
                // 检查是否有任何非透明的像素（包括白色）
                if (alpha > 0 && (red > 0 || green > 0 || blue > 0)) {
                    nonTransparentPixels++
                    hasContent = true
                    Log.v("HiddenDrawingView", "检测到内容在($x,$y): ARGB($alpha,$red,$green,$blue)")
                }
            }
            if (hasContent) break // 一旦找到内容就停止
        }
        
        Log.d("HiddenDrawingView", "位图创建完成，检测到内容: $hasContent, 检查像素: $checkedPixels, 非透明像素: $nonTransparentPixels")
        return bitmap
    }
    
    private fun calculateThickenMultiplier(writingCount: Int): Float {
        // 使用固定的加粗系数，让所有书写粗细保持一致
        return 1.2f.also { multiplier ->
            Log.d("HiddenDrawingView", "使用固定加粗系数: $multiplier (书写数量: $writingCount)")
        }
        
        // 原来的动态加粗代码已注释，如需恢复可取消注释
        /*
        // 根据书写数量计算加粗系数（原逻辑）
        return when (writingCount) {
            0 -> 1.0f      // 第1次书写：原始粗细
            1 -> 1.2f      // 第2次书写：轻微加粗
            2 -> 1.4f      // 第3次书写：适度加粗  
            3 -> 1.6f      // 第4次书写：明显加粗
            else -> 1.8f   // 第5次及以上：最大加粗
        }.also { multiplier ->
            Log.d("HiddenDrawingView", "书写数量 $writingCount -> 加粗系数 $multiplier")
        }
        */
    }
    
    // 这个方法不再需要，因为我们不再累积会话
    
    private fun clearCurrentCanvas() {
        if (width > 0 && height > 0) {
            canvasBitmap?.recycle() // 回收旧的位图
            
            // 检查内存状态，如果内存不足则使用较小的画布
            val memoryUsage = getMemoryUsage()
            val fullWidth = 1650
            val fullHeight = 2200
            
            try {
                // 如果内存使用率超过85%，使用较小的画布尺寸
                val canvasWidth = if (memoryUsage > 0.85f) {
                    Log.w("HiddenDrawingView", "内存使用率过高(${(memoryUsage * 100).toInt()}%)，使用较小画布")
                    (fullWidth * 0.7f).toInt() // 使用70%的尺寸
                } else {
                    fullWidth
                }
                
                val canvasHeight = if (memoryUsage > 0.85f) {
                    (fullHeight * 0.7f).toInt() // 使用70%的尺寸
                } else {
                    fullHeight
                }
                
                canvasBitmap = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888).apply {
                    density = Bitmap.DENSITY_NONE
                    setHasAlpha(true)
                }
                drawCanvas = Canvas(canvasBitmap!!).apply {
                    val paintFlags = Paint.ANTI_ALIAS_FLAG
                    drawFilter = android.graphics.PaintFlagsDrawFilter(
                        Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG,
                        paintFlags
                    )
                }
                drawCanvas?.drawColor(Color.TRANSPARENT)
                
                // 重要：更新缩放比例以匹配新的画布尺寸
                scaleX = canvasWidth.toFloat() / width
                scaleY = canvasHeight.toFloat() / height
                
                Log.d("HiddenDrawingView", "画布已创建，尺寸: ${canvasWidth}x${canvasHeight}, 内存使用率: ${(memoryUsage * 100).toInt()}%, 缩放比例: ${scaleX}x${scaleY}")
                
            } catch (e: OutOfMemoryError) {
                Log.e("HiddenDrawingView", "创建画布时内存不足，尝试强制清理后重试")
                // 强制清理内存
                System.gc()
                System.runFinalization()
                System.gc()
                
                try {
                    // 使用更小的画布重试
                    val smallWidth = (fullWidth * 0.5f).toInt()
                    val smallHeight = (fullHeight * 0.5f).toInt()
                    canvasBitmap = Bitmap.createBitmap(smallWidth, smallHeight, Bitmap.Config.ARGB_8888).apply {
                        density = Bitmap.DENSITY_NONE
                        setHasAlpha(true)
                    }
                    drawCanvas = Canvas(canvasBitmap!!).apply {
                        val paintFlags = Paint.ANTI_ALIAS_FLAG
                        drawFilter = android.graphics.PaintFlagsDrawFilter(
                            Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG,
                            paintFlags
                        )
                    }
                    drawCanvas?.drawColor(Color.TRANSPARENT)
                    
                    // 重要：更新缩放比例以匹配小尺寸画布
                    scaleX = smallWidth.toFloat() / width
                    scaleY = smallHeight.toFloat() / height
                    
                    Log.w("HiddenDrawingView", "使用小尺寸画布重试成功: ${smallWidth}x${smallHeight}, 缩放比例: ${scaleX}x${scaleY}")
                } catch (e2: OutOfMemoryError) {
                    Log.e("HiddenDrawingView", "即使使用小尺寸画布仍然内存不足，跳过画布创建")
                    canvasBitmap = null
                    drawCanvas = null
                    return
                }
            }
            
            // 清空当前绘制路径
            drawPath.reset()
            
            // 重置绘画坐标
            lastX = 0f
            lastY = 0f
            
            // 重要：不要清空currentPoints，保持粉笔模式的连续性
            // 只有在clearAllSessions时才清空currentPoints
            
            Log.d("HiddenDrawingView", "画布已完全清空，重置绘画坐标，画布状态: drawCanvas=${drawCanvas != null}")
            invalidate()
        } else {
            Log.w("HiddenDrawingView", "无法清空画布，尺寸无效: ${width}x${height}")
        }
    }
    
    fun setOnWritingCompletedListener(listener: OnWritingCompletedListener) {
        this.onWritingCompletedListener = listener
    }
    
    fun undoLastSession() {
        // 由于每次书写后都会清空，这里只需要清空当前画布
        clearCurrentCanvas()
        Log.d("HiddenDrawingView", "清空当前画布")
    }
    
    fun clearAllSessions() {
        Log.d("HiddenDrawingView", "开始清除会话，当前会话状态: currentSession=${currentSession != null}, isDrawing=$isDrawing")
        
        currentSession = null
        
        // 取消任何待处理的超时任务
        sessionTimeoutRunnable?.let { handler.removeCallbacks(it) }
        sessionTimeoutRunnable = null
        inactivityRunnable?.let { handler.removeCallbacks(it) }
        inactivityRunnable = null
        
        // 清理粉笔相关资源
        if (currentPoints.isNotEmpty()) {
            for (point in currentPoints) {
                pointPool.release(point)
            }
            currentPoints.clear()
        }
        
        // 重置绘画状态
        isDrawing = false
        
        // 清空当前画布
        clearCurrentCanvas()
        
        // DPT RP1优化：清理粉笔纹理缓存，防止内存泄漏
        chalkTexture?.recycle()
        chalkTexture = null
        cachedChalkShader = null
        
        // 强制垃圾回收
        System.gc()
        
        Log.d("HiddenDrawingView", "DPT RP1优化：清除书写会话完成，已清理纹理缓存")
        
        // 清空完成，准备下一次绘画
    }
    
    fun getSessionCount(): Int = if (currentSession != null) 1 else 0
    
    // 设置画板可见状态
    fun setCanvasVisible(visible: Boolean) {
        // 始终保持透明背景
        setBackgroundColor(Color.TRANSPARENT)
        Log.d("HiddenDrawingView", "画板设置为透明")
    }
    
    // 根据背景色自动设置画笔颜色
    fun setAutoPenColor(isWhiteBackground: Boolean) {
        if (isWhiteBackground) {
            // 白色背景使用黑色画笔
            drawPaint.color = Color.BLACK
            Log.d("HiddenDrawingView", "检测到白色背景，设置黑色画笔")
        } else {
            // 黑色背景使用白色画笔
            drawPaint.color = Color.WHITE
            Log.d("HiddenDrawingView", "检测到黑色背景，设置白色画笔")
        }
    }
    
    fun setBrushType(brushType: BrushType) {
        currentBrushType = brushType
        
        // DPT RP1优化：切换到粉笔模式时自动检查并重新创建纹理
        if (brushType == BrushType.CHALK && (chalkTexture == null || cachedChalkShader == null)) {
            Log.d("HiddenDrawingView", "检测到粉笔纹理缺失，重新创建纹理")
            createChalkTexture()
        }
        
        Log.d("HiddenDrawingView", "设置笔刷类型为: $brushType")
    }
    
    // 添加擦除功能
    // 注意：现在直接接受视图相对坐标（不再是屏幕绝对坐标），因为ClientActivity传递的是ImageView的相对坐标
    // 注意：调用此方法前应该已经检查了坐标是否在范围内，所以这里不再输出超出范围的日志
    fun eraseAtPosition(viewX: Float, viewY: Float, eraserRadius: Float = 30f, isWhiteBackground: Boolean = false) {
        // 检查坐标是否在隐藏画板范围内（双重检查，防止意外调用）
        if (viewX >= 0 && viewX <= width && viewY >= 0 && viewY <= height) {
            // 转换为画布坐标
            val canvasX = viewX * scaleX
            val canvasY = viewY * scaleY
            
            // 在内部画布上执行擦除
            drawCanvas?.let { canvas ->
                val eraserPaint = Paint().apply {
                    // 根据背景色设置擦除颜色：白色背景用白色擦除，黑色背景用黑色擦除
                    color = if (isWhiteBackground) Color.WHITE else Color.BLACK
                    style = Paint.Style.FILL
                    isAntiAlias = true
                    // 移除PorterDuffXfermode，直接使用背景色进行擦除
                }
                canvas.drawCircle(canvasX, canvasY, eraserRadius * scaleX, eraserPaint)
                invalidate() // 重绘视图
                Log.v("HiddenDrawingView", "擦除位置: 视图($viewX, $viewY) -> 画布($canvasX, $canvasY), 半径: ${eraserRadius * scaleX}, 背景色: ${if (isWhiteBackground) "白色" else "黑色"}")
            } ?: Log.w("HiddenDrawingView", "无法擦除：drawCanvas为null")
        }
        // 如果不在范围内，静默跳过（因为调用前已经检查过了）
    }
    
    // 插值擦除，用于处理连续的擦除轨迹
    // 注意：现在直接接受视图相对坐标（不再是屏幕绝对坐标）
    fun interpolateEraseAtPosition(startViewX: Float, startViewY: Float, endViewX: Float, endViewY: Float, eraserRadius: Float = 30f, isWhiteBackground: Boolean = false) {
        val dx = endViewX - startViewX
        val dy = endViewY - startViewY
        val distance = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        val steps = (distance / (eraserRadius / 2)).toInt().coerceAtLeast(1)
        
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val x = startViewX + dx * t
            val y = startViewY + dy * t
            eraseAtPosition(x, y, eraserRadius, isWhiteBackground)
        }
    }
    
    // 安卓5.1兼容性：添加触摸测试方法
    fun testTouchCapability(): Boolean {
        val canReceiveTouch = isEnabled && visibility == View.VISIBLE && isFocusable
        Log.d("HiddenDrawingView", "触摸能力测试: enabled=$isEnabled, visible=${visibility == View.VISIBLE}, focusable=$isFocusable, clickable=$isClickable")
        Log.d("HiddenDrawingView", "视图尺寸: ${width}x${height}, 位置: (${x}, ${y})")
        return canReceiveTouch
    }
    
    private fun createChalkTexture(): Bitmap {
        try {
            val size = chalkParams.textureSize
            val bitmap = try {
                Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            } catch (e: OutOfMemoryError) {
                Log.e("HiddenDrawingView", "创建粉笔纹理时内存不足，使用较小尺寸: ${e.message}")
                // 降级：使用较小纹理
                Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
            }
            val canvas = Canvas(bitmap)
            
            val texturePaint = Paint().apply {
                color = Color.WHITE
                style = Paint.Style.FILL
                alpha = (chalkParams.textureAlpha * 0.7f).toInt() // DPT RP1优化：提高纹理透明度，减少空隙感
                isAntiAlias = true
            }
            
            // 增加颗粒数量，提高纹理密度
            val pointCount = (size * size * chalkParams.grainDensity / 125.0f).toInt() // 从150.0f减少到125.0f，增加20%密度
            val actualPoints = mutableListOf<Float>()
            
            for (i in 0 until pointCount) {
                // 减少随机跳过，增加纹理密度
                if (random.nextFloat() > 0.32f) { // 从0.4f减少到0.32f，68%的点会被绘制，增加20%密度
                    actualPoints.add(random.nextFloat() * size)
                    actualPoints.add(random.nextFloat() * size)
                }
            }
            
            // 转换为数组并绘制
            val points = actualPoints.toFloatArray()
            
            // 使用更小的点绘制更密集的纹理
            texturePaint.strokeWidth = chalkParams.grainSize * size * 1.2f // DPT RP1优化：稍微增大颗粒
            if (points.isNotEmpty()) {
                canvas.drawPoints(points, texturePaint)
            }
            
            chalkTexture = bitmap
            // 更新缓存的Shader
            cachedChalkShader = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            Log.d("HiddenDrawingView", "DPT RP1优化粉笔纹理创建完成，尺寸: ${bitmap.width}x${bitmap.height}, 颗粒数: $pointCount")
            return bitmap
        } catch (e: OutOfMemoryError) {
            Log.e("HiddenDrawingView", "创建粉笔纹理时内存不足: ${e.message}")
            // 创建默认纹理作为备用
            try {
                val fallbackBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
                chalkTexture = fallbackBitmap
                cachedChalkShader = BitmapShader(fallbackBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                return fallbackBitmap
            } catch (e2: OutOfMemoryError) {
                // 极端情况：连 100x100 也无法创建，使用空纹理
                Log.e("HiddenDrawingView", "创建备用粉笔纹理仍然内存不足: ${e2.message}")
                throw e2
            }
        } catch (e: Exception) {
            Log.e("HiddenDrawingView", "创建粉笔纹理时出错: ${e.message}")
            // 创建默认纹理作为备用
            val fallbackBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
            chalkTexture = fallbackBitmap
            cachedChalkShader = BitmapShader(fallbackBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            return fallbackBitmap
        }
    }
    
    private fun drawChalkPath(canvas: Canvas, points: List<PathPoint>, paint: Paint) {
        if (points.size < 2) return
        
        // 绘制不规则边缘的粉笔路径
        drawIrregularChalkPath(canvas, points, paint)
        
        // 增加粉尘效果的生成频率：从0.15f增加到0.18f，让粉笔效果更明显
        if (points.size > 2 && random.nextFloat() < 0.18f) {
            addChalkDust(canvas, points, paint)
        }
    }
    
    private fun drawIrregularChalkPath(canvas: Canvas, points: List<PathPoint>, paint: Paint) {
        if (points.size < 2) return
        
        // DPT RP1优化：确保粉笔纹理存在
        if (chalkTexture == null || cachedChalkShader == null) {
            Log.d("HiddenDrawingView", "粉笔路径绘制时检测到纹理缺失，重新创建")
            createChalkTexture()
        }
        
        // 使用多个小段绘制，每段有不同的宽度和位置偏移，创造不规则边缘
        val baseStrokeWidth = paint.strokeWidth * 1.75f
        
        for (i in 0 until points.size - 1) {
            val currentPoint = points[i]
            val nextPoint = points[i + 1]
            
            // 计算线段长度
            val dx = nextPoint.x - currentPoint.x
            val dy = nextPoint.y - currentPoint.y
            val segmentLength = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            
            // 将每个线段分成多个小段，每段有不规则效果
            val segments = kotlin.math.max(1, (segmentLength / 8f).toInt()) // 每8像素一段
            
            for (j in 0 until segments) {
                val t1 = j.toFloat() / segments
                val t2 = (j + 1).toFloat() / segments
                
                // 计算小段的起点和终点
                val x1 = currentPoint.x + dx * t1
                val y1 = currentPoint.y + dy * t1
                val x2 = currentPoint.x + dx * t2
                val y2 = currentPoint.y + dy * t2
                
                // 为每个小段添加随机偏移和宽度变化
                val roughnessX = (random.nextFloat() - 0.5f) * chalkParams.edgeRoughness * baseStrokeWidth
                val roughnessY = (random.nextFloat() - 0.5f) * chalkParams.edgeRoughness * baseStrokeWidth
                val widthVariation = 1f + (random.nextFloat() - 0.5f) * chalkParams.strokeWidthVariation * 0.5f // 进一步减少宽度变化
                
                // 绘制带有不规则效果的小段
                cachedChalkShader?.let { shader ->
                    tempPaint.set(paint)
                    tempPaint.strokeCap = Paint.Cap.ROUND
                    tempPaint.strokeJoin = Paint.Join.ROUND
                    tempPaint.strokeWidth = baseStrokeWidth * widthVariation
                    tempPaint.isAntiAlias = true
                    tempPaint.isFilterBitmap = true
                    tempPaint.isDither = true
                    tempPaint.shader = shader
                    tempPaint.alpha = (paint.alpha * 0.7f).toInt()
                    
                    // 绘制主线段（带偏移）
                    canvas.drawLine(
                        x1 + roughnessX, y1 + roughnessY,
                        x2 + roughnessX, y2 + roughnessY,
                        tempPaint
                    )
                    
                    // 添加边缘不规则效果 - 绘制额外的不规则边缘线
                    if (random.nextFloat() < chalkParams.edgeVariation) {
                        val edgeOffsetX = (random.nextFloat() - 0.5f) * baseStrokeWidth * 0.8f
                        val edgeOffsetY = (random.nextFloat() - 0.5f) * baseStrokeWidth * 0.8f
                        
                        tempPaint.strokeWidth = baseStrokeWidth * 0.3f * widthVariation
                        tempPaint.alpha = (paint.alpha * 0.4f).toInt()
                        
                        canvas.drawLine(
                            x1 + edgeOffsetX, y1 + edgeOffsetY,
                            x2 + edgeOffsetX, y2 + edgeOffsetY,
                            tempPaint
                        )
                    }
                    
                    // 添加随机的边缘颗粒
                    if (random.nextFloat() < 0.36f) { // 增加边缘颗粒生成概率：从0.3f增加到0.36f，增加20%
                        val particleX = (x1 + x2) / 2 + (random.nextFloat() - 0.5f) * baseStrokeWidth
                        val particleY = (y1 + y2) / 2 + (random.nextFloat() - 0.5f) * baseStrokeWidth
                        val particleSize = baseStrokeWidth * 0.2f * (random.nextFloat() + 0.5f)
                        
                        tempPaint.strokeWidth = particleSize
                        tempPaint.alpha = (paint.alpha * 0.5f).toInt()
                        
                        canvas.drawPoint(particleX, particleY, tempPaint)
                    }
                }
            }
        }
    }
    
    private fun addChalkDust(canvas: Canvas, points: List<PathPoint>, paint: Paint) {
        // DPT RP1优化：确保粉笔纹理存在
        if (chalkTexture == null || cachedChalkShader == null) {
            Log.d("HiddenDrawingView", "粉笔粉尘绘制时检测到纹理缺失，重新创建")
            createChalkTexture()
        }
        
        for (point in points) {
            // DPT RP1优化：进一步降低粉尘生成概率，从0.6f改为0.75f，减少25%粉尘生成
            if (random.nextFloat() > 0.75f) {
                // 方形粉尘效果
                for (i in 0 until chalkParams.squaresPerTouch) {
                    val spread = point.width * chalkParams.squareSize * 1.5f // 增加扩散范围
                    val distance = random.nextFloat() * spread
                    val angle = random.nextFloat() * Math.PI * 2
                    
                    val x = point.x + (distance * kotlin.math.cos(angle)).toFloat()
                    val y = point.y + (distance * kotlin.math.sin(angle)).toFloat()
                    
                    val dustSize = point.width * chalkParams.squareSize * 1.2f // 增加粉尘大小
                    val dustPath = Path().apply {
                        moveTo(x - dustSize, y - dustSize)
                        lineTo(x + dustSize, y - dustSize)
                        lineTo(x + dustSize, y + dustSize)
                        lineTo(x - dustSize, y + dustSize)
                        close()
                    }
                    
                    chalkPaint.set(paint)
                    chalkPaint.style = Paint.Style.FILL
                    chalkPaint.alpha = 80 + random.nextInt(120) // 增加粉尘不透明度
                    chalkPaint.shader = null
                    
                    canvas.save()
                    canvas.rotate(random.nextFloat() * 360, x, y)
                    canvas.drawPath(dustPath, chalkPaint)
                    canvas.restore()
                }
                
                // 细小圆形粉尘
                for (i in 0 until chalkParams.tinyDustCount) {
                    val spread = point.width * chalkParams.tinyDustSpread * 1.5f // 增加扩散范围
                    val distance = random.nextFloat() * spread
                    val angle = random.nextFloat() * Math.PI * 2
                    
                    val x = point.x + (distance * kotlin.math.cos(angle)).toFloat()
                    val y = point.y + (distance * kotlin.math.sin(angle)).toFloat()
                    
                    chalkPaint.set(paint)
                    chalkPaint.style = Paint.Style.FILL
                    chalkPaint.alpha = chalkParams.tinyDustAlpha + 20 // 增加不透明度
                    chalkPaint.shader = null
                    
                    val tinyDustSize = point.width * chalkParams.tinyDustSize * 1.2f // 增加粉尘大小
                    canvas.drawCircle(x, y, tinyDustSize, chalkPaint)
                }
            }
        }
    }
    
    // DPT RP1优化：内存状态检查，防止死机
    private fun checkMemoryStatus() {
        try {
            val runtime = Runtime.getRuntime()
            val usedMemory = runtime.totalMemory() - runtime.freeMemory()
            val maxMemory = runtime.maxMemory()
            val memoryUsage = usedMemory.toFloat() / maxMemory.toFloat()
            
            if (memoryUsage > 0.8f) { // 内存使用超过80%
                Log.w("HiddenDrawingView", "内存使用率过高: ${(memoryUsage * 100).toInt()}%，强制清理")
                System.gc() // 强制垃圾回收
                
                // 如果内存仍然不足，清理一些缓存
                if (currentSession?.paths?.size ?: 0 > 10) {
                    Log.w("HiddenDrawingView", "路径数量过多，清理旧路径")
                    currentSession?.paths?.clear()
                }
            }
        } catch (e: Exception) {
            Log.e("HiddenDrawingView", "检查内存状态时出错: ${e.message}")
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
            Log.e("HiddenDrawingView", "获取内存使用率时出错: ${e.message}")
            0.5f // 返回默认值
        }
    }
    
    private fun addStartEndDust(canvas: Canvas, point: PathPoint, paint: Paint, isStart: Boolean) {
        // DPT RP1优化：确保粉笔纹理存在
        if (currentBrushType == BrushType.CHALK && (chalkTexture == null || cachedChalkShader == null)) {
            Log.d("HiddenDrawingView", "粉笔起止点粉尘绘制时检测到纹理缺失，重新创建")
            createChalkTexture()
        }
        
        val baseX = point.x
        val baseY = point.y
        val effectiveStrokeWidth = if (currentBrushType == BrushType.CHALK) drawPaint.strokeWidth * 1.5f else drawPaint.strokeWidth // 从2.5倍缩小为70%
        val spread = chalkParams.startEndDustSpread * effectiveStrokeWidth * 3.0f // 增加扩散基数
        
        // 创建一个新的画笔用于绘制粉尘
        val dustPaint = Paint(paint).apply {
            style = Paint.Style.FILL
        }
        
        // 绘制大颗粒粉尘
        for (i in 0 until chalkParams.startEndDustCount) {
            val angle = random.nextFloat() * Math.PI.toFloat() * 2.0f
            val distance = random.nextFloat() * spread
            val x = baseX + kotlin.math.cos(angle.toDouble()).toFloat() * distance
            val y = baseY + kotlin.math.sin(angle.toDouble()).toFloat() * distance
            
            // 随机大小
            val size = (random.nextFloat() * 0.5f + 0.5f) * chalkParams.startEndDustSize * effectiveStrokeWidth * 2.0f
            
            // 随机不透明度
            dustPaint.alpha = (random.nextFloat() * 100).toInt() + chalkParams.startEndDustAlpha
            
            // 随机形状（圆形或方形）
            if (random.nextFloat() < 0.3f) {
                // 圆形粉尘
                canvas.drawCircle(x, y, size, dustPaint)
            } else {
                // 方形粉尘
                val rotation = random.nextFloat() * 360
                canvas.save()
                canvas.rotate(rotation, x, y)
                canvas.drawRect(x - size, y - size, x + size, y + size, dustPaint)
                canvas.restore()
            }
        }
        
        // 添加一些更小的粉尘颗粒
        val smallDustCount = chalkParams.startEndDustCount * 2
        val smallSpread = spread * 1.5f
        dustPaint.alpha = chalkParams.startEndDustAlpha / 2
        
        for (i in 0 until smallDustCount) {
            val angle = random.nextFloat() * Math.PI.toFloat() * 2.0f
            val distance = random.nextFloat() * smallSpread
            val x = baseX + kotlin.math.cos(angle.toDouble()).toFloat() * distance
            val y = baseY + kotlin.math.sin(angle.toDouble()).toFloat() * distance
            
            val size = (random.nextFloat() * 0.3f + 0.2f) * chalkParams.startEndDustSize * effectiveStrokeWidth
            canvas.drawCircle(x, y, size, dustPaint)
        }
    }
    
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // 清理资源
        sessionTimeoutRunnable?.let { handler.removeCallbacks(it) }
        inactivityRunnable?.let { handler.removeCallbacks(it) }
        canvasBitmap?.recycle()
    }

    // 启动/刷新无动作看门狗：2秒内无动作且已有笔迹则强制完成会话
    private fun scheduleInactivityWatchdog() {
        inactivityRunnable?.let { handler.removeCallbacks(it) }
        inactivityRunnable = Runnable {
            try {
                val idleMs = System.currentTimeMillis() - lastActivityAt
                val hasStrokes = (currentSession?.paths?.isNotEmpty() == true) || currentPoints.isNotEmpty()
                if (idleMs >= inactivityTimeout && hasStrokes) {
                    Log.w("HiddenDrawingView", "检测到${idleMs}ms无动作且存在笔迹，触发容错完成会话")
                    isDrawing = false
                    // 避免与原会话超时重复触发
                    sessionTimeoutRunnable?.let { handler.removeCallbacks(it) }
                    sessionTimeoutRunnable = null
                    completeCurrentSession()
                } else {
                    // 若仍在活动或无笔迹，继续观察
                    if (isDrawing) handler.postDelayed(inactivityRunnable!!, inactivityTimeout)
                }
            } catch (e: Exception) {
                Log.e("HiddenDrawingView", "无动作容错处理异常: ${e.message}")
            }
        }
        handler.postDelayed(inactivityRunnable!!, inactivityTimeout)
    }
}