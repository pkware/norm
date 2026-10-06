package norm.generator

import assertk.assertThat
import assertk.assertions.containsExactly
import org.junit.jupiter.api.Test

class PgNodeValueTest {

  @Test
  fun `walkTopDown yields each value before its children, in field and list order`() {
    val tree = parsePgNodeValue("{A :x {B :y 1} :z ({C :w 2} 3)}")

    val visited = tree.walkTopDown().map {
      when (it) {
        is PgNodeValue.Node -> it.name
        is PgNodeValue.Atom -> it.text
        is PgNodeValue.Sequence -> "list"
      }
    }

    assertThat(visited.toList()).containsExactly("A", "B", "1", "list", "C", "2", "3")
  }

  @Test
  fun `nodesTopDown yields only the nodes`() {
    val tree = parsePgNodeValue("{A :x ({B :y 1} {C :y 2})}")

    assertThat(tree.nodesTopDown().map { it.name }.toList()).containsExactly("A", "B", "C")
  }
}
