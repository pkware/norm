package norm

import java.sql.ResultSet
import java.util.Optional

public fun <RowType : Any> ResultSet.single(
  functionName: String,
  sql: String,
  mapper: RowMapper<RowType>,
): Optional<RowType> {
  if (!next()) return Optional.empty<RowType>()
  val result = Optional.of(mapper(this))
  check(!next()) { "Query $functionName ($sql) incorrectly produced multiple rows." }
  return result
}
