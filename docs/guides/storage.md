# Storage

Postgres. `loch-jdbc` contains **no cryptography and no compression of its own** — it depends on
`loch-core` and the codec *contract*, and applies whatever pipeline you hand it.

```java
JdbcStorage storage = new JdbcStorageConfig()
    .dataSource(dataSource)
    .codecs(new JacksonCodecFactory(objectMapper))      // serialise
    .storedThrough(StorageCodec.of(                     // then your byte pipeline
        Compression.whenItHelps(new GzipCodec())
            .andThen(EnvelopeCodec.builder(keys).build())))
    .rootedIn("prod-2026", secret)                      // the key the digests are under
    .storage(axes);
```

Jackson or fory or protobuf; gzip or zstd or lz4; envelope encryption or your own KMS. For tests,
`.storedPlainly()` skips the pipeline entirely.

## Composing the pipeline

**Compression before encryption**, always. Ciphertext does not compress, so the other order costs
the same and saves nothing.

**Compression should be conditional**, because measurement says so. Most of what a charter holds is
small, and a compressor's framing costs more than a short payload saves:

```
{"number":"4111111111114821","holder":"J CARMAN"}   49 bytes -> gzip 57   BIGGER
{"v":"4821"}                                        12 bytes -> gzip 32   BIGGER
an email body                                      851 bytes -> gzip 79   smaller
```

`Compression.whenItHelps(...)` keeps the result only when it actually shrank, and records which
with one leading byte. Worst case is one byte instead of a threefold expansion.

**Bound your decompression.** The bytes come from a database, and a corrupt or hostile row should
not expand into an out-of-memory error. That is a property of the compressor you supply.

## Roots

`rootedIn(name, secret)` names the key the digests are computed under. The name is stored with
each row, so rotating a root does not invalidate what was written under the last one — supply both
and old rows still verify.

**The secret is not in the database.** That is the whole point: an unkeyed chain catches a careless
edit and nothing else, because whoever removed a line could recompute everything after it. A row
naming a root nobody supplies is reported as broken rather than crashing the verifier.

## What is in the clear

| column | stored |
|---|---|
| `loch_value.payload` | through your pipeline |
| `loch_value.label` | through your pipeline — a label can name a tenant |
| `loch_audit.label`, `detail`, `context` | through your pipeline |
| `loch_audit.operation`, `outcome`, `reason`, `value_id` | in the clear |

The clear columns are the ones that name rules rather than values, so the trail stays queryable. An
audit nobody can query is a tape backup.

## Schema

`migrate()` creates the tables if they are not there. It is `CREATE TABLE IF NOT EXISTS` only —
nothing alters an existing table — and `.withoutMigration()` skips it for a database you manage
yourself.

The storage uses `pg_advisory_xact_lock`, recursive CTEs, `FOR SHARE` and `clock_timestamp()`, so
it is Postgres rather than "any JDBC database". Isolation is pinned to READ COMMITTED per
transaction and restored afterwards, because the chain's ordering depends on it.

## Verifying

```java
storage.firstBrokenEntry();   // check the chain first
storage.brokenValues();
storage.missingValues();
```

See [The Record](../concepts/the-record.md).

## In tests

`MemoryStorage` stores references in a map, does not encrypt, and does not survive a restart. It
holds the object you gave it rather than a copy, so hold immutable values and the difference never
shows.
