-- name: getAuthor :one
-- Returns an author embedded as a nested object.
SELECT sqlc.embed(author) FROM author WHERE id = ?;

-- name: listBooksWithAuthors :many
-- Lists books, each with its author embedded as a nested object.
SELECT b.title, sqlc.embed(author)
FROM book b
JOIN author ON b.author_id = author.id;

-- name: getAuthorWithBookTitle :one
-- Returns a book's title alongside its author embedded as a nested object.
SELECT sqlc.embed(author), b.title
FROM book b
JOIN author ON b.author_id = author.id
WHERE b.id = ?;
