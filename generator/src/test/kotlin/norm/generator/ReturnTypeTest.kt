package norm.generator

import com.google.common.truth.Truth.assertThat
import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.asTypeName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ReturnTypeTest {

  @Nested
  inner class MapperReturnType {

    @Test
    fun `non-null kotlinType produces non-null bounded T`() {
      val returnType = ReturnType(
        kotlinType = String::class.asTypeName(),
        builder = emptyList(),
      )

      assertThat(returnType.mapperReturnType).isEqualTo(
        TypeVariableName("T", ANY),
      )
    }

    @Test
    fun `nullable kotlinType produces nullable bounded T`() {
      val returnType = ReturnType(
        kotlinType = String::class.asTypeName().copy(nullable = true),
        builder = emptyList(),
      )

      assertThat(returnType.mapperReturnType).isEqualTo(
        TypeVariableName("T", ANY.copy(nullable = true)),
      )
    }

    @Test
    fun `null kotlinType produces non-null bounded T`() {
      // Edge case: when kotlinType is null, kotlinType?.isNullable evaluates to null
      // which becomes false in the boolean expression
      val returnType = ReturnType(
        kotlinType = null,
        builder = emptyList(),
      )

      assertThat(returnType.mapperReturnType).isEqualTo(
        TypeVariableName("T", ANY),
      )
    }
  }

  @Nested
  inner class IsComposedOfMultipleColumns {

    @Test
    fun `empty creationParameters returns false`() {
      val returnType = ReturnType(
        kotlinType = null,
        builder = emptyList(),
        creationParameters = emptyList(),
      )

      assertThat(returnType.isComposedOfMultipleColumns).isFalse()
    }

    @Test
    fun `single creationParameter returns false`() {
      val returnType = ReturnType(
        kotlinType = String::class.asTypeName(),
        builder = listOf(CodeBlock.of("getString(1)")),
        creationParameters = listOf(
          ParameterSpec.builder("name", String::class).build(),
        ),
      )

      assertThat(returnType.isComposedOfMultipleColumns).isFalse()
    }

    @Test
    fun `two creationParameters returns true`() {
      val returnType = ReturnType(
        kotlinType = String::class.asTypeName(),
        builder = listOf(CodeBlock.of("getString(1)"), CodeBlock.of("getString(2)")),
        creationParameters = listOf(
          ParameterSpec.builder("name", String::class).build(),
          ParameterSpec.builder("email", String::class).build(),
        ),
      )

      assertThat(returnType.isComposedOfMultipleColumns).isTrue()
    }

    @Test
    fun `many creationParameters returns true`() {
      val params = (1..5).map {
        ParameterSpec.builder("col$it", String::class).build()
      }
      val builders = (1..5).map {
        CodeBlock.of("getString($it)")
      }

      val returnType = ReturnType(
        kotlinType = String::class.asTypeName(),
        builder = builders,
        creationParameters = params,
      )

      assertThat(returnType.isComposedOfMultipleColumns).isTrue()
    }
  }
}
