package com.omphone.capture

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 图片手势状态。数值全部走 mutableStateOf，graphicsLayer 里只读不重组，
 * 所以捏合/拖动时每帧只更新图层的变换矩阵，不会有额外的重组开销。
 */
class ZoomState {
    var scale by mutableStateOf(1f)
        internal set
    var offset by mutableStateOf(Offset.Zero)
        internal set
    var rotation by mutableStateOf(0f)
        internal set

    /** 1x 时左右拖动产生的跟手位移（切图用，不参与缩放钳制）。 */
    var drag by mutableStateOf(0f)
        internal set

    val zoomed: Boolean get() = scale > 1.02f

    /**
     * 是否有任何需要复位的变换。
     * 只看 scale 会导致「只转了角度、没放大」时复位按钮不出现，用户没法把照片转回来。
     */
    val transformed: Boolean
        get() = scale > 1.02f || abs(normDeg(rotation)) > 1f || offset.getDistance() > 1f

    /** 归一化到 (-180, 180] 的倾角，仅用于判断/展示。 */
    val tilt: Float get() = normDeg(rotation)

    fun resetInstant() {
        scale = 1f
        offset = Offset.Zero
        rotation = 0f
        drag = 0f
    }

    /** 边界回弹：带一点过冲，明确告诉用户「到头了」。 */
    suspend fun springBack() {
        val d0 = drag
        if (abs(d0) < 0.5f) {
            drag = 0f
            return
        }
        animate(
            initialValue = d0,
            targetValue = 0f,
            animationSpec = spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessMediumLow)
        ) { v, _ -> drag = v }
        drag = 0f
    }

    suspend fun animateTo(targetScale: Float, targetOffset: Offset, targetRotation: Float = rotation) {
        val s0 = scale
        val o0 = offset
        val r0 = rotation
        val d0 = drag
        animate(0f, 1f, animationSpec = tween(240, easing = FastOutSlowInEasing)) { t, _ ->
            scale = s0 + (targetScale - s0) * t
            offset = lerp(o0, targetOffset, t)
            rotation = r0 + (targetRotation - r0) * t
            drag = d0 * (1f - t)
        }
    }
}

@Composable
fun rememberZoomState(key: Any?): ZoomState = remember(key) { ZoomState() }

/**
 * 橡皮筋位移：超出边界后越拉越沉（iOS UIScrollView 同款曲线），
 * 不会像线性阻尼那样「一直能拖动」，也不会硬邦邦地卡住。
 */
private fun rubberBand(x: Float, dim: Float): Float {
    if (x == 0f) return 0f
    val d = dim * 0.55f
    val sign = if (x > 0f) 1f else -1f
    val over = abs(x)
    return sign * (1f - 1f / (over / d + 1f)) * d
}

/** 角度归一化到 (-180, 180]。 */
internal fun normDeg(d: Float): Float {
    val m = ((d % 360f) + 360f) % 360f
    return if (m > 180f) m - 360f else m
}

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 8f

/** 图片按 ContentScale.Fit 摆进容器后的实际显示尺寸。 */
private fun fittedSize(container: IntSize, aspect: Float): Pair<Float, Float> {
    val cw = container.width.toFloat()
    val ch = container.height.toFloat()
    if (cw <= 0f || ch <= 0f || aspect <= 0f) return cw to ch
    return if (cw / ch > aspect) (ch * aspect) to ch else cw to (cw / aspect)
}

/** 把平移量钳制在图片边缘内，避免拖出白边/黑边。 */
fun clampOffset(offset: Offset, scale: Float, container: IntSize, aspect: Float): Offset {
    val (dw, dh) = fittedSize(container, aspect)
    val maxX = ((dw * scale - container.width) / 2f).coerceAtLeast(0f)
    val maxY = ((dh * scale - container.height) / 2f).coerceAtLeast(0f)
    return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
}

/**
 * 看图手势（对标系统相册/Google Photos 的手感）：
 * - 双指捏合以「两指中心」为锚点缩放，图片跟手，不会往角落里跑；
 * - 放大后单指拖动平移，边界有钳制，松手不会停在空白处；
 * - 未放大时单指左右拖动，图片跟着手指走，超过阈值再翻页，否则弹回；
 * - 双指旋转；
 * - 双击缩放由调用方通过 [doubleTapTarget] 处理。
 */
fun Modifier.photoGestures(
    state: ZoomState,
    aspect: Float,
    scope: CoroutineScope,
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit,
    canGoLeft: Boolean = true,
    canGoRight: Boolean = true,
    /** 翻一页手指要走的距离：默认整屏宽；图片不满屏时传「图宽 + 间隙」更跟手。 */
    pageStep: Float = 0f,
    /**
     * 手势节点的身份 key。必须随「当前是哪张照片」变化：
     * pointerInput 的 key 不变时节点会沿用旧闭包，翻页后仍在写上一张的 state、
     * 调上一张的回调——表现就是「翻了一页之后再怎么滑都不动」。
     */
    gestureKey: Any? = null,
    onEdgeLeft: () -> Unit = {},
    onEdgeRight: () -> Unit = {}
): Modifier = this.pointerInput(gestureKey, aspect, pageStep) {
    if (aspect <= 0f) return@pointerInput
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        // 一页的步距：图片不满屏时按「图宽 + 间隙」算，滑一点点就能看到下一张
        val step = if (pageStep > 0f) pageStep else size.width.toFloat()
        var pinch = false
        var dragging = false
        var move = Offset.Zero
        // 双指旋转要过死区才生效：两指轻碰时 calculateRotation 也会给出零点几度，
        // 直接应用会让照片被「顺手带歪」，看起来像方向跟着变了。
        var rotSum = 0f
        var rotActive = false
        val slop = viewConfiguration.touchSlop
        // 松手速度（px/s，指数平滑）：轻扫位移不大但甩得快，也应该翻页
        var vx = 0f
        var lastT = 0L
        var lastX = 0f
        var lastEventT = 0L

        do {
            val event = awaitPointerEvent()
            event.changes.firstOrNull()?.let { lastEventT = it.uptimeMillis }
            val pressed = event.changes.count { it.pressed }

            if (pressed >= 2) {
                // 双指：缩放 + 平移 + 旋转，锚点取两指中心
                pinch = true
                val zoom = event.calculateZoom()
                val pan = event.calculatePan()
                val rot = event.calculateRotation()
                val centroid = event.calculateCentroid(useCurrent = true)

                if (zoom != 1f || pan != Offset.Zero) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val newScale = (state.scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                    val k = newScale / state.scale
                    // 锚点不动：offset' = c + (offset - c) * k + pan
                    val anchored = centroid - center
                    val moved = anchored + (state.offset - anchored) * k + pan
                    state.scale = newScale
                    state.offset = clampOffset(moved, newScale, size, aspect)
                }
                if (rot != 0f) {
                    rotSum += rot
                    if (!rotActive && abs(rotSum) > 8f) rotActive = true
                    if (rotActive) state.rotation = (state.rotation + rot) % 360f
                }
                state.drag = 0f
                event.changes.forEach { it.consume() }
            } else if (pressed == 1) {
                val change = event.changes.first()
                val t = change.uptimeMillis
                if (lastT != 0L) {
                    val dt = (t - lastT).coerceAtLeast(1L)
                    val inst = ((change.position.x - lastX) / dt * 1000f)
                        .coerceIn(-8000f, 8000f)
                    vx = vx * 0.7f + inst * 0.3f
                }
                lastT = t
                lastX = change.position.x
                val d = change.positionChange()
                move += d
                // 超过系统触摸阈值才算拖动，否则会让双击/单击手势被误判取消
                if (!dragging && abs(move.x) > slop) {
                    dragging = true
                    // 把起手时被 slop 吞掉的位移补上：图片从按下那一刻就贴着手指走
                    if (!state.zoomed) state.drag = move.x
                }
                if (dragging) {
                    if (state.zoomed) {
                        // 放大后单指平移，边界钳制
                        state.offset = clampOffset(state.offset + d, state.scale, size, aspect)
                        change.consume()
                    } else if (!pinch) {
                        // 1x：左右拖动切图，图片跟手；到头了给很强的阻尼（几乎拖不动），
                        // 让「没有下一张」这件事在手指上就能感觉到
                        val next = state.drag + d.x
                        val allowed = if (next > 0f) canGoRight else canGoLeft
                        state.drag = when {
                            // 到头：橡皮筋，越拉越沉，最多再走小半页
                            !allowed -> rubberBand(next, step * 0.35f)
                            next > step -> step + rubberBand(next - step, step * 0.5f)
                            next < -step -> -step - rubberBand(-next - step, step * 0.5f)
                            else -> next
                        }
                        change.consume()
                    }
                }
            }
        } while (event.changes.any { it.pressed })

        // 停住再松手不算甩动：最后一段没有手指移动就把速度清掉
        if (lastT != 0L && lastEventT - lastT > 120L) vx = 0f

        // 松手：钳制/回弹/翻页
        if (pinch) {
            // 只歪了一点点就摆正，避免留下一个「既没放大也没转正」的怪角度
            if (abs(state.tilt) < 3f) state.rotation = 0f
            val target = if (state.scale <= MIN_SCALE + 0.001f) {
                MIN_SCALE to Offset.Zero
            } else {
                state.scale to clampOffset(state.offset, state.scale, size, aspect)
            }
            scope.launch { state.animateTo(target.first, target.second) }
        } else if (!state.zoomed && dragging) {
            // 参考系统相册的判定：把手势按当前速度再往前投影 0.18s，
            // 投影后过半程就翻页——轻扫和长拖都能翻，慢慢挪一点点则回弹。
            // 两条路都算翻页：拖够 90dp（不用拖满整屏），或者轻轻一甩（350dp/s）
            val slowTravel = 90.dp.toPx()
            val flingPx = 350.dp.toPx()
            val minTravel = 24.dp.toPx()
            val toRight = state.drag > slowTravel ||
                (state.drag > minTravel && vx > flingPx) ||
                (state.drag > 2f && !canGoRight)
            val toLeft = state.drag < -slowTravel ||
                (state.drag < -minTravel && vx < -flingPx) ||
                (state.drag < -2f && !canGoLeft)
            val forward = (toRight && canGoRight) || (toLeft && canGoLeft)
            if ((toRight || toLeft) && forward) {
                val exit = if (toRight) step else -step
                // 剩下的距离越短，动画越快，落位干脆
                val remain = (1f - (abs(state.drag) / step)).coerceIn(0f, 1f)
                val ms = (130 + 190 * remain).toInt()
                scope.launch {
                    animate(
                        state.drag, exit,
                        animationSpec = tween(ms, easing = CubicBezierEasing(0.22f, 0.61f, 0.36f, 1f))
                    ) { v, _ -> state.drag = v }
                    state.drag = 0f
                    if (toRight) onSwipeRight() else onSwipeLeft()
                }
            } else {
                // 到头了（或没到阈值）：明显地弹回去，并告知这是边界
                scope.launch { state.springBack() }
                if (toRight) onEdgeRight()
                if (toLeft) onEdgeLeft()
            }
        }
    }
}

/** 双击缩放的目标缩放值。 */
const val DOUBLE_TAP_SCALE = 2.6f

/** 双击时以点击点为锚点算出目标位移。 */
fun doubleTapOffset(
    tap: Offset,
    state: ZoomState,
    container: IntSize,
    aspect: Float
): Offset {
    val center = Offset(container.width / 2f, container.height / 2f)
    val c = tap - center
    val k = DOUBLE_TAP_SCALE / state.scale
    return clampOffset(c - (c - state.offset) * k, DOUBLE_TAP_SCALE, container, aspect)
}
