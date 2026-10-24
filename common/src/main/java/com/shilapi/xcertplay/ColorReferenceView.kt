package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/** Same 8 x 3 RGB patches as the encoded fixtures, independent of the app palette. */
internal class ColorReferenceView(context: Context) : View(context) {
    private val paint = Paint()
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        colors.forEachIndexed { index, color ->
            paint.color = color
            val column = index % 8; val row = index / 8
            canvas.drawRect(column * width / 8f, row * height / 3f,
                (column + 1) * width / 8f, (row + 1) * height / 3f, paint)
        }
    }
    companion object {
        val colors: List<Int> = (listOf(0, 4, 8, 12, 16, 20, 24, 32) +
            listOf(192, 208, 224, 232, 240, 248, 252, 255)).map { Color.rgb(it, it, it) } +
            listOf(Color.RED, Color.GREEN, Color.BLUE, Color.CYAN, Color.MAGENTA, Color.YELLOW, Color.rgb(128, 128, 128), Color.WHITE)
    }
}
