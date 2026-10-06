package norm.generator

import java.util.Collections
import java.util.IdentityHashMap

/**
 * Names the parameters a statement passes as function arguments, from the call nodes of its query trees.
 *
 * A call is a `FUNCEXPR` written as a call or as SQL syntax, an `AGGREF`, or a `WINDOWFUNC`. The function OID of the
 * node selects the exact overload, whatever the SQL text spells. The calls are taken in the order of their first
 * parameter, an enclosing call before the calls it encloses. Each call numbers itself among the calls of the same
 * function name that came before it, so the second call of `digest` has the prefix `digest2`.
 *
 * An argument that holds exactly one parameter names it. The name is the formal argument name, or else
 * `<prefix>_param<position>` with the position counted from one. A parameter keeps the name it first received. A call
 * whose arguments hold no parameter is skipped. The `ORDER BY` items of a plain aggregate are not arguments, but those
 * after `WITHIN GROUP` of an ordered-set aggregate are.
 *
 * The parser wraps the pattern of `LIKE ... ESCAPE` and `SIMILAR TO` in a call of `like_escape` or
 * `similar_to_escape`, placed directly as an operand of an operator. Such a call is not named. The same call written
 * anywhere else is named like any other.
 *
 * A statement over 100 parameters has one query tree per chunk of parameters, and in each tree the
 * parameters outside the chunk are constants. The trees differ only at those leaves, so the calls of the trees pair up
 * by their position in a depth-first walk. The parameters of an argument are collected over all trees before the
 * argument is named.
 *
 * One instance serves one text. The numbering of repeated calls carries over from one statement of the text to the
 * next.
 *
 * @property signature looks up the function with the given `pg_proc` OID, `null` when it has no row.
 */
internal class FunctionArgumentNamer(private val signature: (functionOid: Int) -> FunctionSignature?) {

  /**
   * Holds the query tree of one chunk of parameters.
   *
   * @property parameterIndexes the 0-based index, among the parameters of the statement, of each parameter of the
   *   chunk. The tree numbers them from `$1` in this order.
   * @property root the parsed `prosqlbody` of the chunk.
   */
  class ChunkTree(val parameterIndexes: List<Int>, val root: PgNodeValue)

  /**
   * Holds an argument of a call.
   *
   * @property position the 0-based place of the argument among the input arguments of the function. A named
   *   argument carries the position it is declared at.
   * @property expression the argument's node.
   */
  private class Argument(val position: Int, val expression: PgNodeValue.Node)

  private class Call(
    val node: PgNodeValue.Node,
    val functionOid: Int,
    val arguments: List<Argument>,
    val isOperatorOperand: Boolean,
  )

  /**
   * Holds an argument with its parameters over all chunks.
   *
   * @property position see [Argument.position].
   * @property parameters the 1-based parameter numbers in the argument.
   */
  private data class MergedArgument(val position: Int, val parameters: Set<Int>)

  /**
   * Holds a call with its arguments over all chunks.
   *
   * @property position the place of the call in a depth-first walk.
   * @property isOperatorOperand whether the call is a direct operand of an operator.
   * @property nodes the node of the call in each chunk. A call with equal nodes to an earlier one is a copy that
   *   PostgreSQL made, as `BETWEEN SYMMETRIC` does of its first operand.
   * @property arguments the arguments in call order.
   */
  private class MergedCall(
    val position: Int,
    val functionOid: Int,
    val isOperatorOperand: Boolean,
    val nodes: List<PgNodeValue.Node>,
    val arguments: List<MergedArgument>,
  ) {
    val firstParameter: Int? = arguments.flatMap { it.parameters }.minOrNull()
  }

  private val callCounts = mutableMapOf<String, Int>()

  /**
   * Names the parameters of one statement.
   *
   * @param chunks the query trees of the statement, one per chunk, in parameter order.
   * @return the name of each named parameter, keyed by its 1-based number in the statement; empty when the trees
   *   disagree on the calls they hold.
   */
  fun name(chunks: List<ChunkTree>): Map<Int, String> {
    val callsByChunk = chunks.map { collectCalls(it.root) }
    val shape = callsByChunk.firstOrNull()?.map { it.functionOid to it.arguments.size } ?: return emptyMap()
    if (callsByChunk.any { calls -> calls.map { it.functionOid to it.arguments.size } != shape }) return emptyMap()

    val copies = mutableSetOf<List<PgNodeValue.Node>>()
    val calls = shape.indices
      .map { position -> merge(position, chunks, callsByChunk) }
      .filter { it.firstParameter != null && copies.add(it.nodes) }
      .sortedWith(compareBy({ it.firstParameter }, { it.position }))

    val names = mutableMapOf<Int, String>()
    for (call in calls) {
      val function =
        signature(call.functionOid)?.takeUnless { it.isPatternEscape && call.isOperatorOperand } ?: continue
      val callNumber = callCounts.merge(function.name, 1, Int::plus) ?: 1
      val prefix = if (callNumber == 1) function.name else "${function.name}$callNumber"
      for ((position, parameters) in call.arguments) {
        val parameter = parameters.singleOrNull() ?: continue
        names.getOrPut(parameter) {
          function.inputArgumentNames.getOrNull(position)?.takeIf { it.isNotEmpty() }
            ?: "${prefix}_param${position + 1}"
        }
      }
    }
    return names
  }

  private fun merge(position: Int, chunks: List<ChunkTree>, callsByChunk: List<List<Call>>): MergedCall {
    val calls = callsByChunk.map { it[position] }
    val arguments = calls.first().arguments.mapIndexed { index, argument ->
      val parameters = chunks.indices.flatMapTo(sortedSetOf()) { chunk ->
        parameterNumbers(calls[chunk].arguments[index].expression).map { chunks[chunk].parameterIndexes[it - 1] + 1 }
      }
      MergedArgument(argument.position, parameters)
    }
    val first = calls.first()
    return MergedCall(position, first.functionOid, first.isOperatorOperand, calls.map { it.node }, arguments)
  }

  private fun collectCalls(root: PgNodeValue): List<Call> {
    val nodes = root.nodesTopDown().toList()
    // Compares by identity. An equal copy of an operand elsewhere in the tree is not an operand.
    val operands = Collections.newSetFromMap(IdentityHashMap<PgNodeValue.Node, Boolean>())
    for (node in nodes) if (node.name == "OPEXPR") operands += node.nodes("args")
    return nodes.mapNotNull { callOf(it, it in operands) }
  }

  private fun callOf(node: PgNodeValue.Node, isOperatorOperand: Boolean): Call? = when (node.name) {
    "FUNCEXPR" -> if (node.isCast()) {
      null
    } else {
      call(node, node.int("funcid"), node.nodes("args"), isOperatorOperand)
    }

    "AGGREF" -> {
      val aggregated = node.nodes("args").filter { it.boolean("resjunk") != true }.mapNotNull { it.node("expr") }
      call(node, node.int("aggfnoid"), node.nodes("aggdirectargs") + aggregated, isOperatorOperand)
    }

    "WINDOWFUNC" -> call(node, node.int("winfnoid"), node.nodes("args"), isOperatorOperand)

    else -> null
  }

  private fun call(
    node: PgNodeValue.Node,
    functionOid: Int?,
    arguments: List<PgNodeValue.Node>,
    isOperatorOperand: Boolean,
  ): Call? = functionOid?.let {
    val positioned = arguments.mapIndexed { index, argument -> Argument(argumentPosition(argument, index), argument) }
    Call(node, it, positioned, isOperatorOperand)
  }

  private fun argumentPosition(argument: PgNodeValue.Node, index: Int): Int =
    if (argument.name == "NAMEDARGEXPR") argument.int("argnumber") ?: index else index

  private fun parameterNumbers(value: PgNodeValue): Set<Int> = value.nodesTopDown()
    .filter { it.name == "PARAM" && it.int("paramkind") == ParamKind.EXTERNAL }
    .mapNotNullTo(mutableSetOf()) { it.int("paramid") }
}
