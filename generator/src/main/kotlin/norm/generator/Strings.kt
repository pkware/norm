package norm.generator

/**
 * Converts the first letter of the string to uppercase.
 */
internal fun String.titleCase() = replaceFirstChar(Character::toTitleCase)

/**
 * Converts a snake_case string to camelCase.
 */
internal fun String.snakeToCamelCase(): String = replace(Regex("_(\\w)")) { it.groups[1]!!.value.uppercase() }
