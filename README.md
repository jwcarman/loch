# Loch

A labelled value store for Java. Opaque handles, labels that travel with the data, and policy at
every sink. Built on Denning's lattice model.

> **Status: early.** The lattice, the handle and the gate work and are tested. Derivation, lineage,
> durable storage and encryption are not written yet.

## The problem

Some values should not simply become text: credentials, personal and regulated data, untrusted
input from outside, anything a compliance regime has opinions about. Once such a value is a `String`
in your application, nothing can tell you where it went.

Loch takes custody of the value and gives you a handle. The handle can go anywhere — an event
stream, a log line, a prompt, a message to another service — because holding one is not permission
to read it. Turning it back into a value is the one checked operation, and it always names where
the value is going.

```java
Held<String> body = loch.hold(message.body(), String.class, new Billing(ACME, UNENDORSED, AMBER, PII));

loch.dereference(body, VENDOR_LLM, onBehalfOf(ACME));       // Denied: above ceiling
loch.dereference(body, QUARANTINED_LLM, onBehalfOf(ACME));  // Allowed
```

## What it rests on

**A value's class is not its type.** `List.of(a, b).getClass()` is `ImmutableCollections$List12`,
which nothing can deserialise into, so a loch that guessed from the object would hand back a handle
it could never honour. The caller says what a value is, and `TypeRef` says it precisely:

```java
loch.hold(cards, TypeRef.listOf(TypeRef.of(Card.class)), attribution);
```

**Possession is not authority.** Holding a handle does not permit reading it.

**Presentation is not attribution.** A handle asserts identity only. Labels come from the store —
otherwise anything that could write an id could claim to be trusted.

**A handle names a value; it does not describe how to make one.** No parents, no recipe. If an id
described a computation, presenting one would be a request to perform it, and ids arrive from
untrusted places. So **lookup never computes**: an id is found, or refused.

**What travels is the id.** A `Held<T>` is a *local typed view* — its `TypeRef` is a claim the gate
checks against what the store actually wrote, which makes it worth having where code uses a value
and worth nothing on a wire. Events, messages and rows carry a `HeldId`, which every serialiser can
manage without being taught anything, and the receiving side says what it expects:

```java
record InboundMail(String from, HeldId body) {}          // goes anywhere

Held<String> body = Held.of(event.body(), String.class); // typed again where it is used
```

A handle also prints as its id and nothing else. The Java type is a local matter, and whether a
model may be told anything about a value is a policy question for a renderer to ask the loch — not
a decision a `toString` should make on everybody's behalf.

**There is no dereference without a destination.** No overload omits it. You cannot obtain plaintext
"in general" — only plaintext for somewhere, and that somewhere is what policy decides on and what
an audit records.

## You supply the labels

Loch has no opinion about what `RESTRICTED` means, and ships no mandatory vocabulary. In US
government nomenclature CONFIDENTIAL sits *below* SECRET; in the common corporate convention it sits
near the top. No regulated organisation will abandon its mandated scheme because a library has
opinions.

What Loch needs is an *order*: one application-defined record, and a `Lattice` over it.

```java
record Billing(Exact<String> tenant, Integrity integrity, Tlp tlp, DataClass dataClass) {}
```

Every lattice is oriented so **up means more constrained**. Implement `join` and `bottom`; the
order comes free, because in a join-semilattice `a ⊑ b` holds exactly when `a ⊔ b = b`. Two things
that must agree cannot disagree if only one of them exists.

That orientation is what makes the safety rule a consequence rather than a check: a derived value's
label is the join of its parents', join is monotone, and therefore **ordinary derivation cannot
weaken a label**. Not "must not" — cannot.

## The laws are executable

`Lattice` has laws, so `LatticeTck` property-tests yours: associativity, commutativity, idempotence,
identity, that `permits` agrees with `join`, and value semantics.

The last two matter most. `permits` is defined through `equals`, so a label type with identity-based
equality doesn't fail loudly — it silently permits everything, or silently permits nothing.

```java
class ClearanceLatticeTest extends LatticeTck<Clearance> {
  protected Lattice<Clearance> lattice() {
    return Lattices.ladder(PUBLIC, EMPLOYEE, MANAGEMENT, EXECUTIVE);
  }
  protected List<Clearance> samples() { return List.of(Clearance.values()); }
}
```

## No footguns in the ordering

There is deliberately **no** factory that reads `Enum::ordinal`. Declaration order is a terrible
place to keep a security-relevant contract: someone sorts a list of constants alphabetically in an
unrelated tidy-up, every test still passes, and the gate now permits the opposite of what it should.
Nothing about an enum declaration says "the order of these lines is load-bearing".

So you say the order where a reviewer will see it, and every constant must appear:

```java
Lattices.ladder(PUBLIC, INTERNAL, CONFIDENTIAL, SECRET)   // or Lattices.ranked(..., Impact::level)
```

## Durable, encrypted, and erasable

`loch-jdbc` keeps values in Postgres. It contains **no cryptography and no compression of its own**
— you compose the pipeline and it applies what it is handed:

```java
Loch<Billing> loch = JdbcLoch.create(Billing.class, c -> c
    .dataSource(dataSource)
    .codecs(new JacksonCodecFactory(objectMapper))          // serialise
    .storedThrough(StorageCodec.of(                          // then your byte pipeline
        Compression.whenItHelps(new GzipCodec())
            .andThen(EnvelopeCodec.builder(keys).build())))
    .lattice(BILLING)
    .auditor(auditSink)
    .destination(...));
```

So `loch-jdbc` depends on `loch-core` and the codec **contract**, and nothing else. Which
serialisation, which compression and which encryption are yours to pick — Jackson or fory or
protobuf, gzip or zstd or lz4, envelope encryption or your own KMS. `StorageCodec` exists so the
composed pipeline is a nameable thing an application can declare and a container can inject.

**Compression before encryption**, always: ciphertext does not compress, so the other order costs
the same and saves nothing.

**Compression should be conditional, because measurement says so.** Most of what a loch holds is
small, and a compressor's framing costs more than a short payload saves:

```
{"number":"4111111111114821","holder":"J CARMAN"}   49 bytes -> gzip 57   BIGGER
{"v":"4821"}                                        12 bytes -> gzip 32   BIGGER
an email body                                      851 bytes -> gzip 79   smaller
```

`Compression.whenItHelps(...)` wraps any compressor, keeps the result only when it actually shrank,
and records which with one leading byte. Worst case is one byte instead of a threefold expansion.

**The label is encrypted but not compressed.** A tenant's name in the clear beside the ciphertext
describes what the ciphertext is; and labels are short, structured and guessable, which is where
compress-then-encrypt leaks most.

**Decompression is bounded.** `CompressionStreamCodec` caps the decoded size, so a corrupt or
malicious row cannot expand into an out-of-memory error.

**Erasure is a reachability query.** Lineage is kept as values are derived, so erasing a value takes
everything ever made from it in one indexed statement. Lineage is a DAG rather than a tree, so this
is a closure table rather than a materialised path, whose rows would multiply at every merge.

Anything Loch stores must round-trip through your codec, which is why its own label types are plain
records rather than sealed hierarchies: a sealed type needs polymorphic type information that every
codec has to be told about separately, and `loch-core` depends on nothing and so cannot annotate
itself for any of them.

## Combining values, and the thing that makes it matter

```java
Held<Report> report = loch.deriveAll(List.of(acmeNote, globexNote), SUMMARISE);
```

The result carries the join of **every** parent's label. Nobody marked anything as conflicted:
somebody wrote a perfectly reasonable summariser and gave it perfectly reasonable inputs. But an
exact-match dimension like tenant joins two different values to a conflict, and a conflict is below
no ceiling — so the report exists, remembers both parents, and **cannot be dereferenced anywhere, by
anyone, ever**.

That is the difference between a rule and a property. Nothing had to be remembered.

The same arithmetic does the quieter, more common job: fold one ordinary note with one containing a
home address and the result is `PII`, so it reaches the quarantined model and not the vendor's.

## Who is asking

Identity is known at the edge and needed at the gate, which may be many layers down. Threading an
`AccessContext` through all of them would make the safety feature the most annoying thing in the
codebase, and annoying safety features get routed around. So say once where the answer lives:

```java
.askingWhoIsAsking(() -> AccessContext.of(Map.of(
    "tenant", CurrentTenant.get(),
    "principal", SecurityContextHolder.getContext().getAuthentication().getName())))
```

A `ThreadLocal`, a `ScopedValue`, Spring's holders — Loch has no opinion about how your request
scope works, and an application with no notion of identity says nothing and gets an empty context.

**Ambient wins, always, and a caller may only contribute keys you declared.** If a call site could
override what the edge established, any code holding a loch could name itself whichever tenant it
liked and the gate would agree — which is not a policy system, it is a formality. What a caller
legitimately has is something the edge does not know, such as the purpose of an operation:

```java
.callerMayContribute("purpose", "tool")   // empty by default; never list an identity key
```

Anything else a caller says is ignored. A destination id is not a key to anything: it names *where*
a value is going, and the ceiling attached to it — evaluated against an identity the caller does not
control — is what decides.

## What it will allow, in one printout

`loch.manifest()` is meant to be printed at startup and pasted into a review:

```
loch manifest

  unconstrained label (bottom)
    Billing[tenant=none, integrity=ENDORSED, tlp=CLEAR, dataClass=NONE]

  destinations (4)
    payment-processor  accepts up to Billing[tenant=none, integrity=ENDORSED, tlp=RED, dataClass=CARDHOLDER]
    approval-card      accepts up to Billing[tenant=none, integrity=ENDORSED, tlp=AMBER, dataClass=NONE]
    vendor-llm         accepts up to Billing[tenant=none, integrity=ENDORSED, tlp=CLEAR, dataClass=NONE]
    quarantined-llm    accepts up to Billing[tenant=none, integrity=UNENDORSED, tlp=AMBER, dataClass=PII]
    (ceilings shown for an access naming nobody; some allow more to some callers)

  derivations (4)
    Card.last4.dataClassOnly            String -> Last4, deterministic v1   << WEAKENS LABELS
    DisputeClaim.invoiceNumber.trustMe  DisputeClaim -> InvoiceNumber, deterministic v1   << WEAKENS LABELS
    DisputeClaim.invoiceNumber          DisputeClaim -> InvoiceNumber, deterministic v1
    Card.last4                          String -> Last4, deterministic v1   << WEAKENS LABELS

  checks (1)
    Account.ownedBy  asks about a Account

  3 operation(s) can WEAKEN a label:
    Card.last4.dataClassOnly  String -> Last4, deterministic v1
    DisputeClaim.invoiceNumber.trustMe  DisputeClaim -> InvoiceNumber, deterministic v1
    Card.last4  String -> Last4, deterministic v1
```

Everything else in the design makes labels *more* constrained; the marked operations are the only
things that can make them less. There should be few enough to read in one sitting, and a name like
`invoiceNumber.trustMe` should stop a reviewer dead — which is the point, because **no algebra can
tell you whether a check is strong enough.** An endorsement confirming a record exists looks
identical to one tying it to the person who asked. That judgement is human, so the list exists to
put it in front of a human.

Note `tenant=none` rather than "any": in a value it means nothing was said, and in a ceiling it
means only values that also said nothing may pass. It is the fail-closed case, and "any" would read
as its opposite.

## Every access leaves a record

```java
MemoryLoch.create(c -> c
    .lattice(BILLING)
    .auditor(myAuditSink)      // or .withoutAudit(), in writing
    .destination(...));
```

There is **no default**. A governance control that quietly keeps no record still produces the
report, which is worse than not having one — so which of the two you want is a decision, not an
omission.

An audit line says which value, who asked, where it was going, what was decided and why. It never
says what the value was; otherwise the audit log becomes the largest collection of protected data in
the system and the least protected. It does carry the label, because an audit that cannot say *why*
is not much of an audit — so an audit sink deserves the protection the values do.

Refusals are recorded as carefully as permissions. A thousand refused attempts against one value is
the interesting event, and a log of successes cannot show it. Checks are recorded with the answer
but never the question. Holding is recorded too, since that is the one place labels are asserted
rather than computed.

**And an access that cannot be audited does not happen.** If the auditor throws, the gate refuses.
An application that would rather proceed says so by catching inside its own auditor, which makes
that a decision somebody wrote down.

## Where this sits in the literature

The algebra is **Denning's lattice model** (1976), used the way Denning stated it — parametric over
the lattice, so its theorems hold for whichever one you supply. Integrity as the dual of
confidentiality is **Biba** (1977); orienting every lattice so that *up means more constrained* is
what lets one `join` serve both. Weakening a label is **declassification** and **endorsement** from
the **Decentralized Label Model** (Myers & Liskov, 1997) — the same move in opposite dimensions,
which is why there is one `lowering(...)` rather than two services.

The Java language that does this properly is **Jif** (Myers, originally JFlow, POPL 1999), with
**Paragon** and **JOANA** nearby. Jif puts the label in the *type*, checks statically across the
whole program, and proves non-interference — including **implicit flows**, the control-flow leaks
that dynamic systems miss:

```java
if (secret) { publicFlag = 1; }   // leaks a bit without ever assigning secret
```

**Loch cannot see that, and makes no non-interference claim.** Its real ancestry is dynamic: Perl's
taint mode, PCI tokenization vaults, and — for the agent case — the dual-LLM pattern and CaMeL.

The reason to be dynamic here is not that it is easier. Static IFC assumes you can see the program,
and in an agentic system part of the program *is a language model* whose control flow is chosen at
runtime from text. **You cannot type-check a prompt.** Where Jif's technique is available it is
stronger; at this boundary it is not available at all.

Jif is also a cautionary tale about cost. It is excellent work that saw almost no industrial use —
annotation burden, whole-program analysis, a compiler fork. A weaker guarantee people deploy beats a
stronger one nobody adopts, which is why the design target here is that an application writes almost
nothing.

If your organisation already classifies data — **Microsoft Purview sensitivity labels**, say, which
are ordered by priority and therefore already a ladder — that taxonomy is the lattice. Loch ships no
mandatory vocabulary precisely so it can take yours.

## What it does not do

**Implicit flows.** A tool that dereferences a secret and branches on it leaks through its own
behaviour, invisibly. This is what a static type system buys and a runtime gate cannot.

**Anything after a value is handed back.** That code holds a `String` and can send it anywhere. No
Java library can follow it without bytecode instrumentation. The mitigation is a `Check`, which
answers a question inside the store so the value never leaves:

```java
Account account = loch.dereference(handle, SOMEWHERE).orThrow();   // Loch loses sight of it
boolean ok = loch.check(handle, OWNED_BY, sender).isTrue();        // never leaves
```

The design target is that most callers never dereference anything.

**Protect what you never handed it.** Loch only knows about values you `hold`. Perl's taint mode
taints everything arriving from outside; here the discipline depends on minting handles at the
boundary where data enters — the mail listener, the webhook, the tool that just queried a system of
record. Data folded into your application as plain text before anyone decided to protect it is
beyond reach.

**Tell you your rules are right.** It can prove they're applied consistently, not that they're the
rules you needed.

## Licence

Apache 2.0.
