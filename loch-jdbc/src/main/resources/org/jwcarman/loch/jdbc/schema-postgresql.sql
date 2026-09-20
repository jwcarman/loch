-- Everything a loch keeps, and nothing it decides. Policy lives in the application.
--
-- The payload and the attribution are both encrypted: a label can itself be sensitive -- a tenant's
-- name, a project codeword -- so storing it in the clear beside the ciphertext would describe what
-- the ciphertext is to anyone who can read the table.
CREATE TABLE IF NOT EXISTS loch_value (
  value_id     TEXT PRIMARY KEY,
  value_type   TEXT        NOT NULL,
  payload      BYTEA       NOT NULL,
  attribution  BYTEA       NOT NULL,
  derivation   TEXT,
  dedupe_key   TEXT,
  held_at      TIMESTAMPTZ NOT NULL
);

-- Recognises work already done, so repeating a derivation does not store a second copy of the same
-- thing. An index and never an identifier: a key computed from its inputs is computable by anyone
-- who knows them, so handles stay random and this stays here, where no caller sees it.
CREATE UNIQUE INDEX IF NOT EXISTS loch_value_dedupe_key
  ON loch_value (dedupe_key) WHERE dedupe_key IS NOT NULL;

-- The immediate parentage, in the order the parents were given.
CREATE TABLE IF NOT EXISTS loch_lineage (
  child_id   TEXT    NOT NULL,
  parent_id  TEXT    NOT NULL,
  position   INTEGER NOT NULL,
  PRIMARY KEY (child_id, parent_id),
  FOREIGN KEY (child_id) REFERENCES loch_value (value_id) ON DELETE CASCADE
);

-- Reachability, maintained as values are derived, so that erasing a customer is one indexed query
-- rather than a recursive walk. Lineage is a DAG and not a tree -- a value can have several parents
-- -- so this is a closure table rather than a materialised path, whose rows would multiply at every
-- merge. Here the row count is the number of genuinely reachable pairs, which is the smallest
-- honest representation of "everything downstream of this".
CREATE TABLE IF NOT EXISTS loch_lineage_closure (
  ancestor_id   TEXT    NOT NULL,
  descendant_id TEXT    NOT NULL,
  depth         INTEGER NOT NULL,
  PRIMARY KEY (ancestor_id, descendant_id)
);

CREATE INDEX IF NOT EXISTS loch_lineage_closure_descendant
  ON loch_lineage_closure (descendant_id);
