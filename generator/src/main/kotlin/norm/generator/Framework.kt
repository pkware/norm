package norm.generator

/**
 * Framework-specific code generation options.
 */
public enum class Framework {
  /**
   * Classes should be generated for all database tables, without specifying framework-specific annotations.
   */
  ALL_TABLES,

  /**
   * Classes should be generated for all database tables, with Micronaut Data JDBC-specific annotations.
   *
   * See [Micronaut Documentation](https://micronaut-projects.github.io/micronaut-data/latest/guide/).
   */
  MICRONAUT_DATA_JDBC,

  /**
   * Classes should be generated for all database tables, with Spring Data JDBC-specific annotations.
   *
   * See [Spring Documentation](https://docs.spring.io/spring-data/relational/reference/jdbc.html).
   */
  SPRING_DATA_JDBC,
}
