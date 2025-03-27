package example

import javax.sql.DataSource
import norm.JdbcQueries

public class JdbcExampleQueries(
  dataSource: DataSource,
) : JdbcQueries(dataSource),
    ExampleQueries
