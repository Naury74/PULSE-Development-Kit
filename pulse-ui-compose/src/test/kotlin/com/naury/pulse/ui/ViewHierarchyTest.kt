package com.naury.pulse.ui

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ViewHierarchyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `View 계층을 읽되 글자 내용은 담지 않고 길이만 담는다`() {
        val label = TextView(context).apply {
            text = "비밀번호 1234"
            isClickable = true
        }
        val hidden = View(context).apply { visibility = View.GONE }
        val root = FrameLayout(context).apply {
            addView(label)
            addView(hidden)
        }

        val snapshot = ViewHierarchy.capture("Login", root, null)

        assertThat(snapshot.nodeCount).isEqualTo(3)
        assertThat(snapshot.root.name).isEqualTo("FrameLayout")
        val text = snapshot.root.children.first()
        assertThat(text.name).isEqualTo("TextView")
        assertThat(text.properties).contains("text" to "9 chars")
        assertThat(text.properties).contains("clickable" to "true")
        assertThat(snapshot.root.children[1].properties).contains("visibility" to "gone")
        val everything = generateSequence(listOf(snapshot.root)) { level -> level.flatMap { it.children }.ifEmpty { null } }.flatten()
        assertThat(everything.flatMap { node -> node.properties.map { it.second } }.joinToString()).doesNotContain("1234")
    }
}
