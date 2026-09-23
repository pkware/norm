package norm.generator

/**
 * A view's resolved column nullability.
 *
 * @param flags one flag per user-visible column, `true` meaning nullable, index `i` for attnum `i + 1`. `null` when
 *   the relid is not a view or materialized view.
 * @param tainted `true` when [flags] depends on a guard placeholder or an unanalyzable node tree, directly or through
 *   another tainted entry. Such an answer depends on traversal order, so it is discarded after one top-level call.
 */
internal data class ViewNullabilityCacheEntry(val flags: List<Boolean>?, val tainted: Boolean)

/**
 * Returns [nullability] when it has exactly [expectedColumnCount] entries. Otherwise, including when [nullability] is
 * `null`, returns all columns nullable.
 */
internal fun alignedViewColumnNullability(nullability: List<Boolean>?, expectedColumnCount: Int): List<Boolean> =
  if (nullability != null && nullability.size == expectedColumnCount) {
    nullability
  } else {
    List(expectedColumnCount) { true }
  }

/**
 * Resolves view-column nullability for one top-level [ColumnNullabilityAnalyzer.resolveViewColumnNullability] call and
 * the views it references.
 *
 * Nested references re-enter through [ColumnNullabilityAnalyzer.isColumnNotNull], and the analysis code between two
 * frames returns plain flags. A frame's taint is therefore recorded in [frameTaint] and added to its caller's entry
 * when the frame finishes.
 *
 * @param permanentCache entries kept across top-level calls. Receives untainted entries only.
 * @param fetchNodeTree returns a relid's `_RETURN` rule text, or `null` when the relid is not a view or materialized
 *   view.
 * @param analyzeNodeTree returns one nullable flag per target-list entry of a relid's node tree, or `null` when the
 *   tree cannot be analyzed.
 * @param columnCountFor returns a relid's user-visible column count.
 */
internal class ViewColumnNullabilityResolver(
  private val permanentCache: MutableMap<Int, ViewNullabilityCacheEntry>,
  private val fetchNodeTree: (Int) -> String?,
  private val analyzeNodeTree: (relid: Int, nodeTree: String) -> List<Boolean>?,
  private val columnCountFor: (Int) -> Int,
) {
  /** Relids on the current resolution path. Its size is the recursion depth. */
  private val pathRelids = mutableSetOf<Int>()

  /** Every entry resolved in this traversal, tainted or not. */
  private val traversalCache = mutableMapOf<Int, ViewNullabilityCacheEntry>()

  /** Whether each frame on [pathRelids] is tainted so far, innermost last. */
  private val frameTaint = ArrayDeque<Boolean>()

  /** Resolves [relid] as documented on [ColumnNullabilityAnalyzer.resolveViewColumnNullability]. */
  internal fun resolve(relid: Int): List<Boolean>? {
    // Checked before the caches so a relid past the budget truncates even when an earlier top-level call cached it.
    if (pathRelids.size >= VIEW_NULLABILITY_RECURSION_DEPTH_BUDGET) {
      taintCurrentFrame()
      return List(columnCountFor(relid)) { true }
    }
    traversalCache[relid]?.let { return returnFromCache(it) }
    permanentCache[relid]?.let { return returnFromCache(it) }
    // `CREATE OR REPLACE VIEW` can build a view cycle, though PostgreSQL refuses to query one.
    if (relid in pathRelids) {
      taintCurrentFrame()
      return List(columnCountFor(relid)) { true }
    }
    pathRelids.add(relid)
    frameTaint.addLast(false)
    try {
      val nodeTree = fetchNodeTree(relid)
      if (nodeTree == null) {
        // No recursion happened, so this answer cannot be tainted.
        val entry = ViewNullabilityCacheEntry(flags = null, tainted = false)
        traversalCache[relid] = entry
        permanentCache[relid] = entry
        return null
      }
      val nullability = analyzeNodeTree(relid, nodeTree)
      val expectedColumnCount = columnCountFor(relid)
      if (nullability == null || nullability.size != expectedColumnCount) taintCurrentFrame()
      val flags = alignedViewColumnNullability(nullability, expectedColumnCount)
      val tainted = frameTaint.last()
      val entry = ViewNullabilityCacheEntry(flags, tainted)
      traversalCache[relid] = entry
      if (!tainted) permanentCache[relid] = entry
      return flags
    } finally {
      pathRelids.remove(relid)
      if (frameTaint.removeLast()) taintCurrentFrame()
    }
  }

  private fun returnFromCache(entry: ViewNullabilityCacheEntry): List<Boolean>? {
    // Reusing a tainted answer taints the reader.
    if (entry.tainted) taintCurrentFrame()
    return entry.flags
  }

  /** Marks the innermost frame tainted. The outermost frame's own taint has no caller to mark. */
  private fun taintCurrentFrame() {
    if (frameTaint.isNotEmpty()) frameTaint[frameTaint.lastIndex] = true
  }
}
