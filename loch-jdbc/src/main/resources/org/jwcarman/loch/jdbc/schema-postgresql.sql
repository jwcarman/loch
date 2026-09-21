-- Everything a loch keeps, and nothing it decides. Policy lives in the application.
--
-- The payload and the label are both encrypted: a label can itself be sensitive -- a tenant's
-- name, a project codeword -- so storing it in the clear beside the ciphertext would describe what
-- the ciphertext is to anyone who can read the table.
CREATE TABLE IF NOT EXISTS loch_value (
  value_id     TEXT PRIMARY KEY,
  value_type   TEXT        NOT NULL,
  payload      BYTEA       NOT NULL,
  label        BYTEA       NOT NULL,
  derivation   TEXT,
  held_at      TIMESTAMPTZ NOT NULL
);

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

-- Every access, allowed or refused, written in the same transaction as the value it concerns.
--
-- Deliberately NOT a child of loch_value: no foreign key, no cascade. Erasing a customer removes
-- their values and everything derived from them, and the record that it happened has to survive
-- that, or the system cannot prove it did the thing it was legally required to do. The audit
-- outlives what it describes.
--
-- Most of this is in the clear, unlike loch_value. An audit trail nobody can query is a tape
-- backup: answering "who touched this value", "what did this user do", "how many refusals in the
-- last hour" needs indexes on real columns. The label is the exception and is encrypted like the
-- one on loch_value, for the same reason -- a label names a tenant, and the label column would
-- otherwise describe every value in the system to anyone who could read this table.
CREATE TABLE IF NOT EXISTS loch_audit (
  entry_id    BIGSERIAL PRIMARY KEY,
  at          TIMESTAMPTZ NOT NULL,
  operation   TEXT        NOT NULL,
  value_id    TEXT,
  target      TEXT,
  outcome     TEXT        NOT NULL,
  reason      TEXT,
  label       BYTEA,
  who         TEXT        NOT NULL
);

CREATE INDEX IF NOT EXISTS loch_audit_value ON loch_audit (value_id);
CREATE INDEX IF NOT EXISTS loch_audit_at ON loch_audit (at);
