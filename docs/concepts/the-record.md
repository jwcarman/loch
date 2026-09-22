# The Record

Every operation writes one line — allowed and refused alike. A thousand refused attempts against
one value is the interesting event, and a log recording only successes cannot show it.

A line never contains plaintext. It does carry the label, because an audit that cannot say *why*
something was refused is not much of an audit — and a label can name a tenant or a project
codeword, so it is encrypted on disk exactly like the value is. So is the access context, which
this library never interprets and therefore cannot judge harmless.

`reason` holds a code in the clear, because it names a rule rather than a value: *"how many
refusals above a ceiling this hour"* should be answerable without decrypting anything.

## Tamper evidence

Two signed structures, under a key the database does not hold.

**The trail is a keyed hash chain.** Each line commits to the digest of the one before it. Keyed is
the whole point: an unkeyed chain catches a careless edit and nothing else, because whoever removed
a line could recompute everything after it.

**Values form a signed graph.** A value's digest covers its bytes, its label, what made it, and the
digests of its parents — so editing one breaks every value derived from it. There is no global
order here and none is needed: a value is fixed by its ancestry rather than by when it arrived.

## Three verifiers

```java
storage.firstBrokenEntry();   // a line edited, removed, reordered or replayed
storage.brokenValues();       // a value edited, or descended from one
storage.missingValues();      // announced by the trail, never erased, and not here
```

The third exists because the value graph is blind to one case. Deleting a **leaf** orphans nothing,
so there is nothing left to disagree, and a verifier that walks the rows still present cannot miss
what is absent. Only the trail can answer that, because only the trail is outside the row — which
is why erasure records *which* values it destroyed, in the same transaction as the deletes.

Check the chain first. `missingValues()` reports claims the trail makes, and whoever could delete a
value row could delete its CONCEAL line too; the chain is what makes that visible.

## What this does not detect

**Tail truncation.** Removing the most recent lines leaves a chain that verifies perfectly.
Detecting it requires an anchor kept somewhere the writer cannot reach — a periodic digest
published elsewhere. This is a property of hash chains, not an oversight.

See [What Loch Does Not Do](../limits.md).
