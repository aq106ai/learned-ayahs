package com.quran.learnedplayer.video

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Paints the backgrounds of [VideoThemes], one [SceneSpec] at a time, at [width] by [height].
 *
 * Each scene has a still layer, painted once and kept as a bitmap, and a light moving layer
 * painted over it every frame — stars twinkling, clouds and mist drifting, rain, waves, embers,
 * rays of light. Keeping the moving part small is what lets a minute of video render in about a
 * minute on a phone. Shapes come from a random generator seeded by the scene, so a background
 * looks the same in the preview and in every frame of the video.
 *
 * Nothing drawn here is a person or an animal: sky, land, water, plants, buildings, light and
 * pattern only.
 */
class BackgroundScenes(private val width: Int, private val height: Int) {
    private val w = width.toFloat()
    private val h = height.toFloat()
    /** One unit scales shapes with the shorter side, so every aspect ratio looks alike. */
    private val u = min(width, height) / 1080f

    private val stills = LinkedHashMap<SceneSpec, Bitmap>()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Draws [spec] as it looks [ms] into the video, at [alpha] (0-255) for crossfades. */
    fun draw(canvas: Canvas, spec: SceneSpec, ms: Long, alpha: Int = 255) {
        val t = ms / 1000f
        if (alpha < 255) canvas.saveLayerAlpha(0f, 0f, w, h, alpha)
        bitmapPaint.alpha = 255
        if (spec.kind == SceneKind.PHOTO) {
            // A slow zoom and drift, so a still photo doesn't look frozen: up to 7% larger,
            // easing in and out over about half a minute.
            val ease = (1 - cos(t * 2 * PI.toFloat() / 32f)) / 2f
            val scale = 1.01f + 0.06f * ease
            canvas.save()
            canvas.scale(scale, scale, w / 2 + sin(t / 11f) * w * 0.04f, h / 2)
            canvas.drawBitmap(still(spec), 0f, 0f, bitmapPaint)
            canvas.restore()
        } else {
            canvas.drawBitmap(still(spec), 0f, 0f, bitmapPaint)
            animate(canvas, spec, t)
        }
        if (alpha < 255) canvas.restore()
    }

    /** Frees the cached stills; call when the video is done. */
    fun release() {
        stills.values.forEach { it.recycle() }
        stills.clear()
    }

    private fun still(spec: SceneSpec): Bitmap {
        stills[spec]?.let { return it }
        // A crossfade needs two; a third is never in use at once.
        while (stills.size >= 2) {
            val oldest = stills.keys.first()
            stills.remove(oldest)?.recycle()
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        paintStill(Canvas(bitmap), spec)
        stills[spec] = bitmap
        return bitmap
    }

    private fun seed(spec: SceneSpec): Int = spec.kind.ordinal * 7919 + spec.colors.contentHashCode()

    private val SceneSpec.top get() = colors[0]
    private val SceneSpec.middle get() = colors[1]
    private val SceneSpec.land get() = colors[2]
    private val SceneSpec.light get() = colors[3]

    // ---------------------------------------------------------------------------------------
    // The still layer

    private fun paintStill(c: Canvas, s: SceneSpec) {
        // Opaque from the start: every frame reuses one bitmap, and anything see-through here
        // would let the frame before show through.
        c.drawColor(Color.rgb(Color.red(s.top), Color.green(s.top), Color.blue(s.top)))
        val r = Random(seed(s))
        when (s.kind) {
            SceneKind.SKY -> {
                sky(c, s.top, s.middle, h * 0.8f)
                band(c, h * 0.8f, h, s.middle, s.land)
                if (s.has(Feature.SUN)) glow(c, w * 0.5f, h * 0.78f, 520f * u, s.light, 0.9f)
                hills(c, r, h * 0.82f, 60f * u, s.land, 3)
            }
            SceneKind.RAYS -> {
                sky(c, s.top, s.land, h)
                glow(c, w * 0.5f, -h * 0.05f, max(w, h) * 0.7f, s.middle, 0.55f)
            }
            SceneKind.WATER -> {
                sky(c, s.top, s.middle, h * 0.42f)
                band(c, h * 0.42f, h, s.middle, s.land)
                glow(c, w * 0.5f, h * 0.42f, 420f * u, s.light, 0.45f)
                if (s.has(Feature.SUN)) glow(c, w * 0.5f, h * 0.4f, 200f * u, s.light, 0.8f)
            }
            SceneKind.RAIN -> {
                sky(c, s.top, s.land, h)
                clouds(c, r, 0f, h * 0.35f, s.middle, 0.35f, 9)
                hills(c, r, h * 0.86f, 50f * u, darker(s.land, 0.8f), 2)
            }
            SceneKind.GARDEN -> garden(c, r, s)
            SceneKind.EMBERS -> {
                sky(c, s.top, s.land, h)
                glow(c, w * 0.5f, h * 1.05f, max(w, h) * 0.75f, s.middle, 0.9f)
                glow(c, w * 0.5f, h * 1.1f, max(w, h) * 0.45f, s.light, 0.25f)
            }
            SceneKind.STORM -> {
                sky(c, s.top, s.middle, h)
                clouds(c, r, -h * 0.05f, h * 0.45f, darker(s.middle, 0.7f), 0.55f, 12)
                hills(c, r, h * 0.88f, 40f * u, s.land, 2)
            }
            SceneKind.RUINS -> {
                sky(c, s.top, s.middle, h * 0.7f)
                band(c, h * 0.7f, h, s.middle, s.land)
                if (s.has(Feature.STARS)) stars(c, r, 220, h * 0.6f, s.light) else glow(c, w * 0.7f, h * 0.66f, 360f * u, s.light, 0.7f)
                dunes(c, r, h * 0.72f, s.land, s.middle, 2)
                columns(c, r, h * 0.8f, darker(s.land, 0.65f))
                dunes(c, r, h * 0.86f, darker(s.land, 0.85f), s.land, 1)
            }
            SceneKind.NEBULA -> {
                sky(c, s.top, s.land, h)
                repeat(6) {
                    glow(c, r.nextFloat() * w, r.nextFloat() * h, (300 + r.nextFloat() * 500) * u, s.middle, 0.35f + r.nextFloat() * 0.3f)
                }
                repeat(3) { glow(c, r.nextFloat() * w, r.nextFloat() * h, (150 + r.nextFloat() * 250) * u, s.light, 0.18f) }
                stars(c, r, 380, h, s.light)
            }
            SceneKind.MIST -> {
                sky(c, s.top, s.middle, h * 0.75f)
                band(c, h * 0.75f, h, s.middle, s.land)
                hills(c, r, h * 0.78f, 70f * u, blend(s.middle, s.land, 0.5f), 2)
                hills(c, r, h * 0.86f, 50f * u, s.land, 2)
            }
            SceneKind.STARS -> {
                sky(c, s.top, s.middle, h)
                stars(c, r, 420, h * 0.9f, s.light)
                moon(c, s)
                hills(c, r, h * 0.9f, 45f * u, s.land, 2)
            }
            SceneKind.SEA -> {
                val horizon = h * 0.55f
                sky(c, s.top, s.middle, horizon)
                if (s.has(Feature.CLOUDS)) clouds(c, r, h * 0.05f, horizon * 0.8f, s.light, 0.18f, 6)
                if (s.has(Feature.SUN)) glow(c, w * 0.5f, horizon, 300f * u, s.light, 0.95f)
                moon(c, s, horizon * 0.45f)
                band(c, horizon, h, blend(s.middle, s.land, 0.5f), s.land)
                if (s.has(Feature.SUN) || s.has(Feature.FULL_MOON)) {
                    fill.alpha = 255
                    fill.shader = LinearGradient(0f, horizon, 0f, h, withAlpha(s.light, 0.55f), withAlpha(s.light, 0f), Shader.TileMode.CLAMP)
                    val path = Path().apply {
                        moveTo(w * 0.47f, horizon); lineTo(w * 0.53f, horizon); lineTo(w * 0.68f, h); lineTo(w * 0.32f, h); close()
                    }
                    c.drawPath(path, fill)
                    fill.shader = null
                }
            }
            SceneKind.DUNES -> {
                sky(c, s.top, s.middle, h * 0.66f)
                band(c, h * 0.66f, h, s.middle, s.land)
                if (s.has(Feature.STARS)) stars(c, r, 300, h * 0.6f, s.light)
                moon(c, s)
                if (s.has(Feature.SUN)) glow(c, w * 0.72f, h * 0.58f, 380f * u, s.light, 0.9f)
                dunes(c, r, h * 0.62f, blend(s.middle, s.land, 0.55f), s.middle, 1)
                dunes(c, r, h * 0.72f, s.land, blend(s.land, s.light, 0.25f), 1)
                dunes(c, r, h * 0.84f, darker(s.land, 0.82f), s.land, 1)
            }
            SceneKind.MOUNTAINS -> {
                sky(c, s.top, s.middle, h * 0.7f)
                band(c, h * 0.7f, h, s.middle, s.land)
                if (s.has(Feature.STARS)) stars(c, r, 260, h * 0.55f, s.light)
                if (s.has(Feature.SUN)) glow(c, w * 0.3f, h * 0.5f, 360f * u, s.light, 0.85f)
                for (layer in 0 until 4) {
                    val shade = blend(s.middle, s.land, 0.35f + layer * 0.22f)
                    ridge(c, r, h * (0.5f + layer * 0.09f), (180f - layer * 25f) * u, shade)
                }
            }
            SceneKind.FIELDS -> {
                sky(c, s.top, s.middle, h * 0.62f)
                if (s.has(Feature.CLOUDS)) clouds(c, r, h * 0.05f, h * 0.4f, s.light, 0.25f, 6)
                if (s.has(Feature.SUN)) glow(c, w * 0.65f, h * 0.55f, 380f * u, s.light, 0.85f)
                band(c, h * 0.6f, h, blend(s.middle, s.land, 0.5f), darker(s.land, 0.8f))
                hills(c, r, h * 0.62f, 50f * u, blend(s.middle, s.land, 0.6f), 2)
            }
            SceneKind.ARCHES -> arches(c, s)
            SceneKind.LANTERN -> {
                sky(c, s.top, s.land, h)
                niche(c, s.middle)
            }
            SceneKind.GEOMETRIC -> {
                sky(c, s.top, s.land, h)
                glow(c, w * 0.5f, h * 0.45f, max(w, h) * 0.6f, s.middle, 0.8f)
                starPattern(c, s.light)
                vignette(c)
            }
            SceneKind.MANUSCRIPT -> manuscript(c, r, s)
            SceneKind.PHOTO -> photo(c, s)
        }
    }

    /** The photo cropped to fill the frame, darkened a little so the text on it stays readable. */
    private fun photo(c: Canvas, s: SceneSpec) {
        val path = s.photo ?: return
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return
        // Decode no larger than the frame needs.
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) sample *= 2
        val bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return
        val cover = max(w / bitmap.width, h / bitmap.height)
        val dw = bitmap.width * cover
        val dh = bitmap.height * cover
        c.drawBitmap(bitmap, null, RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), bitmapPaint)
        bitmap.recycle()
        c.drawColor(Color.argb(60, 0, 0, 0))
        vignette(c)
    }

    private fun sky(c: Canvas, top: Int, bottom: Int, to: Float) = band(c, 0f, to, top, bottom)

    private fun band(c: Canvas, from: Float, to: Float, top: Int, bottom: Int) {
        fill.alpha = 255
        fill.shader = LinearGradient(0f, from, 0f, to, top, bottom, Shader.TileMode.CLAMP)
        c.drawRect(0f, from, w, to, fill)
        fill.shader = null
    }

    private fun glow(c: Canvas, x: Float, y: Float, radius: Float, color: Int, strength: Float) {
        fill.alpha = 255
        fill.shader = RadialGradient(x, y, radius, withAlpha(color, strength), withAlpha(color, 0f), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, radius, fill)
        fill.shader = null
    }

    private fun vignette(c: Canvas) {
        val radius = max(w, h) * 0.75f
        fill.alpha = 255
        fill.shader = RadialGradient(w / 2, h / 2, radius, intArrayOf(0, 0, Color.argb(150, 0, 0, 0)), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, fill)
        fill.shader = null
    }

    private fun stars(c: Canvas, r: Random, count: Int, below: Float, color: Int) {
        repeat(count) {
            fill.color = withAlpha(color, 0.25f + r.nextFloat() * 0.6f)
            c.drawCircle(r.nextFloat() * w, r.nextFloat() * below, (0.8f + r.nextFloat() * 1.8f) * u, fill)
        }
    }

    private fun moon(c: Canvas, s: SceneSpec, y: Float = h * 0.2f) {
        val x = w * 0.72f
        val radius = 70f * u
        if (s.has(Feature.FULL_MOON)) {
            glow(c, x, y, radius * 4f, s.light, 0.35f)
            fill.color = s.light
            c.drawCircle(x, y, radius, fill)
        } else if (s.has(Feature.CRESCENT)) {
            glow(c, x, y, radius * 3f, s.light, 0.25f)
            val path = Path().apply {
                addCircle(x, y, radius, Path.Direction.CW)
                val cut = Path().apply { addCircle(x + radius * 0.45f, y - radius * 0.2f, radius * 0.88f, Path.Direction.CW) }
                op(cut, Path.Op.DIFFERENCE)
            }
            fill.color = s.light
            c.drawPath(path, fill)
        }
    }

    /** Soft rolling hills along [baseline], filled to the bottom. */
    private fun hills(c: Canvas, r: Random, baseline: Float, amplitude: Float, color: Int, waves: Int) {
        val phase = r.nextFloat() * 2 * PI.toFloat()
        val path = Path().apply {
            moveTo(0f, h)
            var x = 0f
            while (x <= w) {
                lineTo(x, baseline + amplitude * sin(x / w * waves * PI.toFloat() + phase))
                x += 8f * u
            }
            lineTo(w, h); close()
        }
        fill.color = color
        c.drawPath(path, fill)
    }

    private fun dunes(c: Canvas, r: Random, baseline: Float, color: Int, crest: Int, layers: Int) {
        repeat(layers) {
            val path = Path().apply {
                moveTo(0f, h)
                lineTo(0f, baseline + r.nextFloat() * 60f * u)
                var x = 0f
                while (x < w) {
                    val span = (300 + r.nextFloat() * 400) * u
                    val peak = baseline - (20 + r.nextFloat() * 80) * u
                    quadTo(x + span / 2, peak, x + span, baseline + (r.nextFloat() - 0.5f) * 60f * u)
                    x += span
                }
                lineTo(w, h); close()
            }
            fill.alpha = 255
            fill.shader = LinearGradient(0f, baseline - 100f * u, 0f, h, crest, color, Shader.TileMode.CLAMP)
            c.drawPath(path, fill)
            fill.shader = null
        }
    }

    private fun ridge(c: Canvas, r: Random, baseline: Float, height: Float, color: Int) {
        val path = Path().apply {
            moveTo(0f, h)
            var x = 0f
            var y = baseline - r.nextFloat() * height
            lineTo(0f, y)
            while (x < w) {
                x += (30 + r.nextFloat() * 70) * u
                y = (y + (r.nextFloat() - 0.5f) * height * 0.6f).coerceIn(baseline - height, baseline)
                lineTo(x, y)
            }
            lineTo(w, h); close()
        }
        fill.color = color
        c.drawPath(path, fill)
    }

    private fun clouds(c: Canvas, r: Random, from: Float, to: Float, color: Int, alpha: Float, count: Int) {
        repeat(count) {
            val x = r.nextFloat() * w
            val y = from + r.nextFloat() * (to - from)
            cloud(c, x, y, (120 + r.nextFloat() * 160) * u, color, alpha)
        }
    }

    private fun cloud(c: Canvas, x: Float, y: Float, size: Float, color: Int, alpha: Float) {
        fill.color = withAlpha(color, alpha)
        c.drawOval(RectF(x - size, y - size * 0.28f, x + size, y + size * 0.28f), fill)
        c.drawOval(RectF(x - size * 0.55f, y - size * 0.5f, x + size * 0.25f, y + size * 0.1f), fill)
        c.drawOval(RectF(x - size * 0.1f, y - size * 0.42f, x + size * 0.7f, y + size * 0.12f), fill)
    }

    private fun garden(c: Canvas, r: Random, s: SceneSpec) {
        val horizon = h * 0.55f
        sky(c, s.top, s.middle, horizon)
        glow(c, w * 0.5f, horizon, 420f * u, s.light, 0.6f)
        band(c, horizon, h, blend(s.middle, s.land, 0.6f), darker(s.land, 0.75f))
        hills(c, r, horizon + 10f * u, 40f * u, blend(s.middle, s.land, 0.55f), 3)
        hills(c, r, horizon + 90f * u, 55f * u, s.land, 2)
        if (s.has(Feature.RIVER)) {
            fill.alpha = 255
            fill.shader = LinearGradient(0f, horizon + 60f * u, 0f, h, withAlpha(s.light, 0.75f), withAlpha(blend(s.light, s.middle, 0.5f), 0.9f), Shader.TileMode.CLAMP)
            val path = Path().apply {
                moveTo(w * 0.49f, horizon + 70f * u)
                cubicTo(w * 0.40f, h * 0.7f, w * 0.70f, h * 0.8f, w * 0.30f, h)
                lineTo(w * 0.80f, h)
                cubicTo(w * 0.95f, h * 0.8f, w * 0.50f, h * 0.7f, w * 0.51f, horizon + 70f * u)
                close()
            }
            c.drawPath(path, fill)
            fill.shader = null
        }
        val treeColor = darker(s.land, 0.6f)
        if (s.has(Feature.PALMS)) {
            palm(c, w * 0.12f, h * 0.92f, 520f * u, treeColor, 1f)
            palm(c, w * 0.88f, h * 0.95f, 600f * u, treeColor, -1f)
            palm(c, w * 0.25f, h * 0.8f, 320f * u, blend(treeColor, s.land, 0.4f), -0.6f)
        }
        if (s.has(Feature.TREES) || !s.has(Feature.PALMS)) {
            repeat(7) {
                val x = r.nextFloat() * w
                val base = horizon + (60 + r.nextFloat() * 120) * u
                tree(c, x, base, (50 + r.nextFloat() * 50) * u, blend(treeColor, s.land, 0.5f))
            }
            tree(c, w * 0.08f, h * 0.9f, 170f * u, treeColor)
            tree(c, w * 0.93f, h * 0.86f, 190f * u, treeColor)
        }
    }

    private fun tree(c: Canvas, x: Float, base: Float, size: Float, color: Int) {
        fill.color = color
        c.drawRect(x - size * 0.08f, base - size * 1.2f, x + size * 0.08f, base, fill)
        c.drawCircle(x, base - size * 1.5f, size * 0.8f, fill)
        c.drawCircle(x - size * 0.5f, base - size * 1.15f, size * 0.55f, fill)
        c.drawCircle(x + size * 0.5f, base - size * 1.2f, size * 0.6f, fill)
    }

    private fun palm(c: Canvas, x: Float, base: Float, height: Float, color: Int, lean: Float) {
        stroke.color = color
        stroke.strokeWidth = height * 0.045f
        val topX = x + lean * height * 0.18f
        val topY = base - height
        val trunk = Path().apply { moveTo(x, base); quadTo(x + lean * height * 0.02f, base - height * 0.5f, topX, topY) }
        c.drawPath(trunk, stroke)
        stroke.strokeWidth = height * 0.03f
        for (i in 0 until 7) {
            val angle = (-160 + i * 23) * PI.toFloat() / 180f
            val len = height * (0.32f + (i % 2) * 0.06f)
            val ex = topX + cos(angle) * len
            val ey = topY + sin(angle) * len * 0.55f + len * 0.25f
            val frond = Path().apply { moveTo(topX, topY); quadTo((topX + ex) / 2, topY - len * 0.2f, ex, ey) }
            c.drawPath(frond, stroke)
        }
    }

    private fun columns(c: Canvas, r: Random, base: Float, color: Int) {
        fill.color = color
        val count = 6
        val spacing = w / (count + 1)
        for (i in 1..count) {
            val x = spacing * i + (r.nextFloat() - 0.5f) * 30f * u
            val full = (260 + r.nextFloat() * 160) * u
            val standing = if (r.nextFloat() < 0.4f) full * (0.3f + r.nextFloat() * 0.4f) else full
            val cw = 34f * u
            c.drawRect(x - cw / 2, base - standing, x + cw / 2, base, fill)
            if (standing == full) c.drawRect(x - cw, base - standing - 18f * u, x + cw, base - standing, fill)
        }
        // A lintel across two of the columns that still stand.
        c.drawRect(spacing * 2 - 40f * u, base - 440f * u, spacing * 3 + 40f * u, base - 410f * u, fill)
    }

    private fun arches(c: Canvas, s: SceneSpec) {
        band(c, 0f, h, s.top, s.land)
        val rows = 3
        for (row in 0 until rows) {
            val depth = row / rows.toFloat()
            val scale = 1f - depth * 0.38f
            val count = 4 + row
            val archW = w / count
            val top = h * (0.18f + depth * 0.14f)
            val bottom = h * (0.92f - depth * 0.12f)
            for (i in 0 until count) {
                val left = i * archW + archW * 0.14f
                val right = (i + 1) * archW - archW * 0.14f
                val mid = (left + right) / 2
                val shoulder = top + (right - left) * 0.75f
                val opening = Path().apply {
                    moveTo(left, bottom)
                    lineTo(left, shoulder)
                    quadTo(left, top, mid, top - 30f * u * scale)
                    quadTo(right, top, right, shoulder)
                    lineTo(right, bottom)
                    close()
                }
                fill.alpha = 255
                fill.shader = LinearGradient(0f, top, 0f, bottom, withAlpha(s.light, 0.55f - depth * 0.2f), withAlpha(s.middle, 0.35f), Shader.TileMode.CLAMP)
                c.drawPath(opening, fill)
                fill.shader = null
            }
        }
        band(c, h * 0.9f, h, withAlpha(s.land, 0f), s.land)
        vignette(c)
    }

    private fun niche(c: Canvas, color: Int) {
        val left = w * 0.22f
        val right = w * 0.78f
        val top = h * 0.12f
        val bottom = h * 0.92f
        val shoulder = top + (right - left) * 0.6f
        val path = Path().apply {
            moveTo(left, bottom); lineTo(left, shoulder)
            quadTo(left, top, w / 2, top - 40f * u)
            quadTo(right, top, right, shoulder)
            lineTo(right, bottom); close()
        }
        fill.color = withAlpha(color, 0.45f)
        c.drawPath(path, fill)
        stroke.color = withAlpha(color, 0.9f)
        stroke.strokeWidth = 6f * u
        c.drawPath(path, stroke)
        // The chain the lamp hangs from.
        stroke.strokeWidth = 3f * u
        c.drawLine(w / 2, top - 40f * u, w / 2, lampY() - 120f * u, stroke)
    }

    private fun lampY() = h * 0.4f

    private fun starPattern(c: Canvas, color: Int) {
        stroke.color = withAlpha(color, 0.28f)
        stroke.strokeWidth = 2.2f * u
        val cell = 180f * u
        var y = -cell
        while (y < h + cell) {
            var x = -cell
            while (x < w + cell) {
                eightPointStar(c, x, y, cell * 0.42f)
                x += cell
            }
            y += cell
        }
    }

    private fun eightPointStar(c: Canvas, cx: Float, cy: Float, radius: Float) {
        // Two overlapping squares, one turned 45°: the khatam.
        for (turn in 0..1) {
            val path = Path()
            for (k in 0..3) {
                val angle = (k * 90 + turn * 45 + 45) * PI.toFloat() / 180f
                val x = cx + cos(angle) * radius
                val y = cy + sin(angle) * radius
                if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            c.drawPath(path, stroke)
        }
        c.drawCircle(cx, cy, radius * 0.38f, stroke)
    }

    private fun manuscript(c: Canvas, r: Random, s: SceneSpec) {
        band(c, 0f, h, s.middle, blend(s.middle, s.land, 0.25f))
        // The paper's grain.
        repeat(2500) {
            fill.color = withAlpha(s.land, r.nextFloat() * 0.08f)
            c.drawCircle(r.nextFloat() * w, r.nextFloat() * h, (1 + r.nextFloat() * 2.5f) * u, fill)
        }
        vignette(c)
        // An illuminated frame: double rule with corner ornaments.
        stroke.color = withAlpha(s.light, 0.85f)
        val inset = 46f * u
        stroke.strokeWidth = 5f * u
        c.drawRect(inset, inset, w - inset, h - inset, stroke)
        stroke.strokeWidth = 2f * u
        c.drawRect(inset + 18f * u, inset + 18f * u, w - inset - 18f * u, h - inset - 18f * u, stroke)
        for ((x, y) in listOf(inset to inset, w - inset to inset, inset to h - inset, w - inset to h - inset)) {
            eightPointStar(c, x, y, 34f * u)
        }
    }

    // ---------------------------------------------------------------------------------------
    // The moving layer, painted every frame. Kept cheap: a few dozen shapes at most.

    private fun animate(c: Canvas, s: SceneSpec, t: Float) {
        val r = Random(seed(s) xor 0x5f3759df)
        when (s.kind) {
            SceneKind.SKY, SceneKind.FIELDS -> {
                if (s.has(Feature.CLOUDS) || s.kind == SceneKind.SKY) driftClouds(c, r, t, s.light, 0.22f, h * 0.05f, h * 0.55f)
                if (s.kind == SceneKind.FIELDS) wheat(c, r, t, s)
            }
            SceneKind.RAYS -> rays(c, t, s.light, w * 0.5f, -h * 0.05f)
            SceneKind.WATER -> {
                ripples(c, t, s.light, w * 0.5f, h * 0.68f)
                if (s.has(Feature.RAIN)) rain(c, r, t, s.light, 0.3f)
            }
            SceneKind.RAIN -> rain(c, r, t, s.light, 0.45f)
            SceneKind.GARDEN -> motes(c, r, t, s.light, 26, h * 0.4f, h)
            SceneKind.EMBERS -> embers(c, r, t, s.light)
            SceneKind.STORM -> {
                driftClouds(c, r, t, darker(s.middle, 0.6f), 0.35f, 0f, h * 0.35f)
                if (s.has(Feature.RAIN)) rain(c, r, t, s.light, 0.35f)
                if (s.has(Feature.LIGHTNING)) lightning(c, r, t, s.light)
            }
            SceneKind.RUINS, SceneKind.DUNES -> {
                if (s.has(Feature.STARS)) twinkle(c, r, t, s.light, 50, h * 0.6f) else driftSand(c, r, t, s.light)
            }
            SceneKind.NEBULA, SceneKind.STARS -> twinkle(c, r, t, s.light, 70, h * 0.9f)
            SceneKind.MIST -> mist(c, r, t, s.light)
            SceneKind.SEA -> {
                waves(c, t, s)
                if (s.has(Feature.RAIN)) rain(c, r, t, s.light, 0.3f)
            }
            SceneKind.MOUNTAINS -> if (s.has(Feature.MIST)) mist(c, r, t, s.light) else if (s.has(Feature.STARS)) twinkle(c, r, t, s.light, 40, h * 0.5f)
            SceneKind.ARCHES -> motes(c, r, t, s.light, 30, h * 0.2f, h * 0.9f)
            SceneKind.LANTERN -> lantern(c, t, s)
            SceneKind.GEOMETRIC, SceneKind.MANUSCRIPT -> sweep(c, t, s.light)
            SceneKind.PHOTO -> Unit
        }
    }

    private fun driftClouds(c: Canvas, r: Random, t: Float, color: Int, alpha: Float, from: Float, to: Float) {
        repeat(5) {
            val speed = (8 + r.nextFloat() * 14) * u
            val size = (140 + r.nextFloat() * 160) * u
            val span = w + size * 2
            val x = ((r.nextFloat() * span + t * speed) % span) - size
            cloud(c, x, from + r.nextFloat() * (to - from), size, color, alpha)
        }
    }

    private fun rain(c: Canvas, r: Random, t: Float, color: Int, alpha: Float) {
        stroke.color = withAlpha(color, alpha)
        stroke.strokeWidth = 2f * u
        val length = 40f * u
        repeat(140) {
            val x0 = r.nextFloat() * w
            val speed = (900 + r.nextFloat() * 500) * u
            val y = (r.nextFloat() * h + t * speed) % (h + length) - length
            c.drawLine(x0, y, x0 - length * 0.18f, y + length, stroke)
        }
    }

    private fun twinkle(c: Canvas, r: Random, t: Float, color: Int, count: Int, below: Float) {
        repeat(count) {
            val x = r.nextFloat() * w
            val y = r.nextFloat() * below
            val phase = r.nextFloat() * 6.28f
            val speed = 0.8f + r.nextFloat() * 1.6f
            val glowAmount = (sin(t * speed + phase) + 1f) / 2f
            fill.color = withAlpha(color, 0.15f + 0.75f * glowAmount)
            c.drawCircle(x, y, (1.4f + glowAmount * 1.8f) * u, fill)
        }
    }

    private fun motes(c: Canvas, r: Random, t: Float, color: Int, count: Int, from: Float, to: Float) {
        repeat(count) {
            val x = r.nextFloat() * w + sin(t * 0.3f + it) * 30f * u
            val span = to - from
            val y = to - ((r.nextFloat() * span + t * (10 + r.nextFloat() * 20) * u) % span)
            fill.color = withAlpha(color, 0.25f + 0.3f * ((sin(t + it) + 1) / 2))
            c.drawCircle(x, y, (2f + r.nextFloat() * 3f) * u, fill)
        }
    }

    private fun embers(c: Canvas, r: Random, t: Float, color: Int) {
        repeat(90) {
            val speed = (40 + r.nextFloat() * 90) * u
            val x = r.nextFloat() * w + sin(t * (0.5f + r.nextFloat()) + it) * 24f * u
            val y = h - ((r.nextFloat() * h + t * speed) % h)
            val life = y / h
            fill.color = withAlpha(color, (0.15f + 0.7f * (1 - life)) * (0.6f + 0.4f * sin(t * 6 + it)))
            c.drawCircle(x, y, (1.5f + r.nextFloat() * 3.5f) * u, fill)
        }
    }

    private fun lightning(c: Canvas, r: Random, t: Float, color: Int) {
        val period = 5.5f
        val cycle = (t % period) / period
        val flashIndex = (t / period).toInt()
        if (cycle > 0.06f) return
        val strength = 1f - cycle / 0.06f
        fill.color = withAlpha(color, 0.35f * strength)
        c.drawRect(0f, 0f, w, h, fill)
        val bolt = Random(flashIndex * 31 + 7)
        stroke.color = withAlpha(color, strength)
        stroke.strokeWidth = 5f * u
        var x = w * (0.2f + bolt.nextFloat() * 0.6f)
        var y = 0f
        val path = Path().apply { moveTo(x, y) }
        while (y < h * 0.6f) {
            x += (bolt.nextFloat() - 0.5f) * 90f * u
            y += (40 + bolt.nextFloat() * 70) * u
            path.lineTo(x, y)
        }
        c.drawPath(path, stroke)
    }

    private fun driftSand(c: Canvas, r: Random, t: Float, color: Int) {
        stroke.color = withAlpha(color, 0.12f)
        stroke.strokeWidth = 2f * u
        repeat(24) {
            val y = h * (0.6f + r.nextFloat() * 0.35f)
            val len = (80 + r.nextFloat() * 160) * u
            val x = (r.nextFloat() * (w + len) + t * (30 + r.nextFloat() * 50) * u) % (w + len) - len
            c.drawLine(x, y, x + len, y - 6f * u, stroke)
        }
    }

    private fun mist(c: Canvas, r: Random, t: Float, color: Int) {
        repeat(6) {
            val y = h * (0.45f + r.nextFloat() * 0.5f)
            val bandW = w * (0.8f + r.nextFloat() * 0.8f)
            val x = ((r.nextFloat() * (w + bandW) + t * (10 + r.nextFloat() * 18) * u) % (w + bandW)) - bandW
            fill.alpha = 255
            fill.shader = RadialGradient(x + bandW / 2, y, bandW / 2, withAlpha(color, 0.22f), withAlpha(color, 0f), Shader.TileMode.CLAMP)
            c.save()
            c.scale(1f, 0.22f, x + bandW / 2, y)
            c.drawCircle(x + bandW / 2, y, bandW / 2, fill)
            c.restore()
            fill.shader = null
        }
    }

    private fun rays(c: Canvas, t: Float, color: Int, sx: Float, sy: Float) {
        val length = max(w, h) * 1.3f
        for (i in 0 until 7) {
            val angle = (60 + i * 10 + sin(t * 0.25f + i) * 2f) * PI.toFloat() / 180f
            val spread = 3.2f * PI.toFloat() / 180f
            val alpha = 0.07f + 0.05f * ((sin(t * 0.7f + i * 1.3f) + 1) / 2)
            fill.color = withAlpha(color, alpha)
            val path = Path().apply {
                moveTo(sx, sy)
                lineTo(sx + cos(angle - spread) * length, sy + sin(angle - spread) * length)
                lineTo(sx + cos(angle + spread) * length, sy + sin(angle + spread) * length)
                close()
            }
            c.drawPath(path, fill)
        }
    }

    private fun ripples(c: Canvas, t: Float, color: Int, x: Float, y: Float) {
        stroke.strokeWidth = 2.5f * u
        for (i in 0 until 5) {
            val progress = ((t * 0.18f + i / 5f) % 1f)
            val radius = progress * w * 0.7f
            stroke.color = withAlpha(color, 0.35f * (1 - progress))
            c.drawOval(RectF(x - radius, y - radius * 0.22f, x + radius, y + radius * 0.22f), stroke)
        }
    }

    private fun waves(c: Canvas, t: Float, s: SceneSpec) {
        val horizon = h * 0.55f
        val rough = s.has(Feature.ROUGH)
        stroke.strokeWidth = (if (rough) 3.5f else 2.2f) * u
        for (i in 0 until 14) {
            val depth = i / 14f
            val y = horizon + (h - horizon) * depth * depth + 8f * u
            val amp = (if (rough) 22f else 7f) * u * (0.4f + depth)
            stroke.color = withAlpha(s.light, 0.08f + 0.18f * depth)
            val path = Path()
            var x = 0f
            while (x <= w) {
                val yy = y + amp * sin(x / (120f * u * (0.5f + depth)) + t * (1.2f + i * 0.1f))
                if (x == 0f) path.moveTo(x, yy) else path.lineTo(x, yy)
                x += 12f * u
            }
            c.drawPath(path, stroke)
        }
    }

    private fun wheat(c: Canvas, r: Random, t: Float, s: SceneSpec) {
        stroke.color = blend(s.land, s.light, 0.35f)
        stroke.strokeWidth = 3f * u
        repeat(70) {
            val x = r.nextFloat() * w
            val base = h
            val height = (180 + r.nextFloat() * 220) * u
            val sway = sin(t * 1.3f + x / (90f * u)) * 26f * u
            c.drawLine(x, base, x + sway, base - height, stroke)
            fill.color = stroke.color
            c.drawOval(RectF(x + sway - 6f * u, base - height - 30f * u, x + sway + 6f * u, base - height + 6f * u), fill)
        }
    }

    private fun lantern(c: Canvas, t: Float, s: SceneSpec) {
        val y = lampY()
        val flicker = 0.85f + 0.1f * sin(t * 3.1f) + 0.05f * sin(t * 7.3f)
        glow(c, w / 2, y, 520f * u * flicker, s.light, 0.55f)
        // The glass, "as if it were a pearly star".
        fill.color = withAlpha(s.light, 0.9f)
        val glass = Path().apply {
            moveTo(w / 2, y - 120f * u)
            cubicTo(w / 2 + 120f * u, y - 60f * u, w / 2 + 110f * u, y + 90f * u, w / 2, y + 120f * u)
            cubicTo(w / 2 - 110f * u, y + 90f * u, w / 2 - 120f * u, y - 60f * u, w / 2, y - 120f * u)
            close()
        }
        c.drawPath(glass, fill)
        glow(c, w / 2, y, 90f * u, Color.WHITE, 0.8f * flicker)
    }

    private fun sweep(c: Canvas, t: Float, color: Int) {
        val x = (t * 60f * u) % (w + h) - h * 0.2f
        glow(c, x, h * 0.35f + sin(t * 0.2f) * h * 0.15f, max(w, h) * 0.35f, color, 0.08f)
    }

    // ---------------------------------------------------------------------------------------

    private fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((Color.alpha(color) * alpha.coerceIn(0f, 1f)).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    private fun blend(a: Int, b: Int, f: Float): Int = Color.argb(
        255,
        (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt(),
    )

    private fun darker(color: Int, f: Float): Int =
        Color.argb(Color.alpha(color), (Color.red(color) * f).toInt(), (Color.green(color) * f).toInt(), (Color.blue(color) * f).toInt())

}
