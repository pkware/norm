package norm.generator

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LibPgQueryConcurrencyTest {
  @Test
  fun `concurrent callers each get their own statement's items`() {
    val statements = (1..8).map { "SELECT col$it, other$it AS alias FROM table$it" }
    val pool = Executors.newFixedThreadPool(8)
    try {
      val results = (1..400).map { round ->
        val sql = statements[round % statements.size]
        sql to pool.submit<List<SelectItem>> { libPgQueryParseSelectItems(sql) }
      }
      for ((sql, future) in results) {
        assertThat(future.get(2, TimeUnit.MINUTES)).isEqualTo(parseSelectItems(sql))
      }
    } finally {
      pool.shutdownNow()
    }
  }
}
