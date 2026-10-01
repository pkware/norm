package norm.generator

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

class TypeMappingTest {

  @Test
  fun `type variant survives Java serialization`() {
    val original = TypeMapping.ByType("jsonb", "com.example.JsonData", "com.example.JsonDataAdapter")

    assertThat(roundTrip(original)).isEqualTo(original)
  }

  @Test
  fun `column variant survives Java serialization`() {
    val original =
      TypeMapping.ByColumn("users", "metadata", "com.example.Metadata", "com.example.MetadataAdapter")

    assertThat(roundTrip(original)).isEqualTo(original)
  }

  private fun roundTrip(mapping: TypeMapping): Any? {
    val bytes = ByteArrayOutputStream().use { buffer ->
      ObjectOutputStream(buffer).use { it.writeObject(mapping) }
      buffer.toByteArray()
    }
    return ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() }
  }
}
