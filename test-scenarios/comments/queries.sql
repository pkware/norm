-- name: getAuthorById :one
SELECT * FROM author WHERE id = ?;

-- Ad-hoc projection: only some columns returned, so generates a query-specific type.
-- name: getBookTitleAndYear :one
SELECT title, published_year FROM book WHERE id = ?;

-- name: addAuthor :exec
INSERT INTO author (name, bio) VALUES (?, ?);

-- Cross-table join: projection columns come from two different tables.
-- name: getBookWithAuthorName :one
SELECT book.title, book.published_year, author.name AS author_name
FROM book
JOIN author ON author.id = book.author_id
WHERE book.id = ?;

-- Function result: COUNT has no source table or column.
-- name: countBooksByAuthor :one
SELECT author.name, COUNT(*) AS book_count
FROM author
JOIN book ON book.author_id = author.id
WHERE author.id = ?
GROUP BY author.name;

-- UPDATE: SET params and WHERE params both get column comments.
-- name: updateBookTitle :execrows
UPDATE book SET title = ? WHERE id = ?;

-- Column with no COMMENT ON: isbn should not produce a @param tag.
-- name: getBookByIsbn :one
SELECT * FROM book WHERE isbn = ?;

-- :many with parameters: verify params flow through the Many code path.
-- name: listBooksByAuthor :many
SELECT * FROM book WHERE author_id = ?;

-- Range: two params on the same column get deduplicated names and both get comments.
-- name: listBooksByYearRange :many
SELECT * FROM book WHERE published_year >= ? AND published_year <= ?;

-- Function-wrapped parameter: the param maps to the column despite being inside crypt().
-- name: createAccount :exec
INSERT INTO account (username, password) VALUES (?, crypt(?, gen_salt('bf')));

-- Quoted column reference WITH an alias: originalName must resolve through the quoted column's
-- own name ("Foo"), not through the alias ("bar") pgjdbc's own ResultSetMetaData.getColumnName
-- reports instead when AS is used -- otherwise comment lookup for "Foo" fails outright, since no
-- column is actually named "bar". A second column is selected so a data class (with @property
-- comment lines) is generated instead of a bare scalar, making the comment lookup outcome visible.
-- name: getQuotedColumnWithAlias :one
SELECT id, "Foo" AS bar FROM tq WHERE id = ?;

-- Quoted column reference containing a space, with no alias. A second column is selected for the
-- same reason as above -- so the comment lookup outcome shows up as a @property line.
-- name: getQuotedColumnWithSpace :one
SELECT id, "My Col" FROM tq WHERE id = ?;
