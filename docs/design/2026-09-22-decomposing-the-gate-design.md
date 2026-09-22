# Decomposing the gate

Status: agreed, not started. Lands **after** 0.1.0; the release does not wait for it.

## What prompted this

SonarCloud reports S6539 "Monster Class" against two types:

| class | lines | type dependencies | limit |
|---|---|---|---|
| `DefaultCharter` | 740 | 27 | 20 |
| `Engine` | 858 | 22 | 20 |

An earlier attempt moved the manifest computation into `Manifests`, taking 254
lines out of `DefaultCharter`. The dependency count barely moved, because size
was never what the rule counts.

Reading what the 27 and 22 actually are says the same thing about both files.
`DefaultCharter`'s 27 is 16 core types plus 12 from `loch-api`, and the api
dozen is almost entirely the portal vocabulary it manufactures: `Conceal`,
`Reveal`, `Query`, `Derivation`, `Fold`, `SurrogateDestination`, and the things
those return. `Engine`'s 22 arrives in per-operation clusters — reveal drags
`DestinationSpec` and `Revealed`, query drags `QuerySpec` and `Answer`, derive
drags `DerivationSpec` and `Derived`.

Both classes do their whole job once per operation, five times over. The rule
is pointing at that, and it is right to.

One finding matters more than the count. `Engine`'s constructor takes
`DefaultCharter.Configuration` and `DefaultCharter` constructs `Engine`: the
gate and the charter each need the other to compile. `Configuration` is an
immutable record with eight fields and no reference to `DefaultCharter`, so the
cycle is an artefact of where the record is declared and nothing else.

## What this is not

**Not a narrowing of `Charter`.** `DefaultCharter` cannot be narrower than the
interface it implements, and `Charter` names roughly 18 types across its
registration overloads before `DefaultCharter` does anything of its own. This
design gets `DefaultCharter` to about 21. Getting it comfortably under 20 means
changing the published registration API, which is a different conversation and
is not attempted here.

**Not a change to any published type.** Every type introduced here is
package-private. `Charter`, `Surrogate`, `Revealed`, `Answer`, `Derived` and
the portal interfaces are untouched.

**Not a blocker for 0.1.0.** The release ships on today's `Engine`, which is
the code the coverage work and the adversarial reviews were done against. Both
S6539 findings are marked won't-fix in SonarCloud until this lands, with the
reason recorded there.

## Target structure

Two collaborators, because the shared core is doing two different jobs:

- **`Gate`** decides. `admits`, both `ceilingOf` overloads, `offeredHere`,
  `leavesARequiredAxisUnsaid`, `asking`, `because`.
- **`Trail`** records. `audit`, `entry`. Wraps `Storage.append`.

Five operations, named as gerunds to match the convention already in `Engine`,
whose private helpers are `deriving`, `vetting`, `reading`, `producing`,
`relabelling`, `writing`:

`Concealing`, `Revealing`, `Asking`, `Deriving`, `Erasing`.

Each takes `Gate`, `Trail`, `Storage` and the configuration it needs, and names
only its own spec and result types.

`Configuration` is promoted from a nested record to a top-level package-private
record. This is the whole of the cycle fix; it is a move, not a rewrite, and it
introduces no new vocabulary.

`Engine` disappears. `DefaultCharter.Active` holds the five operations
directly, so no facade survives to re-accumulate what the split shed.

`Engine`'s four read-only helpers — `label(String)`, `holds(String)`,
`lineage(String)` and the private `metadataOf` — are storage lookups with no
gate logic in them. They go to `Gate`, which already holds `Storage` for
`ceilingOf`. They are named here because they are the obvious thing to
overlook: they belong to no single operation, and leaving them behind is what
would keep `Engine` alive as a vestigial facade.

`Portals` takes the portal-minting currently inlined in `DefaultCharter` — the
anonymous implementations of `Conceal`, `Reveal`, `Query`, `Derivation` and
`Fold`. This is what sheds `Surrogate`, `Revealed`, `Answer`, `Derived`,
`Lineage` and `Engine` from `DefaultCharter`.

### Projected coupling

| type | ~deps |
|---|---|
| `Gate` | 10 |
| `Trail` | 5 |
| `Concealing` | 7 |
| `Revealing` | 9 |
| `Asking` | 9 |
| `Deriving` | 15 |
| `Erasing` | 6 |
| `Portals` | 14 |
| `DefaultCharter` | ~21 |

Every piece of the old `Engine` lands well under the limit. `DefaultCharter`
does not, for the reason given above.

## The invariants

This is the part that binds. The classes below hold loch's safety properties,
so a silent behaviour change here is a security bug rather than a regression.
Each list is the order the checks run in today. Reordering any of them is a
violation of this design, not a judgement call available to the implementer.

**Concealing**

1. A null value is rejected outright, before anything is recorded.
2. A null label is a *recorded* refusal, then `AccessDeniedException`.
3. A label leaving a required axis unsaid is a recorded refusal, then
   `AccessDeniedException`. Unsaid is the bottom of its order and below every
   ceiling, so this must fail closed.
4. Only then is an id minted and the value stored.

**Revealing**

1. A null destination is denied.
2. A value not held is denied.
3. The ceiling is resolved. A ceiling that cannot be evaluated is denied —
   silence is not permission.
4. `admits(ceiling, label)`.
5. **Only then** the type check. A reader who may not see a value must not
   learn what kind of value it is; this order is the whole point.
6. Audit, then decode.

**Asking**

1. `offeredHere` — is this question offered to this caller at all.
2. A value not held is refused.
3. The ceiling is resolved; unevaluable is refused.
4. `admits(ceiling, label)`.
5. **Only then** the type check.
6. Only then is the subject decoded.

**Deriving**

1. `unusable` — no parents, arity mismatch, or not offered here.
2. The ceiling is resolved **once**, before any parent is touched, and
   deliberately before any type check.
3. Per parent, in order: held, then `admits(ceiling, label)`, then the type
   check.
4. Plaintext is read **only after every parent has passed**.
5. The application function runs. Returning null is normalised to declining;
   throwing becomes a refusal. Neither may escape as a `NullPointerException`
   past the audit, because by then the plaintext has been handed over.
6. Relabelling, in order: null, then `atOrBelow`, then
   `leavesARequiredAxisUnsaid`. The last exists because `atOrBelow` cannot see
   a dropped axis — a label that stops mentioning an axis is at or below
   everything, including the label it came from.

**Erasing**

1. A value not held is a recorded no-op returning 0.
2. `mayErase` is consulted; a policy that throws is a recorded refusal.
3. The deletions and the ERASE lines are written in **one transaction**.
   Splitting them produces a permanent, unrepairable tamper alarm for something
   nobody did.

### The out-parameters

`refused` and `because` are `AtomicReference` out-params whose values become
the refusal's audit line. Their timing is observable behaviour, not plumbing:
what they hold when the refusal is written is what an auditor later reads.

In `Deriving`:

- `refused.set(joined)` happens per iteration, **after** the join, so a refusal
  at position *k* reports the join of parents 0..*k* and never more.
- `because.set(...)` is set on exactly three paths: a parent above the ceiling,
  a relabel not below, and a relabel leaving a required axis unsaid. On no
  others.

In `Asking`, `because` is set on one path: the subject above the ceiling.

These are per-operation contracts. Moving each operation into its own class is
what makes them separately checkable, rather than facts spread across one
858-line file.

## How we prove nothing changed

Four mechanisms, strongest last.

**The lists above.** A reviewer walks them against the new code. This is why
they are enumerated rather than described.

**The existing suite as an independent net.** loch-core is at 99.8% line and
99.7% branch coverage, and that suite was written *before* this refactor. It
encodes current behaviour without knowing the intended structure. Any test that
needs changing to make the refactor pass is a finding to surface, not a test to
edit.

**The released 0.1.0 jar as an oracle.** Because this lands after the release,
the published artifact and the rebuilt one can be run against identical inputs
and their audit trails diffed. This replaces reasoning about equivalence with
measuring it, and is the reason the work was scheduled after the release rather
than before.

**A Fable adversarial review** of the whole diff before it merges.

## Sequencing

1. Promote `Configuration` to a top-level package-private record. Pure move,
   breaks the cycle, no new vocabulary. Independently mergeable.
2. Extract `Gate` and `Trail`.
3. Extract the five operations, largest last: `Erasing`, `Concealing`,
   `Revealing`, `Asking`, `Deriving`. Each independently mergeable and
   independently reviewable.
4. Delete `Engine`; `DefaultCharter.Active` holds the five.
5. Extract `Portals` from `DefaultCharter`.
6. Re-measure. Remove the won't-fix on `Engine`'s S6539, which no longer has a
   class to attach to. Leave `DefaultCharter`'s, with the reason updated to
   name `Charter`'s width.

Steps 1 and 2 are mechanical. Step 3 is where the risk is, and `Deriving` is
last because it is ~300 lines, seven methods and four records.

## Open

- Whether `DefaultCharter` at ~21 is worth the churn on its own, or whether
  steps 1–4 are the real value and step 5 should be judged separately once the
  `Engine` split is visible.
- Whether narrowing `Charter` is worth its own design round later. It is the
  only thing that would put `DefaultCharter` under the limit honestly.
