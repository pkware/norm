package norm.generator

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.STAR
import com.squareup.kotlinpoet.TypeName

/**
 * Parses a fully-qualified Kotlin type string into a KotlinPoet [TypeName].
 *
 * Supports:
 * - Simple class names: `kotlin.String`, `com.example.Foo`
 * - Nullable types: `kotlin.String?`, `com.example.Foo?`
 * - Parameterized types: `kotlin.collections.Map<kotlin.String, kotlin.Any?>`
 * - Nested parameterized types: `kotlin.collections.List<kotlin.collections.Map<kotlin.String, kotlin.Int>>`
 * - Star projections: `*`
 *
 * All class names must be fully qualified (e.g., `kotlin.String` not `String`).
 */
internal fun parseTypeName(typeString: String): TypeName {
  val trimmed = typeString.trim()

  if (trimmed == "*") return STAR

  // A trailing `?` at the outermost level marks the whole type as nullable.
  // We detect this by checking whether the last character is `?` on the trimmed string.
  // Since we always parse one complete type expression at a time (callers split at commas
  // at depth 0 before recursing), the trailing `?` always belongs to this type, not a nested one.
  val nullable = trimmed.endsWith("?")
  val withoutNullable = if (nullable) trimmed.dropLast(1) else trimmed

  val angleIndex = withoutNullable.indexOf('<')
  if (angleIndex == -1) {
    return ClassName.bestGuess(withoutNullable).copy(nullable = nullable)
  }

  val outerClassName = withoutNullable.substring(0, angleIndex)
  // Strip the surrounding `< >` to get the raw argument list.
  val argString = withoutNullable.substring(angleIndex + 1, withoutNullable.length - 1)
  val typeArgs = splitTypeArguments(argString).map { parseTypeName(it) }

  return ClassName.bestGuess(outerClassName)
    .parameterizedBy(typeArgs)
    .copy(nullable = nullable)
}

/**
 * Splits a comma-separated list of type argument strings, respecting `<>` nesting depth.
 *
 * For example, `"kotlin.String, kotlin.collections.List<kotlin.Int>"` splits into
 * `["kotlin.String", "kotlin.collections.List<kotlin.Int>"]` — the comma inside `List<kotlin.Int>`
 * is ignored because it is at depth > 0.
 */
private fun splitTypeArguments(argString: String): List<String> {
  val arguments = mutableListOf<String>()
  var depth = 0
  var segmentStart = 0

  for (index in argString.indices) {
    when (argString[index]) {
      '<' -> depth++
      '>' -> depth--
      ',' -> if (depth == 0) {
        arguments.add(argString.substring(segmentStart, index).trim())
        segmentStart = index + 1
      }
    }
  }
  arguments.add(argString.substring(segmentStart).trim())
  return arguments
}
