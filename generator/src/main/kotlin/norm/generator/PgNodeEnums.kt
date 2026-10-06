package norm.generator

/** Holds the `CmdType` values of `nodes/nodes.h`. */
internal object CommandType {
  const val UPDATE = 2
  const val INSERT = 3
  const val DELETE = 4
  const val MERGE = 5
}

/** Holds the `ParamKind` values of `nodes/primnodes.h`. */
internal object ParamKind {
  const val EXTERNAL = 0
}

private val CAST_FUNCTION_FORMATS = 1..2

/** Whether this is a `FUNCEXPR` with `CoercionForm` `COERCE_EXPLICIT_CAST` or `COERCE_IMPLICIT_CAST`. */
internal fun PgNodeValue.Node.isCast(): Boolean = name == "FUNCEXPR" && int("funcformat") in CAST_FUNCTION_FORMATS
