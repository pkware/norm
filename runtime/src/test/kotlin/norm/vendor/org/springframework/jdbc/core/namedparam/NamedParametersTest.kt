package norm.vendor.org.springframework.jdbc.core.namedparam

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class NamedParametersTest {

  @Nested
  inner class ScalarValues {
    @Test
    fun `scalar value yields one placeholder and one argument`() {
      val result = NamedParameters.substitute("SELECT 1 WHERE id = :id", mapOf("id" to 42))

      assertThat(result.sql).isEqualTo("SELECT 1 WHERE id = ?")
      assertThat(result.arguments).containsExactly(42)
    }

    @Test
    fun `null value yields one placeholder and one null argument`() {
      val result = NamedParameters.substitute("SELECT 1 WHERE email = :email", mapOf("email" to null))

      assertThat(result.sql).isEqualTo("SELECT 1 WHERE email = ?")
      assertThat(result.arguments).containsExactly(null)
    }

    @Test
    fun `top level array stays a single placeholder and a single argument`() {
      val array = arrayOf(1, 2, 3)

      val result = NamedParameters.substitute("SELECT 1 WHERE ids = :ids", mapOf("ids" to array))

      assertThat(result.sql).isEqualTo("SELECT 1 WHERE ids = ?")
      assertThat(result.arguments).containsExactly(array)
    }

    @Test
    fun `sql without parameters is returned unchanged`() {
      val result = NamedParameters.substitute("SELECT 1", emptyMap())

      assertThat(result.sql).isEqualTo("SELECT 1")
      assertThat(result.arguments).isEmpty()
    }
  }

  @Nested
  inner class IterableValues {
    @Test
    fun `iterable expands to one placeholder and one argument per element`() {
      val result = NamedParameters.substitute("SELECT 1 WHERE id IN (:ids)", mapOf("ids" to listOf(1, 2, 3)))

      assertThat(result.sql).isEqualTo("SELECT 1 WHERE id IN (?, ?, ?)")
      assertThat(result.arguments).containsExactly(1, 2, 3)
    }

    @Test
    fun `iterable of arrays expands to a parenthesized tuple per element`() {
      val tuples = listOf(arrayOf<Any?>("John", 35), arrayOf<Any?>("Ann", 50))

      val result = NamedParameters.substitute(
        "SELECT 1 WHERE (name, age) IN (:tuples)",
        mapOf("tuples" to tuples),
      )

      assertThat(result.sql).isEqualTo("SELECT 1 WHERE (name, age) IN ((?, ?), (?, ?))")
      assertThat(result.arguments).containsExactly("John", 35, "Ann", 50)
    }

    @Test
    fun `empty iterable yields empty parentheses and no arguments`() {
      val result = NamedParameters.substitute("SELECT 1 WHERE id IN (:ids)", mapOf("ids" to emptyList<Int>()))

      assertThat(result.sql).isEqualTo("SELECT 1 WHERE id IN ()")
      assertThat(result.arguments).isEmpty()
    }

    @Test
    fun `null element inside an iterable yields a placeholder and a null argument`() {
      val result = NamedParameters.substitute("SELECT 1 WHERE id IN (:ids)", mapOf("ids" to listOf(1, null, 3)))

      assertThat(result.sql).isEqualTo("SELECT 1 WHERE id IN (?, ?, ?)")
      assertThat(result.arguments).containsExactly(1, null, 3)
    }

    @Test
    fun `iterable is traversed once so sql and arguments stay aligned`() {
      var traversals = 0
      val singleUse = Iterable {
        traversals++
        check(traversals == 1) { "Iterable traversed more than once" }
        listOf("a", "b").iterator()
      }

      val result = NamedParameters.substitute("SELECT 1 WHERE name IN (:names)", mapOf("names" to singleUse))

      assertThat(result.sql).isEqualTo("SELECT 1 WHERE name IN (?, ?)")
      assertThat(result.arguments).containsExactly("a", "b")
    }
  }

  @Nested
  inner class RepeatedAndMultipleNames {
    @Test
    fun `repeated scalar name adds an argument at each occurrence`() {
      val result = NamedParameters.substitute(
        "SELECT 1 WHERE name = :term OR alias = :term",
        mapOf("term" to "Alice"),
      )

      assertThat(result.sql).isEqualTo("SELECT 1 WHERE name = ? OR alias = ?")
      assertThat(result.arguments).containsExactly("Alice", "Alice")
    }

    @Test
    fun `repeated iterable name expands at each occurrence`() {
      val result = NamedParameters.substitute(
        "SELECT 1 WHERE a IN (:ids) OR b IN (:ids)",
        mapOf("ids" to listOf(1, 2)),
      )

      assertThat(result.sql).isEqualTo("SELECT 1 WHERE a IN (?, ?) OR b IN (?, ?)")
      assertThat(result.arguments).containsExactly(1, 2, 1, 2)
    }

    @Test
    fun `arguments follow the order of appearance in the sql`() {
      val result = NamedParameters.substitute(
        "SELECT 1 WHERE status = :status AND id IN (:ids) AND name = :name",
        mapOf("name" to "Alice", "ids" to listOf(7, 8), "status" to "active"),
      )

      assertThat(result.sql).isEqualTo("SELECT 1 WHERE status = ? AND id IN (?, ?) AND name = ?")
      assertThat(result.arguments).containsExactly("active", 7, 8, "Alice")
    }

    @Test
    fun `unused names are ignored`() {
      val result = NamedParameters.substitute("SELECT 1 WHERE id = :id", mapOf("id" to 1, "unused" to "x"))

      assertThat(result.sql).isEqualTo("SELECT 1 WHERE id = ?")
      assertThat(result.arguments).containsExactly(1)
    }
  }

  @Nested
  inner class MissingNames {
    @Test
    fun `missing name throws IllegalStateException naming the parameter`() {
      val exception = assertThrows<IllegalStateException> {
        NamedParameters.substitute("SELECT 1 WHERE id = :userId", emptyMap())
      }

      assertThat(exception.message).isNotNull().contains("No value provided for parameter 'userId'")
    }

    @Test
    fun `missing name throws even when other names are present`() {
      val exception = assertThrows<IllegalStateException> {
        NamedParameters.substitute("SELECT 1 WHERE id = :id AND name = :name", mapOf("id" to 1))
      }

      assertThat(exception.message).isNotNull().contains("'name'")
    }
  }

  @Nested
  inner class PostgresSyntax {
    @Test
    fun `double colon cast is not a parameter`() {
      val result = NamedParameters.substitute("SELECT :id::text, now()::date", mapOf("id" to 5))

      assertThat(result.sql).isEqualTo("SELECT ?::text, now()::date")
      assertThat(result.arguments).containsExactly(5)
    }

    @Test
    fun `escaped colon is emitted as a literal colon and is not a parameter`() {
      val result = NamedParameters.substitute("SELECT '{}'::jsonb \\:literal, :id", mapOf("id" to 5))

      assertThat(result.sql).isEqualTo("SELECT '{}'::jsonb :literal, ?")
      assertThat(result.arguments).containsExactly(5)
    }
  }
}
