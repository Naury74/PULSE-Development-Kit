package com.naury.pulse.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotEditorTest {
    @Test
    fun `가리기는 그 영역의 픽셀을 검은색으로 덮어쓰고 나머지는 그대로 둔다`() {
        val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }

        val png = render(source, listOf(Mark.Area(EditTool.MASK, Offset(0.1f, 0.1f), Offset(0.5f, 0.5f))))

        val result = BitmapFactory.decodeByteArray(png, 0, png.size)
        assertThat(result.width).isEqualTo(100)
        assertThat(result.getPixel(30, 30)).isEqualTo(Color.BLACK)
        assertThat(result.getPixel(80, 80)).isEqualTo(Color.WHITE)
        assertThat(source.getPixel(30, 30)).isEqualTo(Color.WHITE)
    }
}
