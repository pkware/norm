package com.pkware.norm.runtime

import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.Types

/**
 * Sets the designated parameter to the given Java Boolean value.
 *
 * The driver converts this to an SQL BOOLEAN value when it sends it to the database.
 *
 * @param parameterIndex the first parameter is 1, the second is 2, ...
 * @param x the parameter value
 * @throws SQLException if parameterIndex does not correspond to a parameter marker in the SQL statement;
 * if a database access error occurs or this method is called on a closed PreparedStatement
 */
@Throws(SQLException::class)
public fun PreparedStatement.setBoolean(parameterIndex: Int, x: Boolean?) {
  if (x == null) setNull(parameterIndex, Types.BOOLEAN) else setBoolean(parameterIndex, x)
}

/**
 * Sets the designated parameter to the given Java Short value.
 *
 * The driver converts this to an SQL SMALLINT value when it sends it to the database.
 *
 * @param parameterIndex the first parameter is 1, the second is 2, ...
 * @param x the parameter value
 * @throws SQLException if parameterIndex does not correspond to a parameter marker in the SQL statement;
 * if a database access error occurs or this method is called on a closed PreparedStatement
 */
@Throws(SQLException::class)
public fun PreparedStatement.setShort(parameterIndex: Int, x: Short?) {
  if (x == null) setNull(parameterIndex, Types.SMALLINT) else setShort(parameterIndex, x)
}

/**
 * Sets the designated parameter to the given Java Int value.
 *
 * The driver converts this to an SQL INTEGER value when it sends it to the database.
 *
 * @param parameterIndex the first parameter is 1, the second is 2, ...
 * @param x the parameter value
 * @throws SQLException if parameterIndex does not correspond to a parameter marker in the SQL statement;
 * if a database access error occurs or this method is called on a closed PreparedStatement
 */
@Throws(SQLException::class)
public fun PreparedStatement.setInt(parameterIndex: Int, x: Int?) {
  if (x == null) setNull(parameterIndex, Types.INTEGER) else setInt(parameterIndex, x)
}

/**
 * Sets the designated parameter to the given Java Long value.
 *
 * The driver converts this to an SQL BIGINT value when it sends it to the database.
 *
 * @param parameterIndex the first parameter is 1, the second is 2, ...
 * @param x the parameter value
 * @throws SQLException if parameterIndex does not correspond to a parameter marker in the SQL statement;
 * if a database access error occurs or this method is called on a closed PreparedStatement
 */
@Throws(SQLException::class)
public fun PreparedStatement.setLong(parameterIndex: Int, x: Long?) {
  if (x == null) setNull(parameterIndex, Types.BIGINT) else setLong(parameterIndex, x)
}

/**
 * Sets the designated parameter to the given Java Float value.
 *
 * The driver converts this to an SQL FLOAT value when it sends it to the database.
 *
 * @param parameterIndex the first parameter is 1, the second is 2, ...
 * @param x the parameter value
 * @throws SQLException if parameterIndex does not correspond to a parameter marker in the SQL statement;
 * if a database access error occurs or this method is called on a closed PreparedStatement
 */
@Throws(SQLException::class)
public fun PreparedStatement.setFloat(parameterIndex: Int, x: Float?) {
  if (x == null) setNull(parameterIndex, Types.FLOAT) else setFloat(parameterIndex, x)
}

/**
 * Sets the designated parameter to the given Java Double value.
 *
 * The driver converts this to an SQL DOUBLE value when it sends it to the database.
 *
 * @param parameterIndex the first parameter is 1, the second is 2, ...
 * @param x the parameter value
 * @throws SQLException if parameterIndex does not correspond to a parameter marker in the SQL statement;
 * if a database access error occurs or this method is called on a closed PreparedStatement
 */
@Throws(SQLException::class)
public fun PreparedStatement.setDouble(parameterIndex: Int, x: Double?) {
  if (x == null) setNull(parameterIndex, Types.DOUBLE) else setDouble(parameterIndex, x)
}
