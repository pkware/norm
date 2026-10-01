package norm.vendor.org.springframework.jdbc.core.namedparam

internal object NamedParameters {

  /**
   * Replaces each `:name` in [sql] with `?` and collects the values to bind, one per `?`.
   *
   * @param arguments values by parameter name. Unused names are ignored.
   * @return the JDBC SQL and one value per `?`, in order.
   * @throws IllegalStateException if [sql] references a name that is not a key of [arguments].
   */
  @Throws(IllegalStateException::class)
  fun substitute(sql: String, arguments: Map<String, Any?>): SubstitutedSql {
    val parsedSql = NamedParameterUtils.parseSqlStatement(sql)
    parsedSql.parameterNames.forEach { name ->
      check(arguments.containsKey(name)) { "No value provided for parameter '$name'" }
    }
    val boundValues = mutableListOf<Any?>()
    val jdbcSql = NamedParameterUtils.substituteNamedParameters(parsedSql, arguments, boundValues)
    return SubstitutedSql(jdbcSql, boundValues)
  }
}

/**
 * @param sql the SQL with `?` placeholders.
 * @param arguments the value for each `?` in [sql], in order. `null` binds SQL `NULL`.
 */
internal class SubstitutedSql(val sql: String, val arguments: List<Any?>)
