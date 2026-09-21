# Axes as the label

**Status:** design, not yet built. Supersedes the user-defined label type described in
`2026-09-21-minted-capabilities-design.md` and in the README's lattice section.

## The problem

A store is generic over `A`, the application's label type — `BillingLabels` in the example, a record
with three components. Everything downstream inherits the parameter. Measured: **18 types carry it,
across 65 public signatures.** `SurrogateStoreConfig<A, D>`, `DerivationSpec<A, O>`, `Storage<A>`,
`StoredValue<A>`, `StoredMetadata<A>`, `JdbcStorage<A>`, `Destinations`, `Lattices`, both store
implementations.

What that parameter buys is compile-time proof that a label mentions every axis, because the record's
canonical constructor takes one argument per axis. What it costs is a type parameter threaded through
the entire library. The trade would be defensible if labels were built at runtime by arbitrary code.
They are not: every label in an application is written in one configuration class, at startup, by the
person who declared the axes. Configuration-time correctness is the cheapest kind to check by other
means.

That is the ergonomic argument, and on its own it would not be enough. The real one is that the
current shape cannot express something applications need.

### The thing that cannot be said

A ceiling today is a single point in the product lattice, and a value passes when it sits at or below
that point on every axis simultaneously. That works until a reader wants breadth on one axis and not
the others.

The case is an internal reporting job: entitled to read **any tenant**, but only `ENDORSED` data, and
only up to `ORDINARY` sensitivity. There is no label that denotes it. From `Exact`:

> There is no ceiling meaning "any single tenant, but not a mixture". That set is not of the form
> `{x : x ⊑ c}` for any `c` — the only `c` above every value is the conflict itself, and a ceiling of
> conflict admits conflicts too, which is the opposite of what anyone wants.

The set is not downward-closed, so no point denotes it. This is a fact about the lattice, not a gap in
the API, and no amount of rearranging the current shape fixes it.

So the library offers `acceptingAnything()`, which skips the ceiling check entirely. That is breadth
on *all three* axes to obtain breadth on one, and it admits conflicted values — the single thing the
tenant axis exists to make unreachable. Every use of it in the repository is a test, which reads like
test convenience and is not: the tests are standing in for a shape the API cannot express.

Both problems have the same cause. **A label is treated as one opaque value of the application's
choosing, when what it is is a set of independent per-axis facts.**

## The change

`Axis<T>` becomes a standalone object with an identity, rather than a projection out of a label type.
A label is the axis-keyed collection of values. `A` is deleted.

```java
// declared once, as constants — these are the schema
public static final Axis<String>      TENANT      = Axis.matching("tenant").required();
public static final Axis<Integrity>   INTEGRITY   = Axis.ladder("integrity", ENDORSED, UNENDORSED);
public static final Axis<Sensitivity> SENSITIVITY =
    Axis.ladder("sensitivity", ORDINARY, PERSONAL, CARDHOLDER);

// the store is told its axes, and nothing else about labels
new SurrogateStoreConfig<Domain.BillingValue>()
    .axes(TENANT, INTEGRITY, SENSITIVITY);
```

`SurrogateStoreConfig<A, D>` becomes `SurrogateStoreConfig<D>`. `Storage<A>` becomes `Storage`.
`labelType(Class<A>)` is deleted outright, along with the error message that exists only to complain
when it was not called.

### Axes come in kinds

An axis is not a name bolted to a `Lattice`. The four orders the library offers today — `ladder`,
`ranked`, `exact`, `setUnion` — are assembled by hand out of `Lattices.axis(read, lattice)`, which
means an application wires up a tenant axis the same way it wires up a ladder and the library learns
nothing from the difference. Under this design each kind is its own factory, and each one knows its
own rules:

| kind | combining two values | what `any()` admits |
|---|---|---|
| `ladder` / `ranked` | the more restrictive rung | anything; no poisoned value exists |
| `matching` | **a mixture** | any one value, never a mixture |
| `categories` | the union | any set |

This is what makes breadth statable without a general-purpose escape hatch. An earlier draft of this
document proposed `Lattice.usable(T)` — a method on every lattice so that one axis could declare its
values poisoned. That is withdrawn. Poison is a property of the matching kind, and only the matching
kind needs to know about it.

### A mixture is a value only the system can make

Because `Axis.matching` is typed in the application's own value — `Axis<String>`, not
`Axis<Exact<String>>` — `Exact` stops being a type applications write. A label is built with
`Label.of(TENANT, "acme")`. There is no public constructor anywhere that produces a mixture; it
arises only from joining two labels that disagree, inside the engine.

That upgrades the central guarantee from convention to construction. Today `Exact` is a public record
with a public canonical constructor, so "no ceiling admits a conflict" holds as long as nobody builds
a conflicted `Exact` and hands it somewhere as a ceiling. Afterwards, the sentence is true because the
value cannot be spoken.


### A label

An immutable map from axis to value, built the only way it can be built:

```java
Label label = Label.of(TENANT, tenant)
                   .and(INTEGRITY, UNENDORSED)
                   .and(SENSITIVITY, PERSONAL);
```

**A label has no getter.** You build one, you modify one, and the engine renders one. Nothing reads a
component back out, which was measured rather than assumed: of the eight `lowering` lambdas in the
repository, seven are pure writes (`joined.withIntegrity(ENDORSED)`), and the eighth reads only
because it hand-rolls a wither by calling a record constructor directly. Under `Label` that becomes
`joined.with(DATA_CLASS, PII)` and the read disappears.

```java
Label lowered = joined.with(INTEGRITY, ENDORSED);
```

So the public surface is `of`, `and`, `with`, and a rendering for the manifest and the audit line.
`SurrogateStore.label(id)` — administrative, never published as a bean — hands back a `Label` whose
only use is being rendered.

This matters beyond convenience. A typed getter would have forced an answer to "what does a matching
axis return when it holds a mixture?", where `T` is wrong because it may be mixed and `Optional<T>` is
wrong because empty would mean either mixed or unsaid. Not having the getter means not having the
question, and it keeps labels opaque to application code, which is the stance the rest of the library
already takes.

Joining two labels is componentwise over the store's declared axes, which is what `Lattices.product`
already does; it stops being generated per arity and becomes a loop.

### A ceiling

This is the part that changes meaning rather than shape. A ceiling stops being a point and becomes a
**constraint per axis**:

```java
// the payment processor: a point on every axis, as today
Ceiling.of(TENANT, atMost(tenant))
       .and(INTEGRITY, atMost(ENDORSED))
       .and(SENSITIVITY, atMost(CARDHOLDER));

// the reporting job: breadth on exactly the axis that needs it
Ceiling.of(TENANT, any())
       .and(INTEGRITY, atMost(ENDORSED))
       .and(SENSITIVITY, atMost(ORDINARY));
```

Two constraints, and every axis must state one. Silence is not permission: a ceiling that omits a
declared axis is refused at configuration, which preserves the property the record's constructor used
to give for free.

`acceptingAnything()` is deleted. It has no meaning once breadth is per-axis, and its only honest
reading — "skip all checks" — is not an authority anybody should be able to declare in one word.

### Why `any()` on a matching axis is safe

This is the load-bearing rule, so here is the reasoning rather than the assertion. The reporting job
has no tenant on its context; it reads acme's row, then globex's row, then combines them. Each
individual read is legitimate — it is entitled to any one tenant's data. The combination is where the
leak would be, and the combination already produces a mixture, which reaches nobody. Blocking the
reads would be blocking the wrong thing; admitting the mixture to the reader would be blocking
nothing.

So `any()` on a matching axis means *any one value*, and a mixture is not one value. `atMost` is
unaffected: a mixture was never below any single value and still is not.

### Declassification

`lowering` keeps its meaning and its guard. `UnaryOperator<A>` becomes `UnaryOperator<Label>`, and the
check stays "the result is at or below the join of the parents, on every axis." Endorsement still
works by moving down the integrity ladder, for the same reason it does today.

## Storage

Cheaper than expected, because the audit side is already decoupled.

**`AuditRecord` needs no change.** Its label is already `Optional<String>`, stringified before it
arrives. Only the producer in `DefaultSurrogateStore` changes, and only to render a `Label` instead of
an `A`.

**The schema needs no change.** Both `loch_value.label` and `loch_audit.label` are `BYTEA`. A per-axis
label is still one blob.

**The serialization path is a single thread, and it gets shorter.** Today:

```java
this.labels = codecs.create(labelType).andThen(storageCodec);   // JdbcStorage:118
```

`labelType` arrives from `SurrogateStoreConfig.labelType(Class<A>)` and exists for no other purpose.
A heterogeneous map is not something a `CodecFactory` can serialize by reflecting on a class, so each
axis carries its own encoding — `Axis` gains a `String` codec, trivial for enums and two components
for `Exact`. A label serializes as an axis-name-keyed map of strings, through the same `StorageCodec`
pipeline as today.

That is a migration improvement, not just a rearrangement. **Adding an axis stops invalidating stored
labels.** Today a fourth component on `BillingLabels` breaks the decode of every row already written;
with names in the blob, a row written before an axis existed simply says nothing about it, and
saying nothing is a thing a label can already do -- bottom, or refused by the `required` rule if that
matters.

The other direction is not symmetrical, and an earlier draft of this document had it wrong by calling
an unknown axis "ignorable". **A row labelled on an axis the store no longer declares is refused.**
The row was written under a constraint; dropping it silently would make the value readable by more
than it was ever labelled for, which is a control that stops working without anything failing.

`MemoryStorage` holds references and never serializes, so it loses a type parameter and nothing else.

## What this costs

- **`BillingLabels` stops existing.** With it goes "an axis cannot go missing: the constructor takes
  as many arguments as there are axes," which that file's javadoc names as a virtue. Replaced by two
  checks that run at configuration: a label must mention every declared axis, and a ceiling must
  constrain every declared axis. Both are startup failures, not request-time ones.
- **`Axis.required()` carries more weight.** It already exists, and the `INCOMPLETE_LABEL` refusal
  already exists, because a bottom tenant is readable by everyone and that was a hazard with the
  record too. This change does not create that problem; it removes the compile-time backstop that
  partially masked it.
- **Unchecked casts appear**, confined to `Label` and `Ceiling`. Standard typesafe heterogeneous
  container. The project forbids suppressing warnings, so these must be genuinely safe by
  construction rather than annotated away.
- **`Lattices.product` and its arity overloads are deleted**, along with `Of2`…`Of5`. Joining becomes
  a loop over declared axes. This is a net deletion.
- **Every ceiling in every test and the example is rewritten.** The `label(ctx, ...)` helper in the
  example becomes a `Label` builder; six ceilings change shape.

## What this does not fix

**Ambient identity is still only as good as its source.** Unchanged, and worth repeating because
per-axis ceilings make `TENANT` read more prominent, not more trustworthy. If no gateway strips
`X-Tenant`, a caller picks their own tenant and every axis agrees with them.

**Sensitivity is not always a property of a single value.** A name may be ordinary alone and personal
beside a birthdate, and a per-value label cannot say so: the join combines labels, not information
content, so two ordinary parents produce an ordinary result however identifying the pair is. This is
the inference problem, and it is a limitation of lattice-based flow control generally rather than of
this implementation — measuring what a combination reveals is what quantitative information flow is
for, and it is a different model.

An operator letting a derivation raise its output above the join was considered and rejected. The
output is a function of its inputs and contains nothing they did not, so a result that deserves a
higher label is evidence the inputs were labelled too low at the source. Raising would be an
unverifiable assertion papering over a minting decision, and this library does not ship knobs that
document good intentions.

**Endorsement remains unverifiable.** The lattice proves a relabel moved down. Nothing proves it
deserved to. Same status as `hold`.

## Open questions

1. **Ordering of work.** This change rewrites signatures that the pending `mint()`/`checking` cleanup
   also touches. Doing the cleanup first means doing parts of it twice; doing this first means the
   cleanup lands into a moved API. Recommend this first, cleanup folded into it.
2. **`Exact`'s javadoc says "dimension"** where it means axis. One word, two spellings, settle it.
