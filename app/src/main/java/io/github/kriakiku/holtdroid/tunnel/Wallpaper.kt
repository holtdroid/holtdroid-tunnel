package io.github.kriakiku.holtdroid.tunnel

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display

object Wallpaper {
    fun set(context: Context, rows: List<Pair<String, String>>) {
        val wm = WallpaperManager.getInstance(context)
        wm.setBitmap(render(context, rows, false), null, false, WallpaperManager.FLAG_SYSTEM)
        wm.setBitmap(render(context, rows, true), null, false, WallpaperManager.FLAG_LOCK)
    }

    private fun paint(color: Int, size: Float = 0f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        if (size > 0) textSize = size
    }

    private fun drawLogo(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val head = Path().apply {
            moveTo(8f, 40f)
            arcTo(RectF(8f, 16f, 56f, 64f), 180f, 180f)
            lineTo(56f, 48f)
            lineTo(8f, 48f)
            close()
        }
        val antenna = paint(0xff3ddc84.toInt()).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            strokeCap = Paint.Cap.ROUND
        }
        val dark = paint(0xff1b1b1b.toInt())
        canvas.save()
        canvas.translate(cx - size / 2, cy - size / 2)
        canvas.scale(size / 64, size / 64)
        canvas.drawPath(head, paint(0xff8a5a3c.toInt()))
        canvas.drawLine(20f, 16f, 16f, 9f, antenna)
        canvas.drawLine(44f, 16f, 48f, 9f, antenna)
        canvas.drawCircle(23f, 32f, 3.5f, dark)
        canvas.drawCircle(41f, 32f, 3.5f, dark)
        canvas.drawOval(27f, 35.5f, 37f, 42.5f, paint(0xffd9b48f.toInt()))
        canvas.drawCircle(32f, 38f, 1.8f, dark)
        canvas.drawRoundRect(4f, 48f, 60f, 56f, 4f, 4f, paint(0xff3ddc84.toInt()))
        canvas.restore()
    }

    private fun render(context: Context, given: List<Pair<String, String>>, details: Boolean): Bitmap {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics)
        val width = minOf(metrics.widthPixels, metrics.heightPixels)
        val height = maxOf(metrics.widthPixels, metrics.heightPixels)
        val dpi = metrics.densityDpi
        val dp = dpi / 160f
        val rows = given + listOf(
            "android" to "${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}",
            "abi" to Build.SUPPORTED_ABIS.joinToString(", "),
            "display" to "$width×$height · $dpi dpi",
        )

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xff0b0f14.toInt())

        val minor = paint(0x0affffff)
        val major = paint(0x14ffffff)
        var i = 0
        var x = 0f
        while (x < width) {
            canvas.drawLine(x, 0f, x, height.toFloat(), if (i % 6 != 0) minor else major)
            x += 8 * dp
            i++
        }
        i = 0
        var y = 0f
        while (y < height) {
            canvas.drawLine(0f, y, width.toFloat(), y, if (i % 6 != 0) minor else major)
            y += 8 * dp
            i++
        }

        val cy = 72 * dp + 48 * dp
        drawLogo(canvas, width / 2f, cy, 96 * dp)
        if (!details) return bitmap

        val title = paint(0xffe6edf3.toInt(), 28 * dp).apply {
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("holtdroid", width / 2f, cy + 48 * dp + 36 * dp, title)
        val tag = paint(0xff3ddc84.toInt(), 12 * dp).apply {
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("debug session · not a real device", width / 2f, cy + 48 * dp + 58 * dp, tag)

        val label = paint(0xff7d8590.toInt(), 12 * dp).apply { typeface = Typeface.MONOSPACE }
        val value = paint(0xffe6edf3.toInt(), 12 * dp).apply { typeface = Typeface.MONOSPACE }
        val charW = value.measureText("M")
        val labelChars = rows.maxOf { it.first.length } + 2
        val valueChars = maxOf(8, ((width - 64 * dp) / charW).toInt() - labelChars)
        val lines = rows.map { (k, v) -> k to fit(v, valueChars) }
        val lineH = 20 * dp
        val blockW = (labelChars + lines.maxOf { it.second.length }) * charW
        val left = (width - blockW) / 2
        val top = cy + 48 * dp + 84 * dp
        lines.forEachIndexed { index, (k, v) ->
            val baseline = top + lineH * (index + 1) - 6 * dp
            canvas.drawText(k, left, baseline, label)
            canvas.drawText(v, left + labelChars * charW, baseline, value)
        }

        val pad = 12 * dp
        val l = left - pad
        val t = top - pad / 2
        val r = left + blockW + pad
        val b = top + lineH * lines.size + pad / 2
        canvas.drawRect(l, t, r, b, paint(0x99ff3b30.toInt()).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp
        })
        val corner = paint(0xffd946ef.toInt()).apply { strokeWidth = 2 * dp }
        val c = 8 * dp
        for ((cx0, cy0, dx, dy) in listOf(listOf(l, t, c, c), listOf(r, t, -c, c), listOf(l, b, c, -c), listOf(r, b, -c, -c))) {
            canvas.drawLine(cx0, cy0, cx0 + dx, cy0, corner)
            canvas.drawLine(cx0, cy0, cx0, cy0 + dy, corner)
        }
        return bitmap
    }

    fun fit(s: String, max: Int): String {
        if (s.length <= max) return s
        val half = (max - 1) / 2
        return s.take(half) + "…" + s.takeLast(max - 1 - half)
    }
}
