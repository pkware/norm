package norm.generator

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.MUTABLE_LIST
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.STAR
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.asTypeName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class TypeNameParserTest {

  @Nested
  inner class SimpleTypes {
    @Test
    fun `fully qualified class name`() {
      assertThat(parseTypeName("kotlin.String")).isEqualTo(STRING)
    }

    @Test
    fun `nullable simple type`() {
      assertThat(parseTypeName("kotlin.String?")).isEqualTo(STRING.copy(nullable = true))
    }

    @Test
    fun `custom class name`() {
      assertThat(parseTypeName("com.example.Foo")).isEqualTo(ClassName("com.example", "Foo"))
    }

    @Test
    fun `nullable custom class name`() {
      assertThat(parseTypeName("com.example.Foo?")).isEqualTo(ClassName("com.example", "Foo").copy(nullable = true))
    }

    @Test
    fun `star projection`() {
      assertThat(parseTypeName("*")).isEqualTo(STAR)
    }
  }

  @Nested
  inner class ParameterizedTypes {
    @Test
    fun `Map with simple type arguments`() {
      val expected = Map::class.asTypeName().parameterizedBy(STRING, INT)
      assertThat(parseTypeName("kotlin.collections.Map<kotlin.String, kotlin.Int>")).isEqualTo(expected)
    }

    @Test
    fun `Map with nullable value type`() {
      val expected = Map::class.asTypeName().parameterizedBy(STRING, ANY.copy(nullable = true))
      assertThat(parseTypeName("kotlin.collections.Map<kotlin.String, kotlin.Any?>")).isEqualTo(expected)
    }

    @Test
    fun `nullable Map`() {
      val expected = Map::class.asTypeName().parameterizedBy(STRING, ANY.copy(nullable = true)).copy(nullable = true)
      assertThat(parseTypeName("kotlin.collections.Map<kotlin.String, kotlin.Any?>?")).isEqualTo(expected)
    }

    @Test
    fun `List with simple type argument`() {
      val expected = List::class.asTypeName().parameterizedBy(STRING)
      assertThat(parseTypeName("kotlin.collections.List<kotlin.String>")).isEqualTo(expected)
    }

    @Test
    fun mutableList() {
      val expected = MUTABLE_LIST.parameterizedBy(INT)
      assertThat(parseTypeName("kotlin.collections.MutableList<kotlin.Int>")).isEqualTo(expected)
    }

    @Test
    fun `custom generic type`() {
      val outerClass = ClassName("com.example", "Wrapper")
      val innerClass = ClassName("com.example", "Payload")
      val expected = outerClass.parameterizedBy(innerClass)
      assertThat(parseTypeName("com.example.Wrapper<com.example.Payload>")).isEqualTo(expected)
    }
  }

  @Nested
  inner class NestedParameterizedTypes {
    @Test
    fun `List of Maps`() {
      val expected = List::class.asTypeName().parameterizedBy(
        Map::class.asTypeName().parameterizedBy(STRING, ANY.copy(nullable = true)),
      )
      assertThat(parseTypeName("kotlin.collections.List<kotlin.collections.Map<kotlin.String, kotlin.Any?>>"))
        .isEqualTo(expected)
    }

    @Test
    fun `Map of String to List of Int`() {
      val expected = Map::class.asTypeName().parameterizedBy(
        STRING,
        List::class.asTypeName().parameterizedBy(INT),
      )
      assertThat(parseTypeName("kotlin.collections.Map<kotlin.String, kotlin.collections.List<kotlin.Int>>"))
        .isEqualTo(expected)
    }
  }
}
