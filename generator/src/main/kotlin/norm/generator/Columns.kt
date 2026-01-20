package norm.generator

import plugin.Column

internal val Column.fullyQualifiedName: String
  get() {
    val tableName = table?.name?.let { "$it." }.orEmpty()
    return tableName + name
  }

/**
 * Whether this column is a primary key.
 */
internal val Column.isPrimaryKey: Boolean
  // sqlc doesn't report primary key columns. For now, try to infer them using the name. See
  // https://github.com/sqlc-dev/sqlc/issues/3596
  get() = name == "id"
