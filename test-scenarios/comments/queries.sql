-- name: getAuthorById :one
SELECT * FROM author WHERE id = ?;

-- Returns a book's title and publication year.
-- name: getBookTitleAndYear :one
SELECT title, published_year FROM book WHERE id = ?;

-- name: addAuthor :exec
INSERT INTO author (name, bio) VALUES (?, ?);

-- Returns a book's title, publication year, and author name.
-- name: getBookWithAuthorName :one
SELECT book.title, book.published_year, author.name AS author_name
FROM book
JOIN author ON author.id = book.author_id
WHERE book.id = ?;

-- Returns the number of books by an author.
-- name: countBooksByAuthor :one
SELECT author.name, COUNT(*) AS book_count
FROM author
JOIN book ON book.author_id = author.id
WHERE author.id = ?
GROUP BY author.name;

-- Updates a book's title.
-- name: updateBookTitle :execrows
UPDATE book SET title = ? WHERE id = ?;

-- name: getBookByIsbn :one
SELECT * FROM book WHERE isbn = ?;

-- name: listBooksByAuthor :many
SELECT * FROM book WHERE author_id = ?;

-- Lists books published within a year range.
-- name: listBooksByYearRange :many
SELECT * FROM book WHERE published_year >= ? AND published_year <= ?;

-- Creates an account with a hashed password.
-- name: createAccount :exec
INSERT INTO account (username, password) VALUES (?, crypt(?, gen_salt('bf')));

-- Returns a row's id and its quoted "Foo" column, aliased as bar.
-- name: getQuotedColumnWithAlias :one
SELECT id, "Foo" AS bar FROM tq WHERE id = ?;

-- Returns a row's id and its quoted "My Col" column.
-- name: getQuotedColumnWithSpace :one
SELECT id, "My Col" FROM tq WHERE id = ?;

-- Lists book ids and titles.
-- name: getBooksWithTrailingComment :many
SELECT id, title FROM book
-- only rows with a title
;
