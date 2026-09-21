# The specification and what it is bound to

**Status:** design, not yet built. Follows `2026-09-21-axes-as-the-label-design.md`, which is built.

## The observation

There are two things here wearing one coat.

A **specification** says what this application permits: the axes it labels values on, the doors it
has, what each door may read, which derivations exist, which of them may declassify and by how much,
who is offered what. All of it is decided at configuration and none of it needs a database.

A **binding** makes that specification operational: durable, at scale, audited. Storage, identifiers,
the trail, transactions, encryption.

The line already exists in the code. It is simply not drawn, and three measurements say so.

**`manifest()` touches storage zero times.** It is a complete report of what the system permits —
every door, every derivation, every ceiling — computed entirely from the specification. But the only
way to obtain one is to construct a `DefaultSurrogateStore`, which means binding to a storage you do
not need in order to answer the question.

**Storage is a thin seam.** Eleven call sites in a 650-line class, and they are four verbs:
`metadata`, `value`, `put`, `record`. Everything else in that file — `leavesARequiredAxisUnsaid`,
`ceilingOf`, `offeredHere`, `reasonFor`, the lowering guard, the type check — is specification logic
sitting next to I/O because that is where it was written.

**The reachability report has nowhere to live.** "Which sources have no path to a sink? Which doors
read a type nothing produces?" is a question about the specification alone. Today it would have to be
written inside the store, because that is where the registries ended up.

## What this is not

It is not a hexagonal-architecture exercise, and the payoff is not mainly testability.

It is that **"what does this system permit?" becomes answerable without a database.** Render the
manifest, run the reachability report, diff one release's specification against the last one in CI —
from a configuration object, in a unit test, with no container. That is a reviewer-facing property,
and it is the same argument that put capability minting at configuration time in the first place:
the set of things this application can do should be enumerable by reading it.

## The shape

The hard part is that a decision is not one thing. Reading the derive path as it stands, an operation
goes:

1. is this offered here, and what is the ceiling for this access?
2. fetch each parent's **metadata** — type name and label
3. right types? every parent under the ceiling? join the labels
4. fetch each parent's **plaintext** and run the application's function
5. did it decline? is the relabel it asked for genuinely below the join?
6. write the value and the line

Steps 1, 3 and 5 are pure. Steps 2, 4 and 6 are I/O. And the impure part is *in the middle*, not at
the end, because the function needs plaintext that the decision at step 3 is what authorises.

So the specification side is **two** functions per operation, not one:

```
admit(spec, parents' metadata, context)   -> Admitted(joined label) | Refused(reason, explanation)
settle(spec, joined label)                -> Settled(resulting label) | Refused(reason, explanation)
```

`admit` decides on labels alone and never sees plaintext. `settle` runs the declassification rule and
checks it. Between them sits the one thing that genuinely cannot move: reading the value and running
application code over it.

Every operation fits this, with pieces omitted rather than reshaped:

| operation | admit | plaintext | settle |
|---|---|---|---|
| source | is the label complete? | — | — |
| sink | type matches, label under ceiling | read it | — |
| query | type matches, label under ceiling | read it, run the predicate | — |
| derivation / fold | types match, every parent under ceiling, join | read them, run the function | is the relabel below the join? |
| erase | may this context erase a value labelled so? | — | — |

## The rule the split has to obey

**A decision produces its audit record.** Not "and then you must remember to write one" — the
decision hands one back, and the binding's job is to persist it.

This is not theoretical tidiness. Earlier in this library's life, policies that threw were escaping
`ask` and `derive` unaudited: the gate had decided, the caller got an exception, and nothing was
written down. That was fixed by hand, with `ceilingOf` and `offeredHere` helpers wrapping every call
into application code. Making the decision *return* the record makes the bug unrepresentable rather
than fixed — there is no shape of the code where a refusal exists without a line to write.

The corollary is the thing to protect: the specification decides and the binding records. Never the
specification decides and the binding *optionally* records.

## What stays where

| specification | binding |
|---|---|
| `Axis`, `Label`, `Ceiling`, `Constraint` | `Storage` and its implementations |
| the portals and what each may read | identifier minting |
| `admit` / `settle` for every operation | persisting values and audit records |
| the audit record's *content* | the audit record's *durability* |
| `manifest()` | transactions, encryption, schema |
| reachability | |

Note where the audit record falls: its content is a decision, its durability is a binding. That split
is what makes the rule above expressible.

## Open questions

1. **Names.** The specification half needs one, and `SurrogateStoreConfig` is currently doing the job
   under a name that says "settings". Whatever it becomes, `admit` and `settle` are working titles
   too — they are the two halves of a decision and they should read like it.

2. **`Binding` is taken.** `SurrogateStoreConfig.Binding` already exists and means something else
   entirely: the one-shot attachment that lets a capability minted during configuration reach the
   store once it is built. Calling the storage half "binding" collides with it in the same package.
   One of the two has to give.

3. **How far does this go before it stops paying?** Splitting `admit`/`settle` out of
   `DefaultSurrogateStore` is a real refactor of a 650-line class that is currently green and
   correct. The manifest and the reachability report are worth it on their own. Whether the
   per-operation decision functions are worth it depends on whether anything other than a test ever
   calls them, and that should be decided before rather than discovered after.
