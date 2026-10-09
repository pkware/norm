package norm.generator

/**
 * Represents `pg_node_tree` text as nodes with named fields, lists, and atoms.
 *
 * It keeps every field of every node, so a reader can follow a statement's structure, such as its `JOIN` conditions
 * and `MERGE` actions.
 */
internal sealed interface PgNodeValue {

  /** Holds a token such as `true`, `42`, a name with its backslash escapes removed, or `<>` for an empty value. */
  data class Atom(val text: String) : PgNodeValue

  /** Holds the items of a `(...)` list. */
  data class Sequence(val items: List<PgNodeValue>) : PgNodeValue

  /**
   * Represents a `{NAME :field value ...}` node.
   *
   * @property fields the values after each field label, in order. A field written `<>` has one [Atom] value.
   */
  data class Node(val name: String, val fields: Map<String, List<PgNodeValue>>) : PgNodeValue {

    /** Returns the single node that is the value of [field], or `null`. */
    fun node(field: String): Node? = fields[field]?.singleOrNull() as? Node

    /** Returns the nodes of the list in [field], or none when [field] holds anything but a list. */
    fun nodes(field: String): List<Node> =
      (fields[field]?.singleOrNull() as? Sequence)?.items?.filterIsInstance<Node>().orEmpty()

    /**
     * Returns the lists nested in the list in [field], such as the rows of `values_lists`, or none when [field]
     * holds anything but a list of lists. An item that is not a node is `null`, so positions stay aligned.
     */
    fun nodeLists(field: String): List<List<Node?>> =
      (fields[field]?.singleOrNull() as? Sequence)?.items?.filterIsInstance<Sequence>()
        ?.map { row -> row.items.map { it as? Node } }
        .orEmpty()

    /** Returns the text of the single atom that is the value of [field], or `null`. */
    fun atom(field: String): String? = (fields[field]?.singleOrNull() as? Atom)?.text

    /**
     * Returns the strings of the list in [field], such as the `colnames` of an alias, without their surrounding
     * quotes, or none when [field] holds anything but a list.
     */
    fun strings(field: String): List<String> =
      (fields[field]?.singleOrNull() as? Sequence)?.items?.filterIsInstance<Atom>()
        ?.map { it.text.removeSurrounding("\"") }
        .orEmpty()

    /** Returns the integer value of [field], or `null`. */
    fun int(field: String): Int? = atom(field)?.toIntOrNull()

    /** Returns the boolean value of [field], or `null`. */
    fun boolean(field: String): Boolean? = when (atom(field)) {
      "true" -> true
      "false" -> false
      else -> null
    }
  }
}

/**
 * Parses `pg_node_tree` text.
 *
 * A backslash escapes the next character, so an escaped brace, parenthesis, or space is part of an atom (see
 * [PgNodeTreeScanner]'s note on escaping). A token that starts with `:` is a field label unless it is the first value
 * after a label.
 *
 * @throws IllegalArgumentException if [text] is not a well-formed node tree.
 */
internal fun parsePgNodeValue(text: String): PgNodeValue = PgNodeValueReader(text).readAll()

private class PgNodeValueReader(private val text: String) {
  private var index = 0

  fun readAll(): PgNodeValue {
    val value = readValue()
    skipWhitespace()
    require(index == text.length) { "Unparsed trailing node tree text at $index" }
    return value
  }

  private fun readValue(): PgNodeValue {
    skipWhitespace()
    require(index < text.length) { "Unexpected end of node tree" }
    return when (text[index]) {
      '{' -> readNode()
      '(' -> readSequence()
      else -> PgNodeValue.Atom(readToken())
    }
  }

  private fun readNode(): PgNodeValue.Node {
    index++
    val name = readToken()
    val fields = linkedMapOf<String, List<PgNodeValue>>()
    while (true) {
      skipWhitespace()
      require(index < text.length) { "Unterminated node $name" }
      if (text[index] == '}') break
      val label = readToken()
      require(label.startsWith(":")) { "Expected a field label in $name but found '$label'" }
      val values = mutableListOf<PgNodeValue>()
      while (true) {
        skipWhitespace()
        require(index < text.length) { "Unterminated node $name" }
        if (text[index] == '}' || (values.isNotEmpty() && startsLabel())) break
        values += readValue()
      }
      fields[label.removePrefix(":")] = values
    }
    index++
    return PgNodeValue.Node(name, fields)
  }

  private fun readSequence(): PgNodeValue.Sequence {
    index++
    val items = mutableListOf<PgNodeValue>()
    while (true) {
      skipWhitespace()
      require(index < text.length) { "Unterminated list" }
      if (text[index] == ')') break
      items += readValue()
    }
    index++
    return PgNodeValue.Sequence(items)
  }

  private fun startsLabel(): Boolean = text[index] == ':'

  private fun readToken(): String {
    val token = StringBuilder()
    while (index < text.length) {
      val character = text[index]
      if (character == '\\' && index + 1 < text.length) {
        token.append(text[index + 1])
        index += 2
        continue
      }
      if (character.isWhitespace() || character in "{}()") break
      token.append(character)
      index++
    }
    require(token.isNotEmpty()) { "Expected a token at $index" }
    return token.toString()
  }

  private fun skipWhitespace() {
    while (index < text.length && text[index].isWhitespace()) index++
  }
}
