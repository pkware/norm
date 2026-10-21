package norm.generator

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

class LibPgQueryTimingTest {
  private val queries = listOf(
    "SELECT id, name AS n, COUNT(*) FROM users WHERE a = 1",
    "UPDATE t SET x = 1 RETURNING id, name, upper(name) AS up",
    "WITH c AS (SELECT 1 AS a) SELECT a, a + 1 AS b, CASE WHEN a > 1 THEN 'x' ELSE 'y' END AS z FROM c",
  )

  @Test
  fun `prints cold and warm latency and agrees with the hand-written parser`() {
    val cold = System.nanoTime()
    LibPgQuery.parseJson("SELECT 1")
    println("TIMING cold first parse: ${(System.nanoTime() - cold) / 1_000_000} ms")
    for (sql in queries) {
      repeat(200) { libPgQueryParseSelectItems(sql) }
      val rounds = 500
      val prototype = System.nanoTime()
      repeat(rounds) { libPgQueryParseSelectItems(sql) }
      val prototypeMicros = (System.nanoTime() - prototype) / 1000 / rounds
      val production = System.nanoTime()
      repeat(rounds) { parseSelectItems(sql) }
      val productionMicros = (System.nanoTime() - production) / 1000 / rounds
      val parseOnly = System.nanoTime()
      repeat(rounds) { LibPgQuery.parseJson(sql) }
      val parseMicros = (System.nanoTime() - parseOnly) / 1000 / rounds
      println(
        "TIMING $sql => prototype $prototypeMicros us, parse only $parseMicros us, hand-written $productionMicros us",
      )
      assertThat(
        libPgQueryParseSelectItems(sql).map {
          it.expression
        },
      ).isEqualTo(parseSelectItems(sql).map { it.expression })
    }
  }
}
