package com.fastreader.app.viewer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.roundToInt

/**
 * 핀치 확대/축소, 더블탭 확대, 확대 상태의 좌우 이동을 지원하는 RecyclerView (PDF 페이지용).
 * 레이아웃은 그대로 두고 그리기 단계에서만 배율을 적용하므로 스크롤 성능이 유지된다.
 */
class ZoomRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : RecyclerView(context, attrs, defStyle) {

    var scale = 1f
        private set
    private var transX = 0f

    /** 확대/축소 제스처가 끝났을 때 호출 (고해상도 재렌더링용) */
    var onZoomChanged: ((Float) -> Unit)? = null

    private var multiTouchGesture = false
    private var lastX = 0f
    private val touchMatrix = Matrix()

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoomTo(scale * detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                zoomFinished()
            }
        },
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                zoomTo(if (scale > 1.05f) 1f else 2f, e.x, e.y)
                zoomFinished()
                return true
            }
        },
    )

    fun resetZoom() {
        scale = 1f
        transX = 0f
        updateBottomPadding()
        invalidate()
    }

    fun zoomTo(newScale: Float, focusX: Float, focusY: Float) {
        val s = newScale.coerceIn(MIN_SCALE, MAX_SCALE)
        if (s == scale) return
        val old = scale
        // 손가락 아래의 내용이 제자리에 있도록 이동량 보정
        val contentX = (focusX - transX) / old
        transX = focusX - s * contentX
        val dy = focusY / old - focusY / s
        scale = s
        clampTranslation()
        if (dy != 0f) scrollBy(0, dy.roundToInt())
        invalidate()
    }

    private fun zoomFinished() {
        updateBottomPadding()
        onZoomChanged?.invoke(scale)
    }

    /** 확대 시 화면에는 height/scale 만큼만 보이므로 마지막 페이지 끝까지 스크롤되도록 여백을 준다. */
    private fun updateBottomPadding() {
        val pb = if (scale > 1f) (height - height / scale).roundToInt() else 0
        if (paddingBottom != pb) setPadding(paddingLeft, paddingTop, paddingRight, pb)
    }

    private fun clampTranslation() {
        val minX = width - width * scale
        transX = transX.coerceIn(minOf(minX, 0f), 0f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clampTranslation()
        post { updateBottomPadding() }
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (scale == 1f && transX == 0f) {
            super.dispatchDraw(canvas)
            return
        }
        val save = canvas.save()
        canvas.translate(transX, 0f)
        canvas.scale(scale, scale)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(save)
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        // 두 손가락이 닿으면 항상 가로채서 핀치로 처리
        if (e.pointerCount > 1) return true
        return super.onInterceptTouchEvent(e)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        gestureDetector.onTouchEvent(e)

        if (e.actionMasked == MotionEvent.ACTION_DOWN) multiTouchGesture = false

        if (e.pointerCount > 1 || scaleDetector.isInProgress) {
            if (!multiTouchGesture) {
                multiTouchGesture = true
                // 진행 중이던 스크롤 드래그 취소
                val cancel = MotionEvent.obtain(e)
                cancel.action = MotionEvent.ACTION_CANCEL
                super.onTouchEvent(cancel)
                cancel.recycle()
            }
            return true
        }
        if (multiTouchGesture) {
            // 핀치 직후 남은 한 손가락의 움직임은 무시 (화면 튐 방지)
            return true
        }

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> lastX = e.x
            MotionEvent.ACTION_MOVE -> {
                if (scale > 1f) {
                    transX += e.x - lastX
                    clampTranslation()
                    invalidate()
                }
                lastX = e.x
            }
        }

        if (scale == 1f) return super.onTouchEvent(e)
        // 확대 상태에서는 손가락 이동량과 화면 이동량이 같도록 좌표를 축소해서 전달
        val ev = MotionEvent.obtain(e)
        touchMatrix.setScale(1f / scale, 1f / scale)
        ev.transform(touchMatrix)
        val handled = super.onTouchEvent(ev)
        ev.recycle()
        return handled
    }

    companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 4f
    }
}
