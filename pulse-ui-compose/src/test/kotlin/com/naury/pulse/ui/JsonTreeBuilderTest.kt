package com.naury.pulse.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class JsonTreeBuilderTest {
    @Test
    fun `객체·배열·원시 값을 종류와 경로로 만든다`() {
        val tree = JsonTreeBuilder.build("""{"user":{"name":"kim","age":7,"ok":true,"none":null},"tags":["a"]}""")!!

        val user = tree.root.children.first { it.label == "user" }
        assertThat(tree.root.summary).isEqualTo("{2}")
        assertThat(user.path).isEqualTo("$.user")
        assertThat(user.children.map { it.kind }).containsExactly(
            JsonNode.Kind.STRING,
            JsonNode.Kind.NUMBER,
            JsonNode.Kind.BOOLEAN,
            JsonNode.Kind.NULL,
        ).inOrder()
        assertThat(user.children.first().summary).isEqualTo("\"kim\"")
        assertThat(tree.root.children.last().children.single().path).isEqualTo("$.tags[0]")
        assertThat(tree.truncated).isFalse()
    }

    @Test
    fun `노드 상한을 넘으면 앞부분만 만들고 잘렸다고 알린다`() {
        val text = (1..100).joinToString(",", "[", "]")

        val tree = JsonTreeBuilder.build(text, maxNodes = 10)!!

        assertThat(tree.truncated).isTrue()
        assertThat(tree.nodeCount).isEqualTo(10)
        assertThat(tree.root.children).hasSize(9)
    }

    @Test
    fun `해석할 수 없으면 null`() {
        assertThat(JsonTreeBuilder.build("{")).isNull()
    }
}
