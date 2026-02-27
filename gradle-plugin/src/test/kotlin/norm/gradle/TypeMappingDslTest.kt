package norm.gradle

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test

class TypeMappingDslTest {

  @Test
  fun `type mapping produces correct TypeMapping`() {
    val dsl = TypeMappingDsl()
    dsl.type("mood") mapTo "com.example.CustomMood" using "com.example.CustomMoodAdapter"
    val mappings = dsl.build()

    assertThat(mappings).hasSize(1)
    val mapping = mappings.single()
    assertThat(mapping.postgresType).isEqualTo("mood")
    assertThat(mapping.table).isNull()
    assertThat(mapping.column).isNull()
    assertThat(mapping.kotlinType).isEqualTo("com.example.CustomMood")
    assertThat(mapping.adapterType).isEqualTo("com.example.CustomMoodAdapter")
    assertThat(mapping.isTypeLevel).isTrue()
    assertThat(mapping.isColumnLevel).isFalse()
  }

  @Test
  fun `column mapping produces correct TypeMapping`() {
    val dsl = TypeMappingDsl()
    dsl.column("users", "metadata") mapTo "com.example.Metadata" using "com.example.MetadataAdapter"
    val mappings = dsl.build()

    assertThat(mappings).hasSize(1)
    val mapping = mappings.single()
    assertThat(mapping.postgresType).isEqualTo("")
    assertThat(mapping.table).isEqualTo("users")
    assertThat(mapping.column).isEqualTo("metadata")
    assertThat(mapping.kotlinType).isEqualTo("com.example.Metadata")
    assertThat(mapping.adapterType).isEqualTo("com.example.MetadataAdapter")
    assertThat(mapping.isTypeLevel).isFalse()
    assertThat(mapping.isColumnLevel).isTrue()
  }

  @Test
  fun `multiple mappings accumulate correctly`() {
    val dsl = TypeMappingDsl()
    dsl.type("jsonb") mapTo "com.example.JsonData" using "com.example.JsonDataAdapter"
    dsl.type("mood") mapTo "com.example.CustomMood" using "com.example.CustomMoodAdapter"
    dsl.column("users", "metadata") mapTo "com.example.Metadata" using "com.example.MetadataAdapter"
    val mappings = dsl.build()

    assertThat(mappings).hasSize(3)
    assertThat(mappings.map { it.postgresType }).containsExactly("jsonb", "mood", "")
  }
}
