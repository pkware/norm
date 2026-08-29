package example

import java.util.UUID
import kotlin.Int
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * ```sql
 * WITH RECURSIVE parent_chain AS (
 *   SELECT id, name, 0 AS depth FROM parent
 *   UNION ALL
 *   SELECT p.id, p.name, parent_chain.depth + 1
 *   FROM parent p
 *   JOIN parent_chain ON p.id = parent_chain.id AND parent_chain.depth < 3
 * )
 * SELECT id, name, depth FROM parent_chain
 * ```
 */
@JvmRecord
public data class CountChildGenerationsRecursive(
  public val id: UUID,
  public val name: String,
  public val depth: Int,
)
