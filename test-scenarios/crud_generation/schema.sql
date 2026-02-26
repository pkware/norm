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
