package norm.generator

/** Builds an [SqlStatement] with sensible defaults for tests that only care about a subset of [Query]'s fields. */
internal fun createStatement(
  sql: String,
  cmd: String = ":one",
  name: String = "TestQuery",
  columns: List<Column> = emptyList(),
  params: List<Parameter> = emptyList(),
  catalog: Catalog = Catalog(),
  comments: List<String> = emptyList(),
  isSynthesizedInsert: Boolean = false,
  namedParameters: Map<Int, String> = emptyMap(),
): SqlStatement {
  val repository = TypeRepository("test", catalog)
  return SqlStatement(
    catalog,
    Query(
      text = sql,
      cmd = cmd,
      name = name,
      columns = columns,
      params = params,
      comments = comments,
      isSynthesizedInsert = isSynthesizedInsert,
      namedParameters = namedParameters,
    ),
    repository,
  )
}

/** Builds a [Column] with sensible defaults for [SqlStatement] and [TypeRepository] tests. */
internal fun column(
  name: String,
  type: String = "varchar",
  notNull: Boolean = true,
  isArray: Boolean = false,
  table: Identifier? = null,
  embedTable: Identifier? = null,
  originalName: String = "",
  comment: String = "",
) = Column(
  name = name,
  notNull = notNull,
  type = Identifier(name = type),
  isArray = isArray,
  table = table,
  embedTable = embedTable,
  originalName = originalName,
  comment = comment,
)
