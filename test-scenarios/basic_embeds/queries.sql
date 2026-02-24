-- name: getAuthor :one
-- Simple embed: single table returned as embedded object
SELECT sqlc.embed(author) FROM author WHERE id = ?;

-- name: listBooksWithAuthors :many
-- Regular column before embed
SELECT b.title, sqlc.embed(author)
FROM book b
JOIN author ON b.author_id = author.id;

-- name: getAuthorWithBookTitle :one
-- Regular column after embed
SELECT sqlc.embed(author), b.title
FROM book b
JOIN author ON b.author_id = author.id
WHERE b.id = ?;
