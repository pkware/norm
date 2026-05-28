package norm.generator

/**
 * A database catalog containing one or more schemas.
 *
 * @property comment Catalog-level comment. Empty when absent.
 * @property defaultSchema Name of the default schema (typically `"public"`).
 * @property name Catalog name. Empty when not applicable.
 * @property schemas Schemas within this catalog.
 */
public data class Catalog(
  val comment: String = "",
  val defaultSchema: String = "",
  val name: String = "",
  val schemas: List<Schema> = emptyList(),
) {

  /**
   * Finds a [Table] matching the given [Identifier] in the [Catalog].
   */
  internal fun resolveTable(table: Identifier): Table {
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
  internal fun findTable(tableName: String): Table? = schemas.asSequence()
    .flatMap(Schema::tables)
    .firstOrNull { it.rel.name == tableName }

  /**
   * Finds a [Column] by table and column name, or `null` if not found.
   *
   * When [tableName] is `null`, searches all tables and returns the first match.
   */
  internal fun findColumn(tableName: String?, columnName: String): Column? = schemas.asSequence()
    .flatMap(Schema::tables)
    .filter { tableName == null || it.rel.name == tableName }
    .flatMap(Table::columns)
    .firstOrNull { it.name == columnName }
}

/**
 * A database schema containing tables, enums, composite types, and domains.
 *
 * @property comment Schema-level comment. Empty when absent.
 * @property name Schema name (e.g. `"public"`).
 * @property tables Tables and views in this schema.
 * @property enums User-defined enum types in this schema.
 * @property compositeTypes User-defined composite types in this schema.
 * @property domains User-defined domain types in this schema.
 */
public data class Schema(
  val comment: String = "",
  val name: String = "",
  val tables: List<Table> = emptyList(),
  val enums: List<Enum> = emptyList(),
  val compositeTypes: List<CompositeType> = emptyList(),
  val domains: List<Domain> = emptyList(),
)

/**
 * A user-defined composite type.
 *
 * @property name Type name.
 * @property comment Type-level comment. Empty when absent.
 */
public data class CompositeType(val name: String = "", val comment: String = "")

/**
 * A user-defined domain type (`CREATE DOMAIN name AS base_type CHECK (...)`).
 * Domains are thin wrappers over a base type with optional constraints.
 * Norm generates a `@JvmInline` value class for each domain referenced by a query column.
 *
 * @property name Unqualified domain name as it appears in `pg_type.typname` (e.g. `"email"`).
 * @property baseType Postgres base type name (e.g. `"text"`, `"int4"`).
 * @property comment Comment set via `COMMENT ON DOMAIN`. Empty when absent.
 */
public data class Domain(val name: String = "", val baseType: String = "", val comment: String = "")

/**
 * A user-defined enum type.
 *
 * @property name Enum type name.
 * @property vals Enum values in declaration order.
 * @property comment Type-level comment. Empty when absent.
 */
public data class Enum(val name: String = "", val vals: List<String> = emptyList(), val comment: String = "")

/**
 * A database table or view.
 *
 * @property rel Qualified table identifier.
 * @property columns Columns belonging to this table.
 * @property comment Table-level comment. Empty when absent.
 * @property isView `true` for VIEWs and MATERIALIZED VIEWs.
 */
public data class Table(
  val rel: Identifier,
  val columns: List<Column> = emptyList(),
  val comment: String = "",
  val isView: Boolean = false,
)

/**
 * A three-part SQL identifier (catalog, schema, name).
 *
 * @property catalog Catalog component. Empty when not applicable.
 * @property schema Schema component. Empty when unqualified.
 * @property name Object name.
 */
public data class Identifier(val catalog: String = "", val schema: String = "", val name: String = "")

/**
 * A column in a table or query result set.
 *
 * @property name Column name.
 * @property notNull `true` when the column has a `NOT NULL` constraint.
 * @property isArray `true` when the column is an array type.
 * @property comment Column-level comment. Empty when absent.
 * @property length Column length (e.g. `VARCHAR(n)`). `0` when unspecified.
 * @property isNamedParam `true` when this column represents a named parameter.
 * @property isFuncCall `true` when this column originates from a function call.
 * @property scope Scope qualifier for dotted references (e.g. `foo` in `foo.id`).
 * @property table Identifier of the table this column belongs to. `null` for computed columns.
 * @property tableAlias Alias used for the table in the query. Empty when not aliased.
 * @property type Identifier of the column's data type.
 * @property isSqlcSlice Sqlc-specific: `true` for slice parameters.
 * @property embedTable Sqlc-specific: table to embed. `null` when not embedding.
 * @property originalName Original column name before any aliasing.
 * @property unsigned `true` for unsigned integer types.
 * @property arrayDims Number of array dimensions. `0` for non-array types.
 * @property isPrimaryKey `true` when this column is part of the primary key.
 * @property isAutoIncrement `true` when JDBC reports `IS_AUTOINCREMENT = "YES"`.
 * @property hasDefault `true` when the column has a server-side `DEFAULT` expression.
 * @property isGenerated `true` when JDBC reports `IS_GENERATEDCOLUMN = "YES"`.
 */
public data class Column(
  val name: String = "",
  val notNull: Boolean = false,
  val isArray: Boolean = false,
  val comment: String = "",
  val length: Int = 0,
  val isNamedParam: Boolean = false,
  val isFuncCall: Boolean = false,
  val scope: String = "",
  val table: Identifier? = null,
  val tableAlias: String = "",
  val type: Identifier,
  val isSqlcSlice: Boolean = false,
  val embedTable: Identifier? = null,
  val originalName: String = "",
  val unsigned: Boolean = false,
  val arrayDims: Int = 0,
  val isPrimaryKey: Boolean = false,
  val isAutoIncrement: Boolean = false,
  val hasDefault: Boolean = false,
  val isGenerated: Boolean = false,
) {

  internal val fullyQualifiedName: String
    get() {
      val tableName = table?.name?.let { "$it." }.orEmpty()
      return tableName + name
    }
}

/**
 * An analyzed SQL query with its result columns, parameters, and metadata.
 *
 * @property text The SQL text with `?` placeholders.
 * @property name Query name from the `-- name:` annotation.
 * @property cmd Command type (`:one`, `:many`, `:exec`, `:execrows`).
 * @property columns Result columns produced by this query.
 * @property params Positional parameters for this query.
 * @property comments Comments associated with this query.
 * @property filename Source file containing this query.
 * @property insertIntoTable Target table for INSERT queries. `null` for non-INSERT queries.
 * @property isSynthesizedInsert `true` when this query was synthesized by [CrudQuerySynthesizer].
 * @property namedParameters Maps each 1-based JDBC parameter position to the named parameter that
 *   produced it. Empty for queries using positional `?` parameters or synthesized CRUD queries.
 */
public data class Query(
  val text: String = "",
  val name: String = "",
  val cmd: String = "",
  val columns: List<Column> = emptyList(),
  val params: List<Parameter> = emptyList(),
  val comments: List<String> = emptyList(),
  val filename: String = "",
  val insertIntoTable: Identifier? = null,
  val isSynthesizedInsert: Boolean = false,
  val namedParameters: Map<Int, String> = emptyMap(),
)

/**
 * A positional query parameter.
 *
 * @property number 1-based parameter position.
 * @property column Column metadata describing this parameter's type and nullability.
 *   `null` when the parameter type could not be resolved.
 */
public data class Parameter(val number: Int = 0, val column: Column? = null)

/**
 * A file produced by the code generator.
 *
 * @property name File path relative to the output directory (includes package hierarchy).
 * @property contents UTF-8 Kotlin source code.
 */
public data class GeneratedFile(val name: String, val contents: String)
