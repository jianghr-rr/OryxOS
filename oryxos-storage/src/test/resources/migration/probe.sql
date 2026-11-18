CREATE TABLE probe (id TEXT PRIMARY KEY, value TEXT);
CREATE TABLE child (parent_id TEXT REFERENCES probe(id));
