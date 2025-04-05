-- name: getAuthorByName :one
SELECT * FROM AUTHOR WHERE name = $1;

-- name: listAuthors :many
SELECT * FROM AUTHOR;

-- -- name: addAuthor :execrows
-- INSERT INTO AUTHOR(id, name, email) VALUES ($1, $2, $3);
--
-- -- name: setName :execrows
-- UPDATE AUTHOR SET name=$1;


