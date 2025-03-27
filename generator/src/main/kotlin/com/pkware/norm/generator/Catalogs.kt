package com.pkware.norm.generator

import plugin.Catalog
import plugin.Identifier
import plugin.Schema
import plugin.Table

/**
 * Finds a [Table] matching the given [Identifier] in the [Catalog].
 */
fun Catalog.resolveTable(table: Identifier): Table {
  val candidateTables = schemas.asSequence()
    .filter { table.schema.isEmpty() || it.name == table.schema }
    .flatMap(Schema::tables)
    .filter { it.rel == table }
    .toList()

  check(candidateTables.size == 1) { "Found multiple catalog tables matching $table" }
  return candidateTables.first()
}
