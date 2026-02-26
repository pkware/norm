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
}
