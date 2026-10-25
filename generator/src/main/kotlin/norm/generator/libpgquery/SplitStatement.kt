package norm.generator.libpgquery

/**
 * One statement from [LibPgQuery.split].
 *
 * @property start `String` index of the first character, which includes any whitespace after the previous `;`.
 * @property end `String` index after the last character, which excludes the terminating `;`.
 */
internal data class SplitStatement(val start: Int, val end: Int)
