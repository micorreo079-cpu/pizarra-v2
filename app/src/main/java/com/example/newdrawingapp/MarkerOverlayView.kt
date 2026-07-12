package com.example.newdrawingapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * 电磁笔实时笔画覆盖层。
 *
 * 设计原则：直接渲染，无路径积累。
 * - 每次 addSegment 只把新线段画到内部 Bitmap，onDraw 只做 Bitmap blit（极快）
 * - 禁用抗锯齿 + 不使用硬件层，保证输出纯黑/纯白像素，触发 EPDC A2 快速刷新
 * - 局部 invalidate，EPDC 更新面积最小化
 */
class MarkerOverlayView(context: Context) : View(context) {

    private var overlayBitmap: Bitmap? = null
    private var overlayCanvas: Canvas? = null

    private var lastX = 0f
    private var lastY = 0f

    val paint: Paint = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE  // 方形端点，像素对齐，比 ROUND 更"硬"
        strokeJoin = Paint.Join.MITER
        isAntiAlias = false           // 纯黑/纯白，触发 A2 刷新
        isFilterBitmap = false
        isDither = false
        strokeWidth = 12f
        color = Color.BLACK
    }

    private val bitmapPaint = Paint().apply {
        isAntiAlias = false
        isFilterBitmap = false
        isDither = false
    }

    init {
        setLayerType(LAYER_TYPE_NONE, null)   // 不使用 GPU 纹理合成
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
        isFocusableInTouchMode = false
        background = null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            overlayBitmap?.recycle()
            // ALPHA_8 只有 1 字节/像素，内存最小，onDraw 时用 ColorFilter 着色
            overlayBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            overlayCanvas = Canvas(overlayBitmap!!)
        }
    }

    /** 起笔：记录起点，不绘制任何内容 */
    fun beginStroke(x: Float, y: Float) {
        lastX = x
        lastY = y
    }

    /**
     * 追加线段：从上一点到 (x, y) 直接画线。
     * 只 invalidate 这一小段的包围盒，EPDC 更新区域最小。
     */
    fun addSegment(x: Float, y: Float) {
        overlayCanvas?.drawLine(lastX, lastY, x, y, paint)
        val sw = paint.strokeWidth + 2f
        val left   = (minOf(lastX, x) - sw).toInt().coerceAtLeast(0)
        val top    = (minOf(lastY, y) - sw).toInt().coerceAtLeast(0)
        val right  = (maxOf(lastX, x) + sw).toInt() + 1
        val bottom = (maxOf(lastY, y) + sw).toInt() + 1
        lastX = x
        lastY = y
        invalidate(left, top, right, bottom)
    }

    /** 抬笔：清空覆盖层 */
    fun clearStroke() {
        overlayBitmap?.eraseColor(Color.TRANSPARENT)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        // 只做 Bitmap blit，O(1)，与路径长度无关
        overlayBitmap?.let { canvas.drawBitmap(it, 0f, 0f, bitmapPaint) }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        overlayBitmap?.recycle()
        overlayBitmap = null
        overlayCanvas = null
    }
}
