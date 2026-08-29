package example

import java.util.UUID
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * ```sql
 * WITH parent_upper2 AS (
 *   SELECT id, UPPER(name) AS name_upper FROM parent
 * )
 * SELECT x.id AS parent_id, x.name_upper AS aliased_name_upper FROM parent_upper2 AS x
 * ```
 *
 * @property parent_id (`parent.id`)
 * @property aliased_name_upper (`UPPER(name)`)
 */
@JvmRecord
public data class SelectCteViaExplicitFromAliasAs(
  public val parent_id: UUID,
  public val aliased_name_upper: String,
)
