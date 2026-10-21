package app.hermesconduit.android.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip

/**
 * The state icons the car's voice screen shows (iOS CarPlayVoiceArtwork): the
 * Conduit orb in the live call orb's colours with the state's mark on it. A
 * mic at Ready, a ring around the mic while listening, three dots while
 * thinking, bars while responding, and a warning at Error. Android Auto
 * templates take still images, so each state is one frame (iOS animates the
 * in-conversation ones). Drawn once per state and cached. Pure state
 * feedback: no user or assistant content is ever drawn.
 */
object CarVoiceArtwork {
    /** Pixels per side; the host scales it down to the template's slot. */
    const val SIDE = 300

    private const val DISC_RADIUS = 0.48f
    private const val GLYPH_BOX = 0.6f
    private const val DOT_SPACING = 0.31f
    private const val DOT_RADIUS = 0.115f
    private const val BAR_WIDTH = 0.125f
    private const val BAR_SPACING = 0.18f
    private val barHeights = floatArrayOf(0.30f, 0.55f, 0.78f, 0.55f, 0.30f)

    private val cache = HashMap<CarVoiceState, Bitmap>()

    /** Drops the drawn icons, when the car disconnects. */
    fun clearCache() = synchronized(cache) { cache.clear() }

    fun image(state: CarVoiceState): Bitmap = synchronized(cache) {
        cache.getOrPut(state) { draw(state) }
    }

    private fun draw(state: CarVoiceState): Bitmap {
        val bitmap = createBitmap(SIDE, SIDE)
        val canvas = Canvas(bitmap)
        val palette = CarOrbPalette.of(state)
        drawOrb(canvas, palette)
        val mark = palette.mark
        when (state) {
            CarVoiceState.READY -> drawMic(canvas, mark)
            CarVoiceState.LISTENING -> {
                drawRing(canvas, mark)
                drawMic(canvas, mark)
            }
            CarVoiceState.PROCESSING -> drawDots(canvas, mark)
            CarVoiceState.RESPONDING -> drawBars(canvas, mark)
            CarVoiceState.ERROR -> drawWarning(canvas, mark)
        }
        return bitmap
    }

    /** A glass orb: a soft glow, a body lit from the top left with the second colour swirling in, a highlight and a fine rim. */
    private fun drawOrb(canvas: Canvas, palette: CarOrbPalette) {
        val side = SIDE.toFloat()
        val center = side / 2
        val radius = side * DISC_RADIUS
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.shader = RadialGradient(
            center, center, side / 2,
            intArrayOf(Color.TRANSPARENT, ColorUtils.setAlphaComponent(palette.glow, 0x70), ColorUtils.setAlphaComponent(palette.glow, 0)),
            floatArrayOf(0f, 0.9f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(center, center, side / 2, paint)

        val body = Path().apply { addCircle(center, center, radius, Path.Direction.CW) }
        canvas.withClip(body) {
            // Body: lit from the top left, falling off to the deep colour.
            paint.shader = RadialGradient(
                side * 0.38f, side * 0.34f, radius * 1.3f,
                intArrayOf(palette.light, palette.light, palette.deep), floatArrayOf(0f, 0.15f, 1f), Shader.TileMode.CLAMP,
            )
            drawCircle(center, center, radius, paint)
            // The second colour, swirling in from the top left.
            paint.shader = RadialGradient(
                side * 0.24f, side * 0.3f, radius * 0.75f,
                ColorUtils.setAlphaComponent(palette.swirl, 0x8C), ColorUtils.setAlphaComponent(palette.swirl, 0), Shader.TileMode.CLAMP,
            )
            drawCircle(center, center, radius, paint)
            // Specular highlight.
            paint.shader = RadialGradient(
                side * 0.36f, side * 0.26f, radius * 0.42f,
                ColorUtils.setAlphaComponent(Color.WHITE, 0x73), ColorUtils.setAlphaComponent(Color.WHITE, 0), Shader.TileMode.CLAMP,
            )
            drawCircle(center, center, radius, paint)
        }

        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = side * 0.012f
        paint.color = ColorUtils.setAlphaComponent(Color.WHITE, 0x38)
        canvas.drawCircle(center, center, radius - side * 0.006f, paint)
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }

    /** A microphone filling the glyph box: capsule, cradle and stand. */
    private fun drawMic(canvas: Canvas, color: Int) {
        val side = SIDE.toFloat()
        val box = side * GLYPH_BOX
        val left = (side - box) / 2
        val top = (side - box) / 2
        val paint = fill(color)
        val capsuleWidth = box * 0.38f
        val capsule = RectF(side / 2 - capsuleWidth / 2, top, side / 2 + capsuleWidth / 2, top + box * 0.62f)
        canvas.drawRoundRect(capsule, capsuleWidth / 2, capsuleWidth / 2, paint)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = box * 0.075f
            strokeCap = Paint.Cap.ROUND
        }
        val cradle = RectF(left + box * 0.16f, top + box * 0.18f, left + box * 0.84f, top + box * 0.8f)
        canvas.drawArc(cradle, 0f, 180f, false, stroke)
        canvas.drawLine(side / 2, top + box * 0.8f, side / 2, top + box * 0.96f, stroke)
        canvas.drawLine(side / 2 - box * 0.18f, top + box * 0.96f, side / 2 + box * 0.18f, top + box * 0.96f, stroke)
    }

    /** The listening ring, between the mic and the orb's edge. */
    private fun drawRing(canvas: Canvas, color: Int) {
        val side = SIDE.toFloat()
        val lineWidth = side * 0.035f
        val radius = side * (GLYPH_BOX / 2 + DISC_RADIUS) / 2 + lineWidth / 2
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = ColorUtils.setAlphaComponent(color, 0x99)
            style = Paint.Style.STROKE
            strokeWidth = lineWidth
        }
        canvas.drawCircle(side / 2, side / 2, radius, paint)
    }

    /** Three thinking dots, the middle one brightest. */
    private fun drawDots(canvas: Canvas, color: Int) {
        val side = SIDE.toFloat()
        val alphas = intArrayOf(0x8C, 0xFF, 0x8C)
        for (index in 0 until 3) {
            val paint = fill(ColorUtils.setAlphaComponent(color, alphas[index]))
            canvas.drawCircle(side / 2 + (index - 1) * side * DOT_SPACING, side / 2, side * DOT_RADIUS, paint)
        }
    }

    /** Five rounded bars, highest in the middle like a voice level. */
    private fun drawBars(canvas: Canvas, color: Int) {
        val side = SIDE.toFloat()
        val width = side * BAR_WIDTH
        val paint = fill(color)
        for (index in barHeights.indices) {
            val height = side * barHeights[index]
            val x = side / 2 + (index - barHeights.size / 2) * side * BAR_SPACING - width / 2
            canvas.drawRoundRect(RectF(x, (side - height) / 2, x + width, (side + height) / 2), width / 2, width / 2, paint)
        }
    }

    /** A warning triangle with its exclamation mark knocked out. */
    private fun drawWarning(canvas: Canvas, color: Int) {
        val side = SIDE.toFloat()
        val box = side * GLYPH_BOX
        val top = (side - box) / 2 + box * 0.06f
        val bottom = top + box * 0.86f
        val triangle = Path().apply {
            moveTo(side / 2, top)
            lineTo(side / 2 + box / 2, bottom)
            lineTo(side / 2 - box / 2, bottom)
            close()
        }
        val paint = fill(color).apply {
            strokeJoin = Paint.Join.ROUND
            style = Paint.Style.FILL_AND_STROKE
            strokeWidth = box * 0.08f
        }
        canvas.drawPath(triangle, paint)
        val knock = fill(CarOrbPalette.of(CarVoiceState.ERROR).deep)
        val barWidth = box * 0.1f
        canvas.drawRoundRect(RectF(side / 2 - barWidth / 2, top + box * 0.3f, side / 2 + barWidth / 2, top + box * 0.6f), barWidth / 2, barWidth / 2, knock)
        canvas.drawCircle(side / 2, top + box * 0.72f, barWidth * 0.6f, knock)
    }
}
