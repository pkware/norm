# NORM
NORM is Not an ORM. NORM is a SQL-first code generator for Postgres and Kotlin.

NORM generates Kotlin code from your SQL DDL and DML. This lets you write highly performant code in a great data
modification language, while getting fully type-safe and compile-time checked mapping code on the JVM.

NORM also provides a thin runtime, optimized for the Postgres JDBC driver, to make working with JDBC less error prone.

NORM strives to:
1. Be correct
2. Be performant
3. Give you the APIs needed to meet typical requirements
4. Be debuggable & readable

NORM does not:
- Have an entity model
- Integrate or compete with JPA
- Facilitate serialization (JSON, protobuf, etc)
- Support JVM-first database modeling

## Gradle
See the [Gradle plugin README](gradle-plugin/README.md) for setup details.

## TODO
### Before release
- [ ] Gradle plugin: connect to a real DB
- [ ] Dynamic SQL, especially around adding filters to existing projections.
- [ ] Support the timestamp type
- [ ] Pass detekt
- [ ] Add a getting started section to the README
- [ ] Delete old generated files when no longer needed

### After release
- [ ] Enum adapter. Map enums to String and back by default.
- [ ] List/array/stream/iterable adapter. If given one of these, just convert it to an array.
- [ ] Value class adapter (inline classes)
- [ ] Different behavior for read & write transactions
- [ ] Use save-points for nested transactions—the new code has a model for this already.
- [ ] ScopedValue instead of ThreadLocal? Requires Java 25+
- [ ] Populate KDoc using Postgres COMMENTs.
- [ ] Support batching from Stream, not just Iterable
- [ ] Make it easy to enforce named arguments for query methods. These are easy to change in SQL and have calling code compile incorrectly. Detekt, Intellij, etc.
