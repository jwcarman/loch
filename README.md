# Loch

**A governed claim check for Java.**

A claim check stores the payload and hands back a token; the token travels instead of the thing.
Loch governs the redemption. Every value carries a label, every place a value might go declares what
it will accept, and turning a check back into a payload is decided against both — plus an identity
the holder of the check does not control.

Built on Denning's lattice model.

> **Status: early but real.** The lattice, handles, the gate, derivation, folds, lineage, checks,
> the audit trail, erasure, and durable encrypted storage on Postgres all work and are tested,
> against a Spring Boot service as well as in isolation. Not yet: transaction participation (a
> `hold` inside `@Transactional` will not roll back with it), a Spring Boot starter, and key
> rotation is untested.

## The problem

The pattern is Hohpe and Woolf's; what is usually missing from it is that anyone holding the check
can redeem it. Some values should not simply become text: credentials, personal and regulated data, untrusted
input from outside, anything a compliance regime has opinions about. Once such a value is a `String`
in your application, nothing can tell you where it went.

Loch takes custody of the value and gives you a handle. The handle can go anywhere — an event
stream, a log line, a prompt, a message to another service — because holding one is not permission
to read it. Turning it back into a value is the one checked operation, and it always names where
the value is going.

```java
Handle<String> body =
    loch.hold(mail.body(), String.class, BillingLabels.of("acme", UNENDORSED, PERSONAL));

loch.dereference(body, VENDOR_LLM);        // Denied: above that endpoint's ceiling
loch.dereference(body, QUARANTINED_LLM);   // Allowed
```

Who is asking comes from the edge, not from the call — see below — so nothing is threaded through.

## What it rests on

**A value's class is not its type.** `List.of(a, b).getClass()` is `ImmutableCollections$List12`,
which nothing can deserialise into, so a loch that guessed from the object would hand back a handle
it could never honour. The caller says what a value is, and `TypeRef` says it precisely:

```java
loch.hold(cards, TypeRef.listOf(TypeRef.of(Card.class)), label);
```

**Possession is not authority.** Holding a handle does not permit reading it.

**Presentation is not label.** A handle asserts identity only. Labels come from the store —
otherwise anything that could write an id could claim to be trusted.

**A handle names a value; it does not describe how to make one.** No parents, no recipe. If an id
described a computation, presenting one would be a request to perform it, and ids arrive from
untrusted places. So **lookup never computes**: an id is found, or refused.

**What travels is the id.** A `Handle<T>` is a *local typed view* — its `TypeRef` is a claim the gate
checks against what the store actually wrote, which makes it worth having where code uses a value
and worth nothing on a wire. Events, messages and rows carry a `HandleId`, which every serialiser can
manage without being taught anything, and the receiving side says what it expects:

```java
record InboundMail(String from, HandleId body) {}          // goes anywhere

Handle<String> body = Handle.of(event.body(), String.class); // typed again where it is used
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

`LatticeTck` currently lives in `loch-core`'s test sources, so extending it from outside this repo
needs a published test-jar or a `loch-testing` module — neither exists yet.

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

**Bound your decompression.** The codec library's `CompressionStreamCodec` takes a maximum decoded
size, and since the bytes being decompressed come from a database, a corrupt or hostile row should
not be able to expand into an out-of-memory error. That is a property of the compressor you supply,
not something Loch imposes — `Compression.whenItHelps` wraps whatever you hand it.

**Every derivation makes a new value.** There is no deduplication and no "deterministic" flag. An
earlier version keyed reproducible derivations on their parents and reused the result — and since a
derivation function sees the access context while the key did not, a second caller was handed the
first caller's answer without the function running at all, across tenants. Saving a row was not
worth a rule with an exception in it.

**Erasure is refused until you say who may erase what.** The policy sees the label of the value
being destroyed as well as who is asking — because who alone is not enough, and a rule that only
checks the caller's role lets one tenant's compliance officer destroy another tenant's records.
(That was a real hole here for about an hour, introduced while fixing the previous one.) Descendants
go regardless of their own labels, which is what erasure means: a value derived from two customers
dies with either of them.

**And erasure is refused until you say who may.** Every other gate decides whether a value may be
*disclosed* somewhere, and a label has nothing to say about whether it may be *destroyed* —
"possession is not authority" is a rule about reading. So the authority to erase is named
separately, with `mayErase(...)`, or it is not granted. An application that never erases says
nothing and gets a loch that cannot.

**Erasure is a reachability query.** Lineage is kept as values are derived, so erasing a value takes
everything ever made from it in one indexed statement. Lineage is a DAG rather than a tree, so this
is a closure table rather than a materialised path, whose rows would multiply at every merge.

Anything Loch stores must round-trip through your codec, which is why its own label types are plain
records rather than sealed hierarchies: a sealed type needs polymorphic type information that every
codec has to be told about separately, and `loch-core` depends only on the codec *contract* and so
cannot annotate itself for any particular one.

## Combining values, and the thing that makes it matter

```java
Handle<Report> report = loch.deriveAll(List.of(acmeNote, globexNote), SUMMARISE);
```

One value or several is the same operation — same ceiling, same lowering, same lineage, same audit
line — so it is one type with two factories, `of(...)` and `fromAll(...)`. It was two types once,
and the second was quietly missing from the manifest for exactly as long as nobody looked.

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

Refusals are recorded as carefully as permissions, at **every** gate — dereference, derive, fold and
check alike. A thousand refused attempts against one value is the interesting event, and a log of
successes cannot show it.

The sharpest case is a derivation that *declines*. The function has already run and already read the
plaintext before deciding the answer is no, so a caller could read a value it was not entitled to
act on, repeatedly, and leave nothing behind. That refusal is recorded, with the label of what was
read — and a refusal that happened *before* the value was looked at honestly records no label at
all. Checks are recorded with the answer
but never the question. Holding is recorded too, since that is the one place labels are asserted
rather than computed.

**And an access that cannot be audited does not happen.** If the auditor throws, the gate refuses.
An application that would rather proceed says so by catching inside its own auditor, which makes
that a decision somebody wrote down.

## Where this sits in the literature

Two halves of this trace to the same person. The algebra is **Denning's lattice model** (1976), used the way Denning stated it — parametric over
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

And the other half: a question answers one bit about a value nobody may read, which is a
**predicate** in the zero-knowledge sense and has an older cousin in the non-extractable keys of
PKCS#11 and every HSM — use it, never read it. Bounding how many such questions may be asked is
**inference control**, from the statistical-database literature: Denning, Denning and Schwartz's
tracker attack (1979) and Denning and Schlörer on inference controls (1983), where a sequence of
individually harmless queries reconstructs the record. The same Dorothy Denning, twice.

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

**The closest living relative is FIDES** (Flow Integrity Deterministic Enforcement System), from
Microsoft Research and now shipping in Microsoft Agent Framework — see Costa and Köpf, *Securing AI
Agents with Information-Flow Control* (arXiv:2505.23643), and `github.com/microsoft/fides`. It
carries integrity and confidentiality labels on conversation content, propagates them automatically
through tool calls, and enforces policy before a sensitive tool runs.

Three things it does better than this library does today. Propagation is **automatic**, through
middleware, where Loch only propagates when an application calls `derive` — build a value by hand
after dereferencing and the labels are gone. Policy is declared **on the tool**, next to what it
governs, where ours sits in a configuration class; FIDES buys that locality by being stringly-typed,
which is a real trade rather than a free win. And a policy violation can **escalate to a human**
rather than simply failing.

Three things this library does that FIDES does not. Its two axes are fixed, so a tenant, a data
residency region or an existing Purview taxonomy cannot be expressed at all — and with no
exact-match dimension, cross-tenant mixing is not representable. Labels only ever ratchet upward,
so there is no legitimate way to record that a claim has now been checked against a system of
record, which is the whole of the billing flow above. And it is agent middleware rather than
custody: the plaintext stays in the conversation, where here it never enters it.

Those are different threat models and both are worth having. FIDES stops untrusted content driving
control flow; this stops sensitive content being present in the first place.

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
boolean ok = loch.ask(handle, OWNED_BY, sender).isTrue();        // never leaves
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
