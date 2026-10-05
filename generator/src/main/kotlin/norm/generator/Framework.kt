package norm.generator

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.TypeName

/**
 * Framework-specific code generation options.
 *
 * @property connectionProviderTemplate name of the `.kt` template resource emitted as the connection provider.
 * @property delegatesTransactions `true` when the framework's `@Transactional` owns transactions. Norm then omits its own
 *   transaction API.
 * @property adapterAnnotations placed on generated enum and domain adapters.
 */
public enum class Framework(
  internal val connectionProviderTemplate: String,
  internal val delegatesTransactions: Boolean,
  internal val adapterAnnotations: List<ClassName>,
) {
  /**
   * Generates Micronaut DI annotations and a `MicronautConnectionProvider` that participates in
   * Micronaut-managed transactions.
   *
   * See [Micronaut Documentation](https://micronaut-projects.github.io/micronaut-data/latest/guide/).
   */
  MICRONAUT_DATA(
    connectionProviderTemplate = "MicronautConnectionProvider",
    delegatesTransactions = true,
    adapterAnnotations = listOf(JAKARTA_SINGLETON),
  ),

  /**
   * Generates Spring DI annotations and a `SpringConnectionProvider` that participates in
   * Spring-managed transactions.
   *
   * See [Spring Documentation](https://docs.spring.io/spring-data/relational/reference/jdbc.html).
   */
  SPRING_DATA(
    connectionProviderTemplate = "SpringConnectionProvider",
    delegatesTransactions = true,
    adapterAnnotations = listOf(SPRING_COMPONENT),
  ),

  /**
   * Generates Micronaut DI annotations and a `@Factory` that provides a
   * `norm.TransactionalConnectionProvider` from an injected `javax.sql.DataSource`.
   *
   * Unlike [MICRONAUT_DATA], this mode does not generate a `MicronautConnectionProvider` and
   * requires no `micronaut-data` dependency. Transactions are Norm-managed: the generated `Queries`
   * interface extends `norm.Transactable`, so callers can run `transaction { }` on the injected
   * `Queries` bean without Micronaut's `@Transactional`.
   */
  MICRONAUT(
    connectionProviderTemplate = "NormConnectionProviderFactory",
    delegatesTransactions = false,
    adapterAnnotations = listOf(JAKARTA_SINGLETON),
  ),
  ;

  /**
   * Returns the annotations placed on the generated `PostgresQueries` class.
   *
   * @param missingBeanType Micronaut skips the generated class when the container already holds a bean of this type.
   */
  internal fun queriesAnnotations(missingBeanType: TypeName): List<AnnotationSpec> = when (this) {
    MICRONAUT_DATA, MICRONAUT -> listOf(
      AnnotationSpec.builder(JAKARTA_SINGLETON).build(),
      AnnotationSpec.builder(MICRONAUT_REQUIRES)
        .addMember("missingBeans = [%T::class]", missingBeanType)
        .build(),
      AnnotationSpec.builder(MICRONAUT_REQUIRES)
        .addMember("beans = [%T::class]", JAVAX_DATASOURCE)
        .build(),
    )
    SPRING_DATA -> listOf(AnnotationSpec.builder(SPRING_COMPONENT).build())
  }
}
