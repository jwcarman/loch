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
Held<String> body = loch.hold(message.body(), new Billing(ACME, UNENDORSED, AMBER, PII));

loch.dereference(body, VENDOR_LLM, onBehalfOf(ACME));       // Denied: above ceiling
loch.dereference(body, QUARANTINED_LLM, onBehalfOf(ACME));  // Allowed
```

## What it rests on

**Possession is not authority.** Holding a handle does not permit reading it.

**Presentation is not attribution.** A handle asserts identity only. Labels come from the store —
otherwise anything that could write an id could claim to be trusted.

**A handle names a value; it does not describe how to make one.** No parents, no recipe. If an id
described a computation, presenting one would be a request to perform it, and ids arrive from
untrusted places. So **lookup never computes**: an id is found, or refused.

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

## What it does not do

**Anything after a value is handed back.** That code holds a `String` and can send it anywhere. No
Java library can follow it without bytecode instrumentation. The mitigation is to prefer narrow
operations that answer questions without surrendering values.

**Tell you your rules are right.** It can prove they're applied consistently, not that they're the
rules you needed.

## Licence

Apache 2.0.
