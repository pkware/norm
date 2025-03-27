package norm

import java.sql.ResultSet

public typealias RowMapper<T> = ResultSet.() -> T
