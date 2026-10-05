-- Table with SERIAL PK and DEFAULT columns
CREATE TABLE author (
  id SERIAL PRIMARY KEY,
  name TEXT NOT NULL,
  bio TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Table with composite PK
CREATE TABLE order_item (
  order_id INTEGER NOT NULL,
  item_id INTEGER NOT NULL,
  quantity INTEGER NOT NULL,
  price NUMERIC(10,2) NOT NULL,
  PRIMARY KEY (order_id, item_id)
);

-- Table with no PK (only findAll, count, deleteAll, insert should be generated)
CREATE TABLE audit_log (
  message TEXT NOT NULL,
  logged_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Table with a generated column
CREATE TABLE product (
  id SERIAL PRIMARY KEY,
  name TEXT NOT NULL,
  price NUMERIC(10,2) NOT NULL,
  tax NUMERIC(10,2) NOT NULL,
  total NUMERIC(10,2) GENERATED ALWAYS AS (price + tax) STORED
);

-- View — should be skipped entirely
CREATE VIEW author_names AS
  SELECT id, name FROM author;

-- Table with a nullable jsonb column: pins jsonb binding in the synthesized CRUD insert.
CREATE TABLE document (
  id SERIAL PRIMARY KEY,
  title TEXT NOT NULL,
  metadata JSONB
);

-- Table where every non-auto-increment column has a DEFAULT, and one of them is nullable: pins the
-- synthesized INSERT whose parameters are all optional, and a nullable default column's
-- ColumnValue<T?> shape, together.
CREATE TABLE preference (
  id SERIAL PRIMARY KEY,
  theme TEXT NOT NULL DEFAULT 'light',
  note TEXT DEFAULT 'n/a'
);

-- Table with quoted, mixed-case, space-containing, and mixed-case-reserved-word column names: pins
-- CrudQuerySynthesizer's own identifier quoting in the INSERT it builds. Reading such columns back
-- is covered by the "tq" table in test-scenarios/comments/schema.sql. This table has no column with
-- an embedded double quote (e.g. "a""b"). That SQL is valid, and JdbcAnalyzerTest and
-- SqlParameterInferrerTest cover it. The Kotlin identifier it produces contains a literal `"`,
-- which kotlinc reports as a "problems on Windows" warning. The compiling test project treats
-- warnings as errors. A backtick, "*/", ".", and a literal newline have the same naming limitation.
CREATE TABLE quoted_columns (
  id SERIAL PRIMARY KEY,
  "Foo" TEXT NOT NULL,
  "My Col" TEXT,
  "Select" TEXT
);

-- Domain over an array type: pins full read/write support for a domain whose base
-- type is itself an array, not just the domain-over-scalar case the other domains above cover.
CREATE DOMAIN int_set AS INTEGER[];

-- Table with a NOT NULL and a nullable int_set column: pins both the synthesized CRUD insert's
-- binding and the generated row type's nullability for a domain-over-array column.
CREATE TABLE tag_group (
  id SERIAL PRIMARY KEY,
  required_tags int_set NOT NULL,
  optional_tags int_set
);
