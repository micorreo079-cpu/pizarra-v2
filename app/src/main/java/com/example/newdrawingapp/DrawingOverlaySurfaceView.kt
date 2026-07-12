package com.example.newdrawingapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Rect
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * 透明 SurfaceView 覆盖层，专用于电磁笔实时笔画的零 vsync 延迟渲染。
 *
 * 核心优势：
 *   SurfaceHolder.unlockCanvasAndPost() 直接向显示缓冲区提交帧，
 *   绕过 Choreographer/vsync 调度，消除 View.invalidate() 带来的 0-16ms 额外等待。
 *
 * 双缓冲处理（防闪烁）：
 *   SurfaceView 使用双缓冲（A/B 交替）。若只更新脏区，另一个缓冲区会
 *   缺少上一帧的更新而产生闪烁。解决方案：
 *   - 维护 overlayBitmap（所有线段的完整真实状态）
 *   - 每帧除画新线段外，还重画上一帧的脏区（prevFrameDirty），
 *     确保两个缓冲区都能追上最新内容，在 2 帧内完全收敛。
 */
class DrawingOverlaySurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    private var overlayBitmap: Bitmap? = null
    private var overlayCanvas: Canvas? = null
    private var surfaceReady = false

    // 上一帧的脏区 — 下一帧必须重绘此区域，确保另一个缓冲区也得到更新
    private val prevFrameDirty = Rect()

    val paint: Paint = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE   // 方形端点：像素对齐，无过渡色
        strokeJoin = Paint.Join.MITER
        isAntiAlias = false            // 纯黑/白像素 → 触发 EPDC A2 快速刷新
        isFilterBitmap = false
        isDither = false
        strokeWidth = 12f
        color = Color.BLACK
    }

    private val blitPaint = Paint().apply {
        isAntiAlias = false
        isFilterBitmap = false
        isDither = false
    }

    init {
        setZOrderOnTop(true)                        // Surface 在 Window 内所有 View 之上渲染
        holder.setFormat(PixelFormat.TRANSPARENT)   // 透明背景，让 imageView 透过来
        holder.addCallback(this)
        isClickable = false                         // 触摸事件穿透到下层 View
        isFocusable = false
        isFocusableInTouchMode = false
    }

    // ── SurfaceHolder.Callback ──────────────────────────────────────────────

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        if (w > 0 && h > 0) {
            if (overlayBitmap == null ||
                overlayBitmap!!.width != w || overlayBitmap!!.height != h
            ) {
                overlayBitmap?.recycle()
                overlayBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                overlayCanvas = Canvas(overlayBitmap!!)
            }
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
    }

    // ── 公开 API ────────────────────────────────────────────────────────────

    /**
     * 追加一条线段并立即提交到 Surface（不等待 vsync）。
     *
     * 双缓冲收敛策略：
     *   totalDirty = segDirty ∪ prevFrameDirty
     *   本帧将 overlayBitmap 的 totalDirty 区域贴到 Surface 缓冲区。
     *   即使另一个缓冲区上一帧缺少 segDirty，下一帧也会在其切换时重绘。
     */
    fun addSegment(x1: Float, y1: Float, x2: Float, y2: Float) {
        val bm = overlayBitmap ?: return
        if (!surfaceReady) return

        // 画到累积位图（真实状态）
        overlayCanvas?.drawLine(x1, y1, x2, y2, paint)

        val sw = paint.strokeWidth + 2f
        val segDirty = Rect(
            (minOf(x1, x2) - sw).toInt().coerceAtLeast(0),
            (minOf(y1, y2) - sw).toInt().coerceAtLeast(0),
            (maxOf(x1, x2) + sw).toInt() + 1,
            (maxOf(y1, y2) + sw).toInt() + 1
        )
        if (!segDirty.intersect(0, 0, bm.width, bm.height)) return

        // totalDirty = 本线段 + 上一帧脏区（追赶另一个缓冲区）
        val totalDirty = Rect(prevFrameDirty)
        totalDirty.union(segDirty)

        val c = holder.lockCanvas(totalDirty) ?: return
        try {
            c.clipRect(totalDirty)
            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)   // 清除透明区域
            c.drawBitmap(bm, totalDirty, totalDirty, blitPaint)     // 从真实位图贴图
        } finally {
            holder.unlockCanvasAndPost(c)   // 立即提交，不等待 vsync ★
        }

        prevFrameDirty.set(segDirty)  // 记录本帧脏区，供下帧追赶
    }

    /**
     * 清空覆盖层。
     * 连续清空两次（分别作用于两个缓冲区），确保双缓冲都完全透明。
     */
    fun clearOverlay() {
        overlayBitmap?.eraseColor(Color.TRANSPARENT)
        prevFrameDirty.setEmpty()
        if (!surfaceReady) return
        repeat(2) {
            val c = holder.lockCanvas() ?: return@repeat
            try {
                c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            } finally {
                holder.unlockCanvasAndPost(c)
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        overlayBitmap?.recycle()
        overlayBitmap = null
        overlayCanvas = null
    }
}
