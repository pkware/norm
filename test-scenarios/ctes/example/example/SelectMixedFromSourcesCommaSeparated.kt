package example

import kotlin.Int
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * ```sql
 * WITH parent_label2 AS (
 *   SELECT id, UPPER(name) AS name_upper FROM parent
 * )
 * SELECT
 *   parent_label2.name_upper AS parent_name_upper,
 *   child.name AS child_name,
 *   child_summary.name AS summary_name,
 *   derived.constant_label,
 *   generated.generated_number
 * FROM parent_label2, child, child_summary, (SELECT 'literal'::TEXT AS constant_label) derived,
 *   generate_series(1, 3) AS generated(generated_number)
 * WHERE child.parent_id = parent_label2.id AND child_summary.id = child.id
 * ```
 *
 * @property parent_name_upper (`UPPER(name)`)
 * @property child_name (`child.name`)
 * @property summary_name (`child_summary.name`)
 */
@JvmRecord
public data class SelectMixedFromSourcesCommaSeparated(
  public val parent_name_upper: String,
  public val child_name: String,
  public val summary_name: String,
  public val constant_label: String,
  public val generated_number: Int?,
)
