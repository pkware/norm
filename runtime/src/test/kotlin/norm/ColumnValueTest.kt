package norm

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNull
import org.junit.jupiter.api.Test

class ColumnValueTest {

  @Test
  fun `Set carries a non-null value`() {
    val columnValue: ColumnValue<String> = ColumnValue.Set("hello")

    assertThat((columnValue as ColumnValue.Set).value).isEqualTo("hello")
  }

  @Test
  fun `Set can carry an explicit null for a nullable column`() {
    val columnValue: ColumnValue<String?> = ColumnValue.Set(null)

    assertThat((columnValue as ColumnValue.Set).value).isNull()
  }

  @Test
  fun `Set with an explicit null is distinct from Default`() {
    val explicitNull: ColumnValue<String?> = ColumnValue.Set(null)
    val default: ColumnValue<String?> = ColumnValue.Default

    assertThat(explicitNull).isNotEqualTo(default)
  }

  @Test
  fun `two Set instances with equal values are equal`() {
    val first: ColumnValue<Int> = ColumnValue.Set(42)
    val second: ColumnValue<Int> = ColumnValue.Set(42)

    assertThat(first).isEqualTo(second)
  }

  @Test
  fun `Default is a singleton`() {
    val first: ColumnValue<Nothing> = ColumnValue.Default
    val second: ColumnValue<Nothing> = ColumnValue.Default

    assertThat(first).isEqualTo(second)
  }
}
