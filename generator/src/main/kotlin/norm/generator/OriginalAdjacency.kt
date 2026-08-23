package norm.generator

/**
 * "The characters at [leftIndex] and `leftIndex + 1` were adjacent in the ORIGINAL SQL text" — the
 * fact every multi-character lexical decision in the lexer that spans two adjacent characters (a
 * `--` line-comment or `/* */` block-comment opener, a `$`-prefixed dollar-quote's identifier
 * lookback, a standalone-`E` escape-string marker's lookback, a `''`/`""` doubled-quote escape, and
 * [matchTrailingAliasSegment]'s own bare-identifier run stopping before a `$` that should instead
 * open a fresh dollar-quoted string) must be gated on, whenever the text being scanned might be
 * [stripCommentsAndWhitespace]'s STRIPPED output rather than raw SQL.
 *
 * This exists because deleting a separator PostgreSQL itself lexed on can manufacture a token that
 * was never in the query: `1 - -1` (two separate `-` tokens, genuinely separated by a space) strips
 * to `1--1`, which [skipLexicalToken] would otherwise read as a `--` line comment that was never
 * there. [StrippedText] is the sole [OriginalAdjacency] implementation with real gaps to report —
 * see its KDoc — while [ALL_ADJACENT] is what every RAW-text caller passes (implicitly, via the
 * default parameter on [skipLexicalToken]/[findMatchingCloseParenthesis]): for text that was never
 * stripped, every neighbouring pair of characters genuinely IS adjacent, so the gate is always
 * satisfied and raw-text callers see no behavior change at all.
 */
internal fun interface OriginalAdjacency {
  fun wereAdjacent(leftIndex: Int): Boolean
}

/** The correct [OriginalAdjacency] for raw, never-stripped SQL text — see its KDoc. */
internal val ALL_ADJACENT: OriginalAdjacency = OriginalAdjacency { true }
