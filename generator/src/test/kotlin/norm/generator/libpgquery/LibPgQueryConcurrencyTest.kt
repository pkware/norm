package norm.generator.libpgquery

import assertk.assertThat
import assertk.assertions.isEqualTo
import norm.generator.JsonValue
import norm.generator.descendants
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LibPgQueryConcurrencyTest {

  @Test
  fun `concurrent callers each get the result for their own statement`() {
    val statements = (1..8).map { "SELECT col$it , other$it AS alias FROM table$it" }
    val pool = Executors.newFixedThreadPool(8)
    try {
      val calls = (1..400).map { round ->
        val sql = statements[round % statements.size]
        sql to
          pool.submit<Pair<JsonValue.JsonObject, List<ScanToken>>> { LibPgQuery.parse(sql) to LibPgQuery.scan(sql) }
      }
      for ((sql, future) in calls) {
        val (tree, tokens) = future.get(2, TimeUnit.MINUTES)
        assertThat(relationNames(tree)).isEqualTo(listOf(sql.substringAfter("FROM ")))
        assertThat(tokens.map { sql.substring(it.start, it.end) })
          .isEqualTo(sql.split(' ').filter { it.isNotEmpty() })
      }
    } finally {
      pool.shutdownNow()
    }
  }

  @Test
  fun `concurrent syntax errors do not disturb successful calls`() {
    val pool = Executors.newFixedThreadPool(8)
    try {
      val calls = (1..200).map { round ->
        val valid = round % 2 == 0
        val sql = if (valid) "SELECT $round" else "SELEC $round"
        valid to pool.submit<Result<JsonValue.JsonObject>> { runCatching { LibPgQuery.parse(sql) } }
      }
      for ((valid, future) in calls) {
        val result = future.get(2, TimeUnit.MINUTES)
        assertThat(result.isSuccess).isEqualTo(valid)
        if (!valid) assertThat(result.exceptionOrNull()?.message).isEqualTo("syntax error at or near \"SELEC\"")
      }
    } finally {
      pool.shutdownNow()
    }
  }

  private fun relationNames(tree: JsonValue): List<String> = tree.descendants()
    .filterIsInstance<JsonValue.JsonObject>()
    .mapNotNull { it.objectField("RangeVar")?.stringField("relname") }
    .toList()
}
