package norm.generator

/**
 * Converts the first letter of the string to uppercase.
 */
internal fun String.titleCase() = replaceFirstChar(Character::toTitleCase)

/**
 * Converts a snake_case string to camelCase.
 */
internal fun String.snakeToCamelCase(): String = replace(Regex("_(\\w)")) { it.groups[1]!!.value.uppercase() }

/**
 * Converts a string to UPPER_SNAKE_CASE.
 *
 * Handles snake_case (`very_happy` → `VERY_HAPPY`), camelCase (`inProgress` → `IN_PROGRESS`),
 * and mixed input. Non-alphanumeric characters are replaced with underscores, and consecutive
 * underscores are collapsed.
 */
internal fun String.toUpperSnakeCase(): String = buildString {
  for ((index, character) in this@toUpperSnakeCase.withIndex()) {
    if (character.isUpperCase() && index > 0) {
      val previous = this@toUpperSnakeCase[index - 1]
      // Insert underscore at camelCase boundary: lowercase/digit→uppercase or uppercase→lowercase with run
      if (previous.isLowerCase() ||
        previous.isDigit() ||
        (
          previous.isUpperCase() &&
            index + 1 < this@toUpperSnakeCase.length &&
            this@toUpperSnakeCase[index + 1].isLowerCase()
          )
      ) {
        append('_')
      }
    }
    if (character.isLetterOrDigit()) {
      append(character.uppercaseChar())
    } else {
      // Replace non-alphanumeric with underscore, avoiding consecutive underscores
      if (isNotEmpty() && last() != '_') {
        append('_')
      }
    }
  }
}
