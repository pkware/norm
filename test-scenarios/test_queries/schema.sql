CREATE TABLE author (
  id serial PRIMARY KEY,
  name text NOT NULL
);

-- Neither the schema's enum nor this table is queried by the main queries.
CREATE TYPE audit_action AS ENUM ('created', 'updated', 'deleted');

CREATE TABLE audit_log (
  id serial PRIMARY KEY,
  author_id integer NOT NULL REFERENCES author (id),
  action audit_action NOT NULL
);
