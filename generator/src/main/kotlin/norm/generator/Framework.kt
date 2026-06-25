package norm.generator

/**
 * Framework-specific code generation options.
 */
public enum class Framework {
  /**
   * Generates Micronaut DI annotations and a `MicronautConnectionProvider` that participates in
   * Micronaut-managed transactions.
   *
   * See [Micronaut Documentation](https://micronaut-projects.github.io/micronaut-data/latest/guide/).
   */
  MICRONAUT_DATA,

  /**
   * Generates Spring DI annotations and a `SpringConnectionProvider` that participates in
   * Spring-managed transactions.
   *
   * See [Spring Documentation](https://docs.spring.io/spring-data/relational/reference/jdbc.html).
   */
  SPRING_DATA,

  /**
   * Generates Micronaut DI annotations and a `@Factory` that provides a
   * [norm.TransactionalConnectionProvider] from an injected `javax.sql.DataSource`.
   *
   * Unlike [MICRONAUT_DATA], this mode does **not** generate a `MicronautConnectionProvider` and
   * requires no `micronaut-data` dependency. Transactions are Norm-managed: the generated `Queries`
   * interface extends `norm.Transactable`, so callers can run `transaction { }` on the injected
   * `Queries` bean rather than using Micronaut's `@Transactional`.
   */
  MICRONAUT,
}
