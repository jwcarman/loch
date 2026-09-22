# Erasing

"Erase this customer" is a reachability question, which is why lineage is kept.

```java
int removed = charter.erase(surrogate);
```

Erasing a value takes everything ever derived from it, however deeply. Descendants go regardless of
their own labels, which is what erasure means: a value derived from two customers dies with either
of them.

## Who may erase

A label has nothing to say about whether a value may be *destroyed* — "possession is not authority"
is a rule about reading. So the authority to erase is named separately, or it is not granted:

```java
charter.mayErase((label, ctx) ->
    ctx.has("role", "compliance")
        && ctx.get("tenant")
              .map(t -> Ceiling.of(TENANT, Constraint.atMost(t)).with(LEVEL, Constraint.any())
                               .permits(label))
              .orElse(false));
```

The policy sees **the label of the value being destroyed** as well as who is asking, because who
alone is not enough: a rule checking only the caller's role lets one tenant's compliance officer
destroy another tenant's records.

An application that never erases says nothing and gets a charter that cannot.

Erasure is also absent from the `Charter` interface that beans are handed. Nothing in an
application has needed it, and leaving it off means the answer to *"which code can destroy customer
data?"* is **none, structurally**.

## What survives

The audit outlives what it describes. `loch_audit` has no foreign key into `loch_value` and nothing
cascades into it: the record that you erased somebody has to survive erasing them, or the system
cannot prove it did the thing it was legally required to do.

Erasure writes one line per value destroyed, in the same transaction as the deletes. Not one line
per call — because erasure is the only operation that makes a value stop existing, so the trail
becomes the only thing that can say the value ever did. A single line saying *"41 values removed"*
cannot tell a lawful erasure from a quiet deletion, since nothing afterwards knows which 41.

## Reachability is computed from what is signed

The set of values an erasure destroys is walked over the lineage that the value digests cover, so
nothing decides what gets destroyed that a signature does not protect.

Deriving takes a shared lock and erasing an exclusive one, so a derivation cannot commit a child of
values an erasure is in the middle of removing. Derivations never block one another; an erasure
waits for those in flight. Erasing is rare and must be complete.
