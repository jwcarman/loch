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
  held_at      TIMESTAMPTZ NOT NULL,
  -- What this value hashes to, over its own bytes and the digests of whatever it was made from.
  --
  -- Every fresh value starts its own graph: it has no parents, so it hashes from the root alone.
  -- Everything derived from it hashes from its parents, which are immutable and already written,
  -- so nothing has to be locked and two derivations never wait on each other. There is no global
  -- order here and none is needed -- a value is fixed by its ancestry, not by when it arrived.
  --
  -- Editing a value changes its digest, which breaks every descendant. Deleting one leaves its
  -- children hashing from something that is not there. Covering either up means recomputing the
  -- whole graph below it, which is exactly the work this makes necessary.
  --
  -- The root is where that stops being merely expensive. Rooted in a constant, somebody with write
  -- access can recompute a graph after editing it. Rooted in a secret the database does not hold,
  -- they cannot forge a single node.
  digest       BYTEA       NOT NULL
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
-- last hour" needs indexes on real columns. So `reason` holds the code alone -- it names a rule,
-- not a value, and stays queryable.
--
-- `detail` and `label` are the exceptions and are encrypted like the label on loch_value, for the
-- same reason: they name a tenant, and in the clear they would describe every value in the system
-- to anyone who could read this table. `detail` is where a refusal says which label it turned away
-- and against which ceiling, which is exactly the thing a refusal must never tell its caller.
CREATE TABLE IF NOT EXISTS loch_audit (
  entry_id    BIGSERIAL PRIMARY KEY,
  at          TIMESTAMPTZ NOT NULL,
  operation   TEXT        NOT NULL,
  value_id    TEXT,
  target      TEXT,
  outcome     TEXT        NOT NULL,
  reason      TEXT,
  detail      BYTEA,
  label       BYTEA,
  -- Each line names the digest of the line before it. Values hash from their parents. A line has
  -- no parents, only a predecessor, so the trail is a chain where the graph of values is a DAG.
  --
  -- Keyed, and that is the whole point. An unkeyed chain catches a careless edit and nothing else:
  -- delete a line, recompute the ones after it, and the chain agrees with itself again. Under an
  -- HMAC whose key this database does not hold, the ones after it cannot be recomputed, so a
  -- deletion leaves a break nobody can repair.
  --
  -- The cost is that appends are ordered -- each needs the digest of whatever came last, which is
  -- an advisory lock, not a table lock. Measured at about 180 appends a second from one writer and
  -- 900 from eight: writers still overlap on the value, the lineage, the encoding and the network,
  -- and queue only at the end.
  previous    BYTEA,
  digest      BYTEA       NOT NULL,
  who         TEXT        NOT NULL
);

CREATE INDEX IF NOT EXISTS loch_audit_value ON loch_audit (value_id);
CREATE INDEX IF NOT EXISTS loch_audit_at ON loch_audit (at);
