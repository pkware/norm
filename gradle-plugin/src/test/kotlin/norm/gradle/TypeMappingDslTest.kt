package norm.gradle

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import norm.generator.TypeMapping
import org.junit.jupiter.api.Test

class TypeMappingDslTest {

  @Test
  fun `type mapping produces a type variant`() {
    val dsl = TypeMappingDsl()
    dsl.type("mood") mapTo "com.example.CustomMood" using "com.example.CustomMoodAdapter"

    assertThat(dsl.build().single()).isEqualTo(
      TypeMapping.ByType("mood", "com.example.CustomMood", "com.example.CustomMoodAdapter"),
    )
  }

  @Test
  fun `column mapping produces a column variant`() {
    val dsl = TypeMappingDsl()
    dsl.column("users", "metadata") mapTo "com.example.Metadata" using "com.example.MetadataAdapter"

    assertThat(dsl.build().single()).isEqualTo(
      TypeMapping.ByColumn("users", "metadata", "com.example.Metadata", "com.example.MetadataAdapter"),
    )
  }

  @Test
  fun `multiple mappings accumulate in declaration order`() {
    val dsl = TypeMappingDsl()
    dsl.type("jsonb") mapTo "com.example.JsonData" using "com.example.JsonDataAdapter"
    dsl.type("mood") mapTo "com.example.CustomMood" using "com.example.CustomMoodAdapter"
    dsl.column("users", "metadata") mapTo "com.example.Metadata" using "com.example.MetadataAdapter"

    assertThat(dsl.build()).containsExactly(
      TypeMapping.ByType("jsonb", "com.example.JsonData", "com.example.JsonDataAdapter"),
      TypeMapping.ByType("mood", "com.example.CustomMood", "com.example.CustomMoodAdapter"),
      TypeMapping.ByColumn("users", "metadata", "com.example.Metadata", "com.example.MetadataAdapter"),
    )
  }
}
