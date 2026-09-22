# Loch

[![CI](https://github.com/jwcarman/loch/actions/workflows/maven.yml/badge.svg)](https://github.com/jwcarman/loch/actions/workflows/maven.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java](https://img.shields.io/badge/dynamic/xml?url=https://raw.githubusercontent.com/jwcarman/loch/main/pom.xml&query=//*[local-name()='maven.compiler.release']/text()&label=Java&color=orange)](https://openjdk.org/)

**Opaque references to sensitive values, for Java.**

Some values should not simply become text: personal and regulated data, credentials, untrusted
input from outside. Once such a value is a `String` in your application, nothing can tell you where
it went — a log line, an event, a prompt.

Loch takes custody of the value and hands back a **surrogate**. A surrogate travels anywhere,
because holding one is not permission to read it. Turning it back into a value is the one checked
operation, and it always names where the value is going.

```java
Surrogate<Mail> mail = customerMail.conceal(incoming);   // the real thing stays here

vendorLlm.reveal(mail);        // Denied  — above that destination's ceiling
quarantinedLlm.reveal(mail);   // Allowed
```

Built on Denning's lattice model and on the object-capability model: **authority is held, never
looked up**. There is no registry and no `charter.get("customer-mail")`. Code can perform an
operation because something handed it the portal that performs it — so "what can this class do?" is
answered by reading its constructor parameters.

## Read the docs

The [documentation site](https://jwcarman.github.io/loch/) is the manual: concepts, guides, and
[what Loch does not do](https://jwcarman.github.io/loch/limits/) — which is the page to read first
if you are deciding whether it fits. This README is the front door.

---

## The vocabulary

An application declares **axes**: the questions it asks about every value it holds. Loch ships no
mandatory scheme. In US government nomenclature CONFIDENTIAL sits *below* SECRET; in the common
corporate convention it sits near the top. No regulated organisation will abandon its mandated
scheme because a library has opinions.

```java
public static final Axis<String>      TENANT      = Axis.matching("tenant").required();
public static final Axis<Integrity>   INTEGRITY   = Axis.ladder("integrity", ENDORSED, UNENDORSED);
public static final Axis<Sensitivity> SENSITIVITY = Axis.ladder("sensitivity",
                                                        ORDINARY, PERSONAL, CARDHOLDER);
```

A **ladder** is ordered. A **matching** axis is not — two tenants are simply different, and their
combination is a *mixture* that no ceiling admits.

Note which end of `INTEGRITY` is which. Untrusted data is the **more** constrained end, because it
is the dangerous thing to handle. Vouching for something moves it *down*, which is why the
operation that does it is called **lowering**.

There is deliberately **no** factory that reads `Enum::ordinal`. Declaration order is a terrible
place to keep a security-relevant contract: someone sorts a list of constants alphabetically in an
unrelated tidy-up, every test still passes, and the gate now permits the opposite of what it
should. Nothing about an enum declaration says "the order of these lines is load-bearing", so you
state the order where a reviewer will see it, and every constant must appear.

A **`Label`** is what one value says on every axis; a **`Ceiling`** is what a reader may see. A
label speaking to an axis the ceiling never constrained is refused — silence is not permission. An
axis marked `required()` must be answered, because an unanswered axis sits at the bottom of its
order, and the bottom is below every ceiling: readable by everyone, not by nobody.

## The portals

A **charter** is where an application constitutes its authority. Each declaration hands back the
one object able to perform that operation.

```java
Conceal<Mail> customerMail =
    charter.source("customer-mail", MAIL, ctx -> label(ctx, UNENDORSED, PERSONAL));

Reveal<Invoice> paymentProcessor =
    charter.destination("payment-processor", ctx -> ceiling(ctx, ENDORSED, CARDHOLDER), INVOICE)
           .reading(INVOICE);

Derivation<Invoice, Last4> cardLast4 =
    charter.derivation("invoice.card.last4", INVOICE, LAST4,
        invoice -> new Last4(last4(invoice.cardToken())),
        d -> d.accepting(ctx -> ceiling(ctx, ENDORSED, CARDHOLDER))
              .lowering(joined -> joined.with(SENSITIVITY, PERSONAL))
              .availableTo(ctx -> ctx.has("role", "approver")));

Query<Mail, String> mailMentions =
    charter.query("mail.mentions", MAIL, String.class,
        (mail, text, ctx) -> mail.body().toLowerCase().contains(text.toLowerCase()),
        d -> d.accepting(ctx -> ceiling(ctx, UNENDORSED, PERSONAL)));
```

| portal | what holding it lets you do |
|---|---|
| `Conceal<T>` | hand over a real value, leave with a surrogate |
| `Reveal<T>` | turn a surrogate back into a value, at one declared destination |
| `Derivation<I,O>` | make one value from another |
| `Fold<I,O>` | make one value from many |
| `Query<I,Q>` | ask one question of a value without the value leaving |

**`Conceal` takes no label argument.** The door carries its own, decided when it was declared, so
code holding it writes at that label and no other. A service handed the door for customer-submitted
disputes cannot create cardholder data — not "is refused at runtime", but cannot express the
operation. Writing at another tenant's label is less refused than unsayable.

**There is no reveal without a destination.** No overload omits it. You cannot obtain plaintext "in
general", only plaintext for somewhere, and that somewhere is what the ceiling hangs off and what
the audit records.

A `Surrogate` prints as its identifier and nothing else, and carries no runtime type — knowing that
a surrogate is a card token rather than a display name is itself a disclosure.

## Deriving

A derived value's label is the **join** of its parents': the least thing at or above all of them.
Join only moves up, so an ordinary derivation cannot weaken a label. That is a theorem, not a rule
anyone has to remember.

One parent or many is the same operation — same ceiling, same lowering, same lineage, same audit
line — so `Derivation` and `Fold` differ only in arity.

The join does the quiet job: fold one ordinary note with one containing a home address and the
result is `PERSONAL`, so it reaches the quarantined model and not the vendor's. And it does the
loud one: fold two tenants' notes and the tenant axis joins to a **mixture** that is below no
ceiling — so the report exists, remembers both parents honestly, and reaches nobody. Nothing had to
be marked as conflicted.

A derivation may declassify by declaring `lowering(...)`, and only what it declares. Truncating a
card is a real reduction in sensitivity; tying a customer's claim to the mailbox it arrived from is
a real increase in trust. Both appear in the manifest:

```
  4 operation(s) can WEAKEN a label:
    invoice.card.last4      invoice -> last4
    mail.confirmedInvoice   mail -> invoice
```

There should be few enough to read in one sitting, and a name like `invoiceNumber.trustMe` should
stop a reviewer dead — which is the point, because **no algebra can tell you whether a check is
strong enough.** An endorsement confirming a record exists looks identical to one tying it to the
person who asked. That judgement is human, so the list exists to put it in front of a human.

**Every derivation makes a new value.** There is no deduplication and no "deterministic" flag. A
derivation function sees the access context, so reusing a result keyed on its parents alone would
hand a second caller the first caller's answer without the function running — across tenants.
Saving a row is not worth a rule with an exception in it.

## Who is asking

Identity is known at the edge and needed at the gate, which may be many layers down. Threading a
context through all of them makes the safety feature the most annoying thing in the codebase, and
annoying safety features get routed around. So say once where the answer lives:

```java
charter.currentAccess(() -> AccessContext.of(Map.of(
    "tenant", CurrentTenant.get(),
    "principal", SecurityContextHolder.getContext().getAuthentication().getName())));
```

A `ThreadLocal`, a `ScopedValue`, Spring's holders — Loch has no opinion about how your request
scope works. Nothing a caller passes influences a label or a ceiling: if a call site could override
what the edge established, any code holding a portal could name itself whichever tenant it liked,
which is not a policy system but a formality.

## The record

Every operation writes one line, allowed and refused alike. A thousand refused attempts against one
value is the interesting event, and a log recording only successes cannot show it. A line never
contains plaintext. It does carry the label, because an audit that cannot say *why* something was
refused is not much of an audit — and the label is encrypted on disk for the same reason the value
is, as is the access context, which this library does not interpret and so cannot judge harmless.

The trail is a **keyed HMAC hash chain**: each line commits to the digest of the one before it.
Keyed is the whole point — an unkeyed chain catches a careless edit and nothing else, because
whoever removed a line could recompute everything after it. The key is not in the database.

Values form a second signed structure. A value's digest covers its bytes, its label, what made it,
and its parents' digests, so editing one breaks everything derived from it.

```java
storage.firstBrokenEntry();   // a line edited, removed, reordered or replayed
storage.brokenValues();       // a value edited, or descended from one
storage.missingValues();      // announced by the trail, never erased, and not here
```

The third catches a deleted **leaf**: nothing was derived from it, so nothing is left to disagree.
Only the trail can answer that, because only the trail is outside the row — which is why erasure
records *which* values it destroyed, in the same transaction as the deletes.

**Tail truncation cannot be detected from the data alone.** Removing the most recent lines leaves a
chain that verifies. Detecting it needs an anchor kept somewhere the writer cannot reach.

## Erasing

A label has nothing to say about whether a value may be *destroyed* — "possession is not authority"
is a rule about reading. So the authority to erase is named separately with `mayErase(...)`, or it
is not granted, and an application that never erases gets a charter that cannot.

The policy sees the label of the value being destroyed as well as who is asking, because who alone
is not enough: a rule checking only the caller's role lets one tenant's compliance officer destroy
another tenant's records.

Erasing takes everything ever derived from a value, however deeply, computed by walking the lineage
that the value digests cover — so nothing decides what gets destroyed that a signature does not
protect. Descendants go regardless of their own labels, which is what erasure means: a value
derived from two customers dies with either of them.

The audit outlives what it describes. `loch_audit` has no foreign key into `loch_value` and nothing
cascades into it: the record that you erased somebody has to survive erasing them.

## Storage

Postgres. `loch-jdbc` contains **no cryptography and no compression of its own** — you compose the
pipeline and it applies what it is handed:

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

So it depends on `loch-core` and the codec *contract*, and nothing else. Jackson or fory or
protobuf; gzip or zstd or lz4; envelope encryption or your own KMS.

**Compression before encryption**, always: ciphertext does not compress, so the other order costs
the same and saves nothing.

**Compression should be conditional**, because measurement says so — most of what a charter holds
is small, and a compressor's framing costs more than a short payload saves:

```
{"number":"4111111111114821","holder":"J CARMAN"}   49 bytes -> gzip 57   BIGGER
{"v":"4821"}                                        12 bytes -> gzip 32   BIGGER
an email body                                      851 bytes -> gzip 79   smaller
```

**Bound your decompression.** The bytes come from a database, and a corrupt or hostile row should
not expand into an out-of-memory error. That is a property of the compressor you supply.

A `MemoryStorage` exists for tests and for proving a policy before a database is involved.

## Spring Boot

Declare the axes. The starter constructs the charter and is therefore the only thing able to seal
it, so nothing in the application can bring one into force or erase through one.

```java
@Bean
Axes billingAxes() {
  return Axes.of(TENANT, INTEGRITY, SENSITIVITY);
}
```

`Charter` is the interface every bean is handed. It declares portals and reports on the
*declarations* — `axes()`, `manifest()`. It cannot seal, cannot erase, and cannot report on any
held value: no `label`, no `lineage`, no `holds`. Those stay with whoever constructed it.

`manifest()` is meant to be printed at startup and pasted into a review. It answers before the
charter is sealed to any storage, because it describes the declarations rather than any value — so
a build can render it, diff it against the last release, and fail on a change nobody meant to make.

## Building

```
./mvnw clean verify
```

Java 25. The JDBC tests need Docker, for Testcontainers.
