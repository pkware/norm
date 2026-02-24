-- name: getAuthorByName :one
SELECT * FROM author WHERE name = ?;

-- name: authorAndMostPopularBook :one
SELECT name, title, copies_sold
FROM author
  LEFT JOIN book ON author.id = book.author_id
WHERE name = ?
ORDER BY copies_sold DESC
LIMIT 1;

-- name: mostPopularBook :many
SELECT title, copies_sold
FROM author
  RIGHT JOIN book ON author.id = book.author_id
WHERE name = ?
ORDER BY copies_sold DESC
  LIMIT 1;

-- name: listAuthors :many
SELECT * FROM author;

-- name: listBooks :many
SELECT title from book;

-- name: addAuthor :execrows
INSERT INTO author(name, email) VALUES (?, ?);

-- name: setEmailForName :execrows
UPDATE author SET email = ? WHERE name = ?;

-- name: setEmailForNameReturningId :many
UPDATE author SET email = ? WHERE name = ? RETURNING id;

-- -- name: assignBooksToAuthor :exec
-- -- TODO first ? is an int author ID, second ? is a list of book IDs. No return value should be generated.
-- CALL assign_books_to_author(?, ?);


