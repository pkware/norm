-- name: getAuthorByName :one
SELECT * FROM author WHERE name = $1;

-- name: authorAndMostPopularBook :one
SELECT name, title, copies_sold
FROM author
  LEFT JOIN book ON author.id = book.author_id
WHERE name = $1
ORDER BY copies_sold DESC
LIMIT 1;

-- name: mostPopularBook :many
SELECT title, copies_sold
FROM author
  RIGHT JOIN book ON author.id = book.author_id
WHERE name = $1
ORDER BY copies_sold DESC
  LIMIT 1;

-- name: listAuthors :many
SELECT * FROM author;

-- name: listBooks :many
SELECT title from book;

-- name: addAuthor :execrows
INSERT INTO author(name, email) VALUES ($1, $2);

-- name: setEmailForName :execrows
UPDATE author SET email = $1 WHERE name = $2;

-- name: setEmailForNameReturningId :many
UPDATE author SET email = $1 WHERE name = $2 RETURNING id;

-- -- name: assignBooksToAuthor :exec
-- -- TODO $1 is an int author ID, $2 is a list of book IDs. No return value should be generated.
-- CALL assign_books_to_author($1, $2);


