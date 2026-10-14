package com.naury.pulse.ui

import android.annotation.SuppressLint
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull

/**
 * 화면 구조의 노드 하나. 글자 내용은 담지 않고 길이만 담는다(개인정보).
 *
 * @property bounds 창 기준 픽셀 영역.
 * @property properties 화면에 보여 줄 속성(이름, 값).
 */
internal class UiNode(
    val kind: String,
    val name: String,
    val id: String?,
    val bounds: Rect,
    val properties: List<Pair<String, String>>,
    val children: List<UiNode>,
)

/** 한 순간의 화면 구조와 (찍을 수 있었다면) 화면 캡처. */
internal class UiSnapshot(
    val screen: String,
    val root: UiNode,
    val png: ByteArray?,
    val windowWidth: Int,
    val windowHeight: Int,
    val nodeCount: Int,
    val truncated: Boolean,
)

/** 플로팅 버튼이 잡은 화면 구조. 인스펙터 화면이 열어 보여 준다. 메모리에만 있다. */
internal object UiSnapshots {
    var pending by mutableStateOf<UiSnapshot?>(null)
}

/**
 * View 계층과 Compose 화면의 Semantics 계층을 읽는다. Compose는 테스트용 공개 인터페이스(`ViewRootForTest`)로 읽으므로
 * Compose 버전이 바뀌어 읽지 못하면 그 View까지만 보여 준다. 노드는 [MAX_NODES]개까지.
 */
internal object ViewHierarchy {
    private const val MAX_NODES = 3_000

    fun capture(screen: String, root: View, png: ByteArray?): UiSnapshot {
        val counter = intArrayOf(0)
        val node = view(root, counter)
        return UiSnapshot(screen, node, png, root.width, root.height, counter[0], counter[0] >= MAX_NODES)
    }

    private fun view(view: View, counter: IntArray): UiNode {
        counter[0]++
        val children = when {
            counter[0] >= MAX_NODES -> emptyList()
            view is ViewRootForTest -> compose(view, counter)
            view is ViewGroup -> (0 until view.childCount).map { this.view(view.getChildAt(it), counter) }
            else -> emptyList()
        }
        return UiNode("View", view.javaClass.simpleName, idName(view), bounds(view), viewProperties(view), children)
    }

    @SuppressLint("VisibleForTests")
    @Suppress("TooGenericExceptionCaught")
    private fun compose(root: ViewRootForTest, counter: IntArray): List<UiNode> = try {
        listOf(semantics(root.semanticsOwner.unmergedRootSemanticsNode, counter))
    } catch (_: RuntimeException) {
        emptyList()
    }

    private fun semantics(node: SemanticsNode, counter: IntArray): UiNode {
        counter[0]++
        val config = node.config
        val role = config.getOrNull(SemanticsProperties.Role)?.toString()
        val tag = config.getOrNull(SemanticsProperties.TestTag)
        val text = config.getOrNull(SemanticsProperties.Text)?.sumOf { it.length }
        val description = config.getOrNull(SemanticsProperties.ContentDescription)?.sumOf { it.length }
        val properties = buildList {
            tag?.let { add("testTag" to it) }
            role?.let { add("role" to it) }
            if (config.contains(SemanticsProperties.Disabled)) add("enabled" to "false")
            if (config.any { it.key.name == "OnClick" }) add("clickable" to "true")
            config.getOrNull(SemanticsProperties.Selected)?.let { add("selected" to it.toString()) }
            config.getOrNull(SemanticsProperties.ToggleableState)?.let { add("toggle" to it.toString()) }
            text?.let { add("text" to "$it chars") }
            description?.let { add("contentDescription" to "$it chars") }
            add("size" to "${node.size.width}×${node.size.height}")
        }
        val bounds = node.boundsInWindow.let { Rect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt()) }
        val children = if (counter[0] >= MAX_NODES) emptyList() else node.children.map { semantics(it, counter) }
        return UiNode("Compose", role ?: tag ?: "Node", tag, bounds, properties, children)
    }

    private fun viewProperties(view: View): List<Pair<String, String>> = buildList {
        add("visibility" to visibility(view.visibility))
        if (!view.isEnabled) add("enabled" to "false")
        if (view.isClickable) add("clickable" to "true")
        if (view.isFocusable) add("focusable" to "true")
        if (view.alpha < 1f) add("alpha" to "%.2f".format(view.alpha))
        if (view.paddingLeft + view.paddingTop + view.paddingRight + view.paddingBottom > 0) {
            add("padding" to "${view.paddingLeft} ${view.paddingTop} ${view.paddingRight} ${view.paddingBottom}")
        }
        if (view.elevation > 0f) add("elevation" to "%.1f".format(view.elevation))
        (view as? TextView)?.let { add("text" to "${it.text?.length ?: 0} chars") }
        view.contentDescription?.let { add("contentDescription" to "${it.length} chars") }
        add("size" to "${view.width}×${view.height}")
    }

    private fun idName(view: View): String? = if (view.id == View.NO_ID) {
        null
    } else {
        try {
            view.resources.getResourceEntryName(view.id)
        } catch (_: android.content.res.Resources.NotFoundException) {
            null
        }
    }

    private fun bounds(view: View): Rect {
        val location = IntArray(2)
        view.getLocationInWindow(location)
        return Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
    }

    private fun visibility(value: Int) = when (value) {
        View.VISIBLE -> "visible"
        View.INVISIBLE -> "invisible"
        else -> "gone"
    }
}
