# The charter

**Status:** design, not yet built. Supersedes `2026-09-21-specification-and-binding-design.md` and
completes the naming left open in `2026-09-21-axes-as-the-label-design.md`, which is built.

## The thing being named

An application declares, once, every way a sensitive value may enter, leave, or be transformed.
Declaring one hands back an object, and holding that object is the only way to perform that
operation. There is no registry and no lookup by name.

That object which does the declaring has been called a "store", and a store is the wrong idea. It
does three things and only the last is storage:

- it **holds the axes** — the questions this application asks about every value it keeps
- it **issues the portals** — the only means of acting, handed out at configuration
- it **records every crossing** — in full, whether permitted or refused

It is called a **`Charter`**.

A charter is the instrument that founds a body, enumerates the powers it has, and vests them. Not a
description of a thing that exists independently — the charter is what brings it into being, and
what it does not enumerate does not exist. That is the object-capability rule in older language:
there is no general authority to be narrowed, only the powers named, and everything else is absent
rather than forbidden.

An earlier draft called this a `Gatehouse`, on the objection that a document should not
manufacture machinery. That objection dissolves under the next section: the portals are not artifacts a
specification emits, they *are* the specification in executable form — the way a charter both
constitutes a body and vests authority in its officers.

What it does is Stiegler's **powerbox**: a trusted component holding broad authority that hands out
narrowed capabilities, with no way to obtain one except to be given it.

The vocabulary already in the code stays, because the words do not collide:

- a **gate** is the check — the moment something is allowed through or refused
- a **portal** is what an application holds that puts it at a gate
- the **charter** constitutes portals, keeps the register, and renders its `manifest()`

## The portals are named for what they do

Three of the five already are, and each has a verb to match: `Derivation.derive`, `Fold.fold`,
`Query.ask`. Two were named for their position in a dataflow, and shared one verb pointing in
opposite directions -- `SurrogateSource.exchange(value)` and `SurrogateSink.exchange(surrogate)`,
where nothing at the call site says which way the value is going.

| direction | type | verb | returns |
|---|---|---|---|
| in | `Conceal<T>` | `conceal(value)` | `Surrogate<T>` |
| out | `Reveal<T>` | `reveal(surrogate)` | `Revealed<T>` |
| | `Derivation<I, O>` | `derive(...)` | `Derived<O>` |
| | `Fold<I, O>` | `fold(...)` | `Derived<O>` |
| | `Query<I, Q>` | `ask(...)` | `Answer` |
| | `Erasure` | `erase(...)` | see open question 6 |

The `Surrogate` prefix drops from both: a surrogate is what they trade in, not what they are. Each
is left with a single method, since the `AccessContext` overloads go with `callerMayContribute`.

`Dereferenced<T>` becomes **`Revealed<T>`**, keeping its `Allowed` / `Denied` arms. Every other
outcome type is the past participle of the verb that produces it -- `derive` gives `Derived` -- and
this was the only one still named after an operation that no longer exists.

`Surrogate<T>` does **not** become `Concealed<T>`, and the two are not meant to be antonyms. They
are different kinds of thing: a surrogate is a value that travels, serialises and sits in a field,
while `Revealed`, `Derived` and `Answer` are outcomes, consumed where they are produced.

That leaves a deliberate asymmetry worth stating rather than inheriting:

```java
conceal(value)     -> Surrogate<T>     // refuses by throwing
reveal(surrogate)  -> Revealed<T>      // refuses by returning Denied
```

Concealing fails in exactly two ways -- a label that leaves a required axis unsaid, and a labelling
function that could not decide -- and both are configuration bugs rather than routine outcomes, so
they belong in an exception. Refusing a read is ordinary operation and deserves a value. Adding a
`Concealed<T>` for symmetry would make every write site unwrap a result that is almost never
anything but success.

## Two faces, one object

The friction that held this design up for a while: a charter is a *specification*, and yet the
things it emits are *live operational objects*. A document does not manufacture machinery.

The resolution is that this is not a document. The portals are not artifacts a specification happens
to emit; they **are** the specification, in executable form. Holding a `Reveal<Invoice>` is
what it means for "this code may read invoices at this ceiling" to be true of you. The charter is a
*rendering* of the same facts, which is why it needs no storage to produce.

So there are two faces and one object: what it permits, and what it issues. "Store" saw only the
second.

## The lifecycle

This is the heart of the design.

Two things must be true, and there must be no state in which both are allowed:

1. While the charter is being configured, portals may be **declared** but not **exercised**.
2. Once it is sealed, portals may be **exercised** but never **declared**.

```
CONFIGURING  ──────────────────►  ACTIVE
                seal(storage)
```

### One shared lifecycle coordinate

Every portal a charter issues shares **one** reference to that charter's state:

```java
sealed interface State {
  record Configuring(Configuration configuration) implements State {}
  record Active(Configuration configuration, Engine engine) implements State {}
}
```

A portal holds its own definition and that shared reference, and nothing else:

```java
final class Reveal<T> {
  private final SinkDefinition<T> definition;
  private final AtomicReference<State> lifecycle;

  public Revealed<T> reveal(Surrogate<T> value) {
    return engine().reveal(definition, value);
  }

  private Engine engine() {
    return switch (lifecycle.get()) {
      case Active(_, var engine) -> engine;
      case Configuring _ -> throw new IllegalStateException(
          "this portal cannot be exercised before its charter is sealed");
    };
  }
}
```

**Sealing does not activate each portal. It changes the state of the domain they all belong to.**
There is no walk, nothing to install, and no portal that could be missed.

### What this replaces, and a bug it fixes

Today there are N `Binding` objects — one per portal — and sealing walks them:

```java
bindings.forEach(binding -> binding.attach(store));      // SurrogateStoreConfig:438
```

Each one holds a mutable field:

```java
private DefaultSurrogateStore store;                     // not final, not volatile
private void attach(DefaultSurrogateStore store) { this.store = store; }
```

That field is written by whichever thread seals and read by whichever thread later exercises a
portal, with no happens-before edge established by this library. It works today only because
Spring's singleton publication happens to supply one. An `AtomicReference` makes the guarantee ours
rather than borrowed — a second reason for this design that has nothing to do with elegance.

`Binding` is deleted.

### Declaring is itself a transition

Declaration must not race sealing. A naive check-then-add allows this:

```
thread A                    thread B
checks: not sealed
                            seal(storage)
adds definition                              <-- authority created after sealing
```

So the configuration is part of the atomic state, and declaring swaps one immutable snapshot for
another:

```java
private <P> P declare(Function<Configuration, Declaration<P>> declaration) {
  State current = lifecycle.get();
  if (!(current instanceof Configuring configuring)) {
    throw new CharterSealedException("...cannot declare after sealing");
  }
  Declaration<P> result = declaration.apply(configuring.configuration());
  if (!lifecycle.compareAndSet(current, new Configuring(result.configuration()))) {
    throw new ConcurrentDeclarationException("...declared while being sealed");
  }
  return result.portal();
}
```

**Fail, never retry.** Concurrent bootstrap mutation is a programming error, not contention worth
absorbing.

This copies the configuration once per declaration, which is quadratic in the number of portals.
At dozens of portals that is nothing, and the comment saying so belongs in the code — otherwise
somebody will "optimise" it back into a mutable map and reopen the race.

### Declaring after sealing

Rejected immediately and loudly. This is a security invariant, not an ergonomic one: once active,
the authority graph must be incapable of growing.

There is no `unseal()`, no `rebind()`, no way to replace the storage. The transition is
irreversible for the lifetime of the object.

It is also strictly better than what is built today, and supersedes an earlier ruling. Today a
portal minted after binding fails at *first use*, because it was never attached. Failing at
*declaration* means finding out at startup rather than at whichever request first reaches the
forged portal.

## Axes are identity, so they are constructor arguments

A charter cannot exist without its axes. They decide what a label is even able to say and what a
stored row is decoded against, so they are not configuration — they are what this charter *is*.

```java
Charter charter = new Charter(TENANT, INTEGRITY, SENSITIVITY);
```

Three things follow. The error that exists only because axes can be absent is deleted:

```java
throw new IllegalStateException("a store needs axes: call axes(...) ...");
```

The accessor gets its name back — it is currently called `declaredAxes()` for one reason, that a
public varargs setter `axes(Axis<?>...)` swallowed a no-argument `axes()` call. Remove the setter
and `axes()` means what it should.

And the axes sit outside the mutable state entirely: a final field beside the `AtomicReference`,
never copied into a snapshot, never replaced. Duplicate-name validation moves to the constructor,
where it fails before anything can be declared against a broken scheme.

## Sealing is not public API

If `seal` is on the public surface, any code holding a charter can bring it into force — the
ambient authority this design deletes everywhere else.

Java has no friend access across jars, so hiding it costs a cast, a second interface, or a
capability object — unless the problem is dissolved by **ownership**. Whoever constructs the
charter holds the concrete type, so infrastructure should construct it and the application should
only ever receive the declaring face:

```java
// application — declares, and is handed only what declares
@Bean
public DisputeService disputeService(Charter charter, Invoices invoices) {
  Conceal<Mail> customerMail         = charter.source("customer-mail", MAIL, ...);
  Reveal<Invoice> supportUi          = charter.destination("support-ui", ...).reading(INVOICE);
  return new DisputeService(customerMail, supportUi, ...);
}
```

The autoconfiguration constructs the charter from the application's axes, injects it, and seals
the instance it still holds once every bean is built. The application cannot express sealing,
because it never holds anything that can.

Outside Spring the same inversion is a callback, which makes the lifecycle impossible to misuse:

```java
Charter.run(storage, charter -> {
  customerMail = charter.source(...);
  supportUi    = charter.destination(...).reading(INVOICE);
});
// sealed on return
```

## What sealing binds to

Storage, and nothing grander. A dedicated runtime type was considered and dropped: what sealing needs is
a way to fetch and keep the values behind surrogates and the audit lines, which is the existing
`Storage` SPI — four verbs plus existence and erasure. Renaming an honest interface to something
larger than it is would be the opposite of what this redesign is for.

The engine — the thing that orchestrates fetch, decide, read plaintext, decide again, record — is
constructed **at the moment of sealing**, from the configuration and the storage, because that is
the only moment both exist. It needs no public name.

## Decisions: admit and settle

A decision is not one thing, because the impure step is in the middle rather than at the end:

```
admit   — labels only, never sees plaintext
            ↓
      [read the value, run the application's function]
            ↓
settle  — is the relabel genuinely below the join?
```

Every operation fits, with pieces omitted rather than reshaped:

| operation | admit | plaintext | settle |
|---|---|---|---|
| source | is the label complete? | — | — |
| sink | type matches, label under ceiling | read it | — |
| query | type matches, label under ceiling | read it, run the predicate | — |
| derivation / fold | types match, every parent under ceiling, join | read them, run the function | relabel below the join? |
| erase | may this context erase a value labelled so? | — | — |

**A decision produces its audit record.** Not "and then remember to write one" — the decision hands
one back and the engine's job is to persist it. Earlier in this library's life, policies that threw
escaped `ask` and `derive` unaudited: the gate had decided, the caller got an exception, and nothing
was written down. It was fixed by hand with wrapper helpers. Returning the record makes that bug
unrepresentable rather than fixed.

## Refusals are recorded, never explained

Today this is inverted. The audit gets the coarse code and the caller gets the detail:

```java
AuditRecord.Outcome.REFUSED,
refused.reason().name(),           // "ABOVE_CEILING"
label.get(),
```

while `Dereferenced.Denied.detail()` carries "labelled {tenant=acme, dataClass=PII}; accepts
{...}" — gated behind an undocumented `explainRefusals()` toggle.

Swap them:

| | today | after |
|---|---|---|
| caller | full explanation, if toggled on | which portal, coarse reason, always |
| audit | coarse code only | the full explanation, always |

**Why the caller must never be told.** A refusal message is an oracle. Code that cannot read a value
can still learn its classification by trying, and enough attempts reconstruct the labelling without
a single permitted read. That is the tracker-attack shape (Denning, Denning & Schwartz, 1979) this
design already cites elsewhere.

`explainRefusals()` disappears, because with the detail off the caller's path there is nothing left
to toggle.

**No logging in core.** `loch-core` and `loch-api` have no logging dependency today and should not
acquire one. A log line is the same disclosure to a different audience: audit labels are encrypted
through the `StorageCodec`; log aggregators are readable by anyone with a dashboard. Observability
should emit that a refusal happened — portal, time, coarse reason — and read the detail from the
audit, where it is protected.

**Consequence to not forget:** putting the ceiling into `AuditRecord.reason` means that column
starts carrying label-shaped data. It needs the same protection the label column already has. Cheap
to do, easy to miss.

## The surface after this

**Constructor:** the axes.

**Kept:** `currentAccess` (defaulted to the empty provider, and renamed from the field's `ambient` —
that word only ever meant "as opposed to the caller's context", which no longer exists), `mayErase`,
the declaration methods, `manifest()`, reachability.

**Deleted:**

| gone | why |
|---|---|
| `SurrogateStore`, `JdbcSurrogateStore`, `MemorySurrogateStore` | the type is referenced nowhere in main code; it was built and discarded for its side effect |
| `SurrogateStoreConfig` | becomes `Charter` |
| `Binding` | one shared state reference replaces N objects and the walk |
| the domain bound `D` | 23 of 36 call sites set it to `Object`; name collisions are caught at declaration regardless |
| `explainRefusals` | nothing left to toggle |
| `callerMayContribute`, `AccessContext.contributedTo`, the explicit-context overloads | a second identity channel, silently inert by default, and expressible as an `AccessContextProvider` the application writes itself |

## What this does not fix

**Ambient identity is still only as good as its source.** Whatever the provider returns is taken as
fact. If no gateway strips `X-Tenant`, a caller picks their own tenant and every ceiling agrees.

**Endorsement remains unverifiable.** The lattice proves a relabel moved down. Nothing proves it
deserved to.

**Unforgeability is bounded by the language.** A portal whose only implementation is package-private
is unforgeable against ordinary application code, not against reflection.

## Open questions

1. **The clock and the identifiers have no home.** `Instant.now()` and `UUID.randomUUID()` are
   hardcoded in the engine, so no test can pin a clock or get deterministic ids. Storage could mint
   ids, which would also let JDBC use a sequence or UUIDv7 — time-ordered, which is what an
   append-only audit table scanned by time wants, and the house convention. The clock still has
   nowhere obvious to live.

2. **What does `seal` validate?** Name collisions already fail at declaration; reachability is a
   report rather than a startup failure. Either the list is explicit and short, or sealing validates
   nothing and only transitions — which is cleaner than a vague "validate it".

3. **How the application hands over its axes** under ownership inversion. The charter needs them
   before it exists, so either a small carrier type or the autoconfiguration collects `Axis<?>`
   beans. Needs a name either way.

4. **Whether a bare JDK type may be declared.** Deleting the domain bound means
   `SurrogateType.of(String.class)` compiles again. A declaration-time refusal of primitives, boxes,
   `String` and JDK collections would catch the sloppiness the bound was aimed at, uniformly and
   without generics.

5. **`admit` and `settle`** are working titles for the two halves of a decision.

6. **Erasure has no capability.** Every other operation became "hold the portal or you cannot say
   it". This one is still a method on `SurrogateStore` -- the type referenced nowhere in main code
   -- guarded by `BiPredicate<Label, AccessContext> mayErase` against ambient context. It is the
   last place authority is checked rather than held, and it is only reachable from tests today,
   which is why nobody noticed. It should be a portal like the others, at which point `mayErase`
   disappears and "which code can destroy customer data" is answered the same way every other
   authority question is.
