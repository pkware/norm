package norm.generator

internal val Column.fullyQualifiedName: String
  get() {
    val tableName = table?.name?.let { "$it." }.orEmpty()
    return tableName + name
  }
