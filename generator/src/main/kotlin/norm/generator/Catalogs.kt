package norm.generator

import plugin.Catalog
import plugin.Column
import plugin.Identifier
import plugin.Schema
import plugin.Table

/**
 * Finds a [Table] matching the given [Identifier] in the [Catalog].
 */
internal fun Catalog.resolveTable(table: Identifier): Table {
  val candidateTables = schemas.asSequence()
    .filter { table.schema.isEmpty() || it.name == table.schema }
    .flatMap(Schema::tables)
    .filter { it.rel == table }
    .toList()

  check(candidateTables.size == 1) {
    if (candidateTables.isEmpty()) {
      "No catalog table found matching $table"
    } else {
      "Found multiple catalog tables matching $table: ${candidateTables.map { it.rel }}"
    }
  }
  return candidateTables.first()
}

/**
 * Finds a [Table] by its unqualified name, or `null` if not found.
 */
internal fun Catalog.findTable(tableName: String): Table? = schemas.asSequence()
  .flatMap(Schema::tables)
  .firstOrNull { it.rel?.name == tableName }

/**
 * Finds a [Column] by table and column name, or `null` if not found.
 *
 * When [tableName] is `null`, searches all tables and returns the first match.
 */
internal fun Catalog.findColumn(tableName: String?, columnName: String): Column? = schemas.asSequence()
  .flatMap(Schema::tables)
  .filter { tableName == null || it.rel?.name == tableName }
  .flatMap(Table::columns)
  .firstOrNull { it.name == columnName }
