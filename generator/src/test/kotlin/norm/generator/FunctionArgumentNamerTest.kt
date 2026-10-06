package norm.generator

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

class FunctionArgumentNamerTest {

  private val digestOid = 100
  private val likeEscapeOid = 101
  private val functions = mapOf(
    digestOid to FunctionSignature("public", "digest", listOf("", "type")),
    likeEscapeOid to FunctionSignature("pg_catalog", "like_escape", emptyList()),
  )
  private val namer = FunctionArgumentNamer(functions::get)
  private val constant = "{CONST :consttype 25 :constisnull true}"

  private fun parameter(id: Int) = "{PARAM :paramkind 0 :paramid $id :paramtype 25}"

  private fun call(oid: Int, vararg arguments: String) =
    "{FUNCEXPR :funcid $oid :funcformat 0 :args (${arguments.joinToString(" ")})}"

  private fun tree(firstIndex: Int, expression: String) = tree((firstIndex until firstIndex + 100).toList(), expression)

  private fun tree(parameterIndexes: List<Int>, expression: String) =
    FunctionArgumentNamer.ChunkTree(parameterIndexes, parsePgNodeValue("(($expression))"))

  @Test
  fun `trees that disagree on the calls they hold get no names`() {
    val chunks = listOf(
      tree(0, call(digestOid, parameter(1), constant)),
      tree(100, constant),
    )

    assertThat(namer.name(chunks)).isEmpty()
  }

  @Test
  fun `a statement without a tree gets no names`() {
    assertThat(namer.name(emptyList())).isEmpty()
  }

  @Test
  fun `the parameters of one argument are summed over the trees before the argument is named`() {
    val chunks = listOf(
      tree(0, call(digestOid, "{OPEXPR :args (${parameter(100)} $constant)}", constant)),
      tree(100, call(digestOid, "{OPEXPR :args ($constant ${parameter(1)})}", constant)),
    )

    assertThat(namer.name(chunks)).isEmpty()
  }

  @Test
  fun `an argument that holds one parameter in one of the trees is named`() {
    val chunks = listOf(
      tree(0, call(digestOid, "{OPEXPR :args (${parameter(100)} $constant)}", constant)),
      tree(100, call(digestOid, "{OPEXPR :args ($constant $constant)}", constant)),
    )

    assertThat(namer.name(chunks)).isEqualTo(mapOf(100 to "digest_param1"))
  }

  @Test
  fun `a pattern escape call is not named`() {
    val chunks =
      listOf(tree(0, "{OPEXPR :args (${call(likeEscapeOid, parameter(1))} ${call(digestOid, parameter(2))})}"))

    assertThat(namer.name(chunks)).isEqualTo(mapOf(2 to "digest_param1"))
  }

  @Test
  fun `a chunk that skips a parameter maps its numbers to the parameters it holds`() {
    val chunks = listOf(tree(listOf(0, 2), call(digestOid, parameter(2), constant)))

    assertThat(namer.name(chunks)).isEqualTo(mapOf(3 to "digest_param1"))
  }
}
