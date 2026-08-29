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

-- Table with a nullable jsonb column: pins jsonb binding in the synthesized CRUD insert (#187).
CREATE TABLE document (
  id SERIAL PRIMARY KEY,
  title TEXT NOT NULL,
  metadata JSONB
);

-- Table with quoted, mixed-case, space-containing, and mixed-case-reserved-word column names: pins
-- CrudQuerySynthesizer's own identifier quoting in the SQL it BUILDS (INSERT), a different surface
-- from reading such columns back, which test-scenarios/comments/schema.sql's "tq" table already
-- covers (#238). An embedded double quote (e.g. "a""b") is deliberately NOT added here: it produces
-- valid SQL (JdbcAnalyzerTest and SqlParameterInferrerTest pin that at the unit level, #238 11.4),
-- but the resulting Kotlin identifier containing a literal `"` still trips a kotlinc "problems on
-- Windows" warning-as-error in a real compiling project -- the same pre-existing, out-of-scope
-- naming-pipeline gap already documented for a backtick, "*/", ".", and a literal newline.
CREATE TABLE quoted_columns (
  id SERIAL PRIMARY KEY,
  "Foo" TEXT NOT NULL,
  "My Col" TEXT,
  "Select" TEXT
);
