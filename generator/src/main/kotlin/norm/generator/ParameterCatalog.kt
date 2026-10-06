package norm.generator

import java.sql.Connection
import java.sql.Array as SqlArray

/**
 * Reads the `pg_catalog` facts that [ParameterNullabilityClassifier] and [FunctionArgumentNamer] need.
 */
internal interface ParameterCatalog {

  /** Returns the schema and name of the relation with the given `pg_class` OID, `null` when it has no row. */
  fun relation(relid: Int): RelationName?

  /** Returns the attribute with the given relation OID and number, `null` when there is none or it is dropped. */
  fun attribute(relid: Int, attno: Int): Attribute?

  /**
   * Returns [typeOid] followed by each type in its `typbasetype` chain, outermost first. Empty when [typeOid] has no
   * row.
   */
  fun typeChain(typeOid: Int): List<ChainedType>

  /** Returns the function with the given `pg_proc` OID, `null` when it has no row. */
  fun function(functionOid: Int): FunctionSignature?
}

/**
 * Names a relation by its schema and its name.
 *
 * @property schema the `pg_namespace.nspname` of the schema that holds the relation.
 * @property name the `pg_class.relname` of the relation.
 */
internal data class RelationName(val schema: String, val name: String)

/**
 * Describes a column as `pg_attribute` declares it.
 *
 * @property name the `pg_attribute.attname`.
 * @property typeOid the `pg_attribute.atttypid`.
 */
internal data class Attribute(val name: String, val typeOid: Int)

/**
 * Describes one type of a `typbasetype` chain.
 *
 * @property oid the `pg_type.oid`.
 * @property notNull the `pg_type.typnotnull`, `true` only for a domain declared `NOT NULL`. A domain over such a
 *   domain has `false` itself.
 */
internal data class ChainedType(val oid: Int, val notNull: Boolean)

/**
 * Describes a function as `pg_proc` declares it.
 *
 * @property schema the schema that declares the function.
 * @property name the function name, as `pg_proc.proname` spells it.
 * @property inputArgumentNames the names of the input arguments in call order, `""` for an unnamed one. `OUT` and
 *   `TABLE` arguments are left out.
 */
internal data class FunctionSignature(val schema: String, val name: String, val inputArgumentNames: List<String>) {

  /** Whether the parser calls this function on the pattern of `LIKE ... ESCAPE` and `SIMILAR TO`. */
  val isPatternEscape: Boolean
    get() = schema == "pg_catalog" && name in PATTERN_ESCAPE_FUNCTIONS

  private companion object {
    val PATTERN_ESCAPE_FUNCTIONS = setOf("like_escape", "similar_to_escape")
  }
}

/**
 * Reads a [ParameterCatalog] over JDBC and remembers each answer for the life of the instance.
 *
 * @param connection the connection to a database with the schema applied.
 */
internal class JdbcParameterCatalog(private val connection: Connection) : ParameterCatalog {

  private val relations = memoized(::readRelation)
  private val attributes = memoized(::readAttribute)
  private val typeChains = memoized(::readTypeChain)
  private val functions = memoized(::readFunction)

  override fun relation(relid: Int): RelationName? = relations(relid)

  override fun attribute(relid: Int, attno: Int): Attribute? = attributes(relid to attno)

  override fun typeChain(typeOid: Int): List<ChainedType> = typeChains(typeOid)

  override fun function(functionOid: Int): FunctionSignature? = functions(functionOid)

  private fun readRelation(relid: Int): RelationName? = connection.createStatement().use { statement ->
    statement.executeQuery(
      "SELECT n.nspname, c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE c.oid = $relid",
    ).use { resultSet ->
      if (resultSet.next()) RelationName(resultSet.getString(1), resultSet.getString(2)) else null
    }
  }

  private fun readAttribute(key: Pair<Int, Int>): Attribute? = connection.createStatement().use { statement ->
    statement.executeQuery(
      "SELECT attname, atttypid::integer FROM pg_attribute " +
        "WHERE attrelid = ${key.first} AND attnum = ${key.second} AND NOT attisdropped",
    ).use { resultSet -> if (resultSet.next()) Attribute(resultSet.getString(1), resultSet.getInt(2)) else null }
  }

  private fun readTypeChain(typeOid: Int): List<ChainedType> = connection.createStatement().use { statement ->
    statement.executeQuery(
      """
      WITH RECURSIVE chain(oid, base, not_null, depth) AS (
        SELECT oid, typbasetype, typnotnull, 0 FROM pg_type WHERE oid = $typeOid
        UNION ALL
        SELECT t.oid, t.typbasetype, t.typnotnull, c.depth + 1 FROM pg_type t JOIN chain c ON t.oid = c.base
      )
      SELECT oid::integer, not_null FROM chain ORDER BY depth
      """.trimIndent(),
    ).use { resultSet ->
      buildList { while (resultSet.next()) add(ChainedType(resultSet.getInt(1), resultSet.getBoolean(2))) }
    }
  }

  private fun readFunction(functionOid: Int): FunctionSignature? = connection.createStatement().use { statement ->
    statement.executeQuery(
      "SELECT n.nspname, p.proname, p.proargnames, p.proargmodes::text[] FROM pg_proc p " +
        "JOIN pg_namespace n ON n.oid = p.pronamespace WHERE p.oid = $functionOid",
    ).use { resultSet ->
      if (resultSet.next()) {
        val names = readTextArray(resultSet.getArray(3))
        val modes = resultSet.getArray(4)?.let(::readTextArray)
        val inputIndexes = (0 until (modes?.size ?: names.size)).filter { modes == null || modes[it] in INPUT_MODES }
        FunctionSignature(
          schema = resultSet.getString(1),
          name = resultSet.getString(2),
          inputArgumentNames = inputIndexes.map { names.getOrNull(it).orEmpty() },
        )
      } else {
        null
      }
    }
  }

  private fun <K, V> memoized(read: (K) -> V): (K) -> V {
    val values = mutableMapOf<K, V>()
    return { key -> if (values.containsKey(key)) values.getValue(key) else read(key).also { values[key] = it } }
  }

  private companion object {
    /** Holds the `pg_proc.proargmodes` values of arguments the caller passes: `IN`, `INOUT`, and `VARIADIC`. */
    val INPUT_MODES = setOf("i", "b", "v")
  }
}

/** Returns the elements of a `text[]` value, none when it is SQL `NULL`. */
private fun readTextArray(array: SqlArray?): List<String> {
  @Suppress("UNCHECKED_CAST")
  return (array?.array as Array<String>?)?.toList().orEmpty()
}
