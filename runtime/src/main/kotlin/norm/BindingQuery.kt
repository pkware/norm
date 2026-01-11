package norm

import norm.vendor.org.springframework.jdbc.core.namedparam.NamedParameterUtils
import org.intellij.lang.annotations.Language
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types

/**
 * A [Query] that binds arguments to the SQL.
 *
 * @param sql to execute.
 * @param rowReader Expression to extract an [RowType] from the [ResultSet].
 * @param normDriver The underlying driver to use to execute the query.
 * @param RowType Type to return.
 */
internal class BindingQuery<RowType>(
  @Language("PostgreSQL") sql: String,
  private val rowReader: ResultSet.() -> RowType,
  private val normDriver: NormDriver,
) : Query<RowType> {
  private val sqlBuilder = StringBuilder(sql)
  private val positionalArguments = mutableListOf<Any?>()
  private val namedArguments = mutableMapOf<String, Any?>()

  override fun single(): RowType = performQuery(normDriver::queryOne)

  override fun append(sql: String): Query<RowType> {
    sqlBuilder.append(sql)
    return this
  }

  override fun bind(name: String, value: Any?): Query<RowType> {
    check(positionalArguments.isEmpty()) { "Cannot mix positional and named arguments." }
    namedArguments[name] = value
    return this
  }

  override fun bind(value: Any?): Query<RowType> {
    check(namedArguments.isEmpty()) { "Cannot mix positional and named arguments." }
    positionalArguments.add(value)
    return this
  }

  override fun stream() = many().stream()

  override fun list() = many().list()

  override fun distinct() = many().distinct()

  override fun <TCollection : MutableCollection<RowType>> collection(factory: () -> TCollection): TCollection =
    many().collection(factory)

  override fun firstOrNull() = many().firstOrNull()

  private fun many(): Many<RowType> = performQuery(normDriver::queryMany)

  private fun <T> performQuery(
    driverMethod: (
      sql: String,
      rowReader: (ResultSet) -> RowType,
      queryBinder: (PreparedStatement.() -> Unit)?,
    ) -> T,
  ): T {
    val sql = sqlBuilder.toString()
    val jdbcSql: String
    val arguments: List<Any?>

    if (positionalArguments.isNotEmpty()) {
      // JDBC knows how to bind queries with positional parameters, so we don't need to do anything to transform the SQL
      jdbcSql = sql
      arguments = positionalArguments
    } else {
      val parsedSql = NamedParameterUtils.parseSqlStatement(sql)
      jdbcSql = NamedParameterUtils.substituteNamedParameters(parsedSql, namedArguments)
      arguments = parsedSql.parameterNames.map { name ->
        namedArguments.getOrElse(name) {
          check(namedArguments.containsKey(name)) { "No value provided for parameter '$name'" }
          null
        }
      }
    }

    return driverMethod(jdbcSql, rowReader) {
      arguments.forEachIndexed { index, value ->
        if (value != null) {
          setObject(index + 1, value)
        } else {
          setNull(index + 1, Types.NULL)
        }
      }
    }
  }

  // We do not include any arguments in the toString as they may be sensitive. The query itself typically isn't.
  override fun toString(): String = "BindingQuery=$sqlBuilder"
}
