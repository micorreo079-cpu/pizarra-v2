package com.example.newdrawingapp

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import java.security.SecureRandom
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import java.io.ByteArrayOutputStream
import com.example.newdrawingapp.network.DrawingSocketManager

class DrawingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var drawPath: Path = Path()
    private var drawPaint: Paint = Paint()
    private var canvasPaint: Paint = Paint()
    private var drawCanvas: Canvas? = null
    private var canvasBitmap: Bitmap? = null
    private var lastX: Float = 0f
    private var lastY: Float = 0f
    private var isDrawing = false
    private var bitmapScale = 1f
    private var bitmapOffsetX = 0f
    private var bitmapOffsetY = 0f
    private var originalBitmap: Bitmap? = null  // 添加一个变量保存原始背景

    // 用于撤销/重做功能的路径历史记录
    private val pathHistory = mutableListOf<PathAction>()
    private val redoHistory = mutableListOf<PathAction>()
    
    // 存储当前绘制路径的所有点和对应的画笔宽度
    private data class PathPoint(
        val x: Float,
        val y: Float,
        val width: Float
    )
    
    private val currentPoints = mutableListOf<PathPoint>()
    
    // 修改 PathAction 类以支持可变宽度
    private data class PathAction(
        val points: List<PathPoint>,
        val paint: Paint
    )

    private val baseStrokeWidth = 12f     // 增加基础画笔宽度
    private val maxStrokeWidth = 40f      // 增大最大画笔宽度
    private val minStrokeWidth = 6f       // 增加最小画笔宽度
    private val touchSizeMultiplier = 0.2f // 增加触摸大小的影响
    private var strokeWidthMultiplier = 1.0f  // 画笔大小倍数

    private val baseBlurRadius = 3f      // 基础羽化半径
    private val maxBlurRadius = 8f       // 最大羽化半径
    private val minBlurRadius = 2f       // 最小羽化半径
    private val blurRandomness = 0.3f    // 羽化随机程度

    // 添加颗粒效果相关参数
    private val particleMaxRadius = 3f     // 最大颗粒半径
    private val particleMinRadius = 0.4f   // 最小颗粒半径
    private val particleDensity = 0.002f     // 颗粒密度（0-1）
    private val particleSpread = 2.2f      // 颗粒扩散范围（相对于画笔宽度）
    private val random = SecureRandom()  // 使用 SecureRandom

    // 添加边距常量
    private val drawingPadding = 20f  // 20dp的边距，您可以根据需要调整这个值

    init {
        setupDrawing()
    }

    private fun setupDrawing() {
        drawPaint.apply {
            color = Color.WHITE  // 默认白色画笔
            isAntiAlias = true
            strokeWidth = baseStrokeWidth
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        canvasPaint = Paint(Paint.DITHER_FLAG)
    }

    fun setBackgroundBitmap(bitmap: Bitmap) {
        originalBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)  // 保存原始背景
        canvasBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        drawCanvas = Canvas(canvasBitmap!!)
        updateBitmapTransform()
        invalidate()
    }

    fun setPenColor(isBlackPen: Boolean) {
        drawPaint.color = if (isBlackPen) Color.BLACK else Color.WHITE
    }

    fun getDrawingBitmap(): Bitmap? = canvasBitmap

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (canvasBitmap == null) {
            canvasBitmap = Bitmap.createBitmap(1650, 2200, Bitmap.Config.ARGB_8888)
            drawCanvas = Canvas(canvasBitmap!!)
        }
        updateBitmapTransform()
    }

    private fun updateBitmapTransform() {
        if (canvasBitmap != null && width > 0 && height > 0) {
            // 计算缩放比例，使图片完整显示
            val scaleX = width.toFloat() / canvasBitmap!!.width
            val scaleY = height.toFloat() / canvasBitmap!!.height
            bitmapScale = minOf(scaleX, scaleY)

            // 计算居中偏移
            bitmapOffsetX = (width - canvasBitmap!!.width * bitmapScale) / 2
            bitmapOffsetY = (height - canvasBitmap!!.height * bitmapScale) / 2
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvasBitmap?.let {
            canvas.save()
            canvas.translate(bitmapOffsetX, bitmapOffsetY)
            canvas.scale(bitmapScale, bitmapScale)
            canvas.drawBitmap(it, 0f, 0f, canvasPaint)
            
            // 绘制当前路径
            if (currentPoints.size > 1) {
                drawVariableWidthPath(canvas, currentPoints, drawPaint)
            }
            
            canvas.restore()
        }
    }

    private fun updateStrokeWidth(event: MotionEvent): Float {
        // 简化粗细控制，使用固定的基础粗细，减少变化
        return baseStrokeWidth * strokeWidthMultiplier
        
        // 原来的动态粗细代码已注释，如需恢复可取消注释
        /*
        val touchArea = event.getToolMajor() * event.getToolMinor()
        
        if (touchArea == 0f) {
            val pressure = event.pressure
            return if (pressure > 0) {
                // 减少压力影响，让粗细更稳定
                val pressureEffect = 0.2f // 从1.0f减少到0.2f，降低压力影响
                (baseStrokeWidth + (maxStrokeWidth - baseStrokeWidth) * pressure * pressureEffect) * strokeWidthMultiplier
            } else {
                baseStrokeWidth * strokeWidthMultiplier
            }
        }

        // 减少触摸面积影响
        val normalizedSize = (touchArea * touchSizeMultiplier * 0.1f).coerceIn(0f, 0.2f) // 大幅减少影响
        return (baseStrokeWidth + (maxStrokeWidth - baseStrokeWidth) * normalizedSize) * strokeWidthMultiplier
        */
    }

    private fun getBlurRadius(touchSize: Float): Float {
        val random = (Math.random() * 2 - 1) * blurRandomness + 1  // 生成0.7-1.3之间的随机数
        val normalizedSize = (touchSize * touchSizeMultiplier).coerceIn(0f, 1f)
        return (minBlurRadius + (maxBlurRadius - minBlurRadius) * normalizedSize * random).toFloat()
    }

    private fun createBlurredPaint(paint: Paint, blurRadius: Float): Paint {
        val blurredPaint = Paint(paint)
        blurredPaint.maskFilter = BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.NORMAL)
        return blurredPaint
    }

    private fun drawParticles(canvas: Canvas, x: Float, y: Float, width: Float, paint: Paint) {
        val particleCount = (width * particleDensity).toInt().coerceIn(1, 20)
        val spreadRadius = width * particleSpread / 2

        for (i in 0 until particleCount) {
            // 随机生成颗粒位置
            val angle = random.nextDouble() * 2 * Math.PI
            val distance = random.nextFloat() * spreadRadius
            val px = x + (cos(angle).toFloat() * distance)
            val py = y + (sin(angle).toFloat() * distance)

            // 随机生成颗粒大小
            val radius = particleMinRadius + random.nextFloat() * (particleMaxRadius - particleMinRadius)

            // 创建颗粒画笔
            val particlePaint = Paint(paint).apply {
                style = Paint.Style.FILL            // 设置为填充模式
                alpha = (50 + random.nextInt(156)).coerceIn(0, 255)
                maskFilter = BlurMaskFilter(radius * 0.5f, BlurMaskFilter.Blur.NORMAL)  // 添加边缘羽化
            }

            // 绘制实心颗粒
            canvas.drawCircle(px, py, radius, particlePaint)
        }
    }

    private fun drawVariableWidthPath(canvas: Canvas, points: List<PathPoint>, paint: Paint) {
        if (points.size < 2) return

        val path = Path()
        path.moveTo(points[0].x, points[0].y)

        // 使用更平滑的贝塞尔曲线
        if (points.size == 2) {
            // 只有两个点时使用直线
            path.lineTo(points[1].x, points[1].y)
            val width = (points[0].width + points[1].width) / 2
            val segmentPaint = Paint(paint).apply {
                strokeWidth = width
            }
            canvas.drawPath(path, createBlurredPaint(segmentPaint, getBlurRadius(width / maxStrokeWidth)))
        } else {
            // 三个或更多点时使用三次贝塞尔曲线
            var i = 0
            while (i < points.size - 2) {
                val p0 = if (i > 0) points[i - 1] else points[0]
                val p1 = points[i]
                val p2 = points[i + 1]
                val p3 = if (i + 2 < points.size) points[i + 2] else p2

                // 计算控制点
                val xc1 = (p1.x + p2.x) / 2
                val yc1 = (p1.y + p2.y) / 2
                val xc2 = (p1.x + p2.x) / 2
                val yc2 = (p1.y + p2.y) / 2

                // 使用三次贝塞尔曲线
                path.cubicTo(
                    p1.x, p1.y,
                    xc1, yc1,
                    xc2, yc2
                )

                // 计算当前段的宽度（使用四个点的平均宽度）
                val width = (p0.width + p1.width + p2.width + p3.width) / 4
                val segmentPaint = Paint(paint).apply {
                    strokeWidth = width
                }

                // 绘制主路径
                val blurRadius = getBlurRadius(width / maxStrokeWidth)
                canvas.drawPath(path, createBlurredPaint(segmentPaint, blurRadius))

                // 添加颗粒效果
                val stepLength = 2f
                var distance = 0f
                val dx = p2.x - p1.x
                val dy = p2.y - p1.y
                val length = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                
                while (distance < length) {
                    val t = distance / length
                    val particleX = p1.x + dx * t
                    val particleY = p1.y + dy * t
                    drawParticles(canvas, particleX, particleY, width, paint)
                    distance += stepLength
                }

                // 重置路径，从当前点开始
                path.reset()
                path.moveTo(xc2, yc2)
                i++
            }

            // 处理最后一段
            if (points.size >= 2) {
                val last = points.last()
                val secondLast = points[points.size - 2]
                path.lineTo(last.x, last.y)
                
                val width = (last.width + secondLast.width) / 2
                val segmentPaint = Paint(paint).apply {
                    strokeWidth = width
                }
                
                canvas.drawPath(path, createBlurredPaint(segmentPaint, getBlurRadius(width / maxStrokeWidth)))
                drawParticles(canvas, last.x, last.y, width, paint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = (event.x - bitmapOffsetX) / bitmapScale
        val y = (event.y - bitmapOffsetY) / bitmapScale

        if (canvasBitmap != null &&
            x >= 0 && x < canvasBitmap!!.width &&
            y >= 0 && y < canvasBitmap!!.height
        ) {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    currentPoints.clear()
                    val width = updateStrokeWidth(event)
                    currentPoints.add(PathPoint(x, y, width))
                    isDrawing = true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isDrawing) {
                        val width = updateStrokeWidth(event)
                        if (currentPoints.isNotEmpty()) {
                            val lastPoint = currentPoints.last()
                            val dx = x - lastPoint.x
                            val dy = y - lastPoint.y
                            val distance = sqrt((dx * dx + dy * dy).toDouble())
                            
                            // 增加距离阈值，减少点的采样频率
                            if (distance > 4.0) {  // 增加阈值
                                currentPoints.add(PathPoint(x, y, width))
                                // 只保留最近的点进行绘制
                                val pointsToRender = if (currentPoints.size > 4) {
                                    currentPoints.takeLast(4)
                                } else {
                                    currentPoints
                                }
                                drawVariableWidthPath(drawCanvas!!, pointsToRender, drawPaint)
                                invalidate()
                            }
                        } else {
                            currentPoints.add(PathPoint(x, y, width))
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (isDrawing) {
                        val width = updateStrokeWidth(event)
                        currentPoints.add(PathPoint(x, y, width))
                        pathHistory.add(PathAction(ArrayList(currentPoints), Paint(drawPaint)))
                        redoHistory.clear()
                        currentPoints.clear()
                        isDrawing = false
                    }
                }
                else -> return false
            }
            return true
        }
        return false
    }

    fun clearDrawing() {
        // 清除所有绘制的路径
        pathHistory.clear()
        redoHistory.clear()
        // 重新设置为原始背景
        originalBitmap?.let { bitmap ->
            canvasBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            drawCanvas = Canvas(canvasBitmap!!)
        }
        invalidate()
    }

    fun undo() {
        if (pathHistory.isNotEmpty()) {
            // 将最后一个动作移到重做历史
            redoHistory.add(pathHistory.removeAt(pathHistory.size - 1))
            // 重绘所有路径
            redrawPaths()
        }
    }

    fun redo() {
        if (redoHistory.isNotEmpty()) {
            // 将最后一个重做动作移回路径历史
            pathHistory.add(redoHistory.removeAt(redoHistory.size - 1))
            // 重绘所有路径
            redrawPaths()
        }
    }

    private fun redrawPaths() {
        originalBitmap?.let { bitmap ->
            canvasBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            drawCanvas = Canvas(canvasBitmap!!)
        }
        // 重绘所有路径
        for (action in pathHistory) {
            drawVariableWidthPath(drawCanvas!!, action.points, action.paint)
        }
        invalidate()
    }

    // 添加画笔大小调节方法
    fun setStrokeWidthMultiplier(multiplier: Float) {
        strokeWidthMultiplier = multiplier.coerceIn(0.5f, 2.0f)  // 限制倍数范围
    }

    // 添加接收绘图数据的方法
    fun receiveDrawingData(data: ByteArray) {
        // 处理接收到的绘图数据
        invalidate() // 重绘视图
    }
} 