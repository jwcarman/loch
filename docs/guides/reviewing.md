# Reviewing a Charter

`manifest()` is meant to be printed at startup and pasted into a review. It is answerable before
the charter has been sealed to any storage, because it describes the declarations rather than any
value — so a build can render it, diff it against the last release, and fail on a change nobody
meant to make.

```java
System.out.println(charter.manifest(AccessContext.of(Map.of("tenant", "acme"))));
```

```
charter manifest, as {tenant=acme}

  unconstrained label (bottom)
    {}

  destinations (3)
    support-ui         accepts up to {tenant=acme, integrity<=ENDORSED, sensitivity<=ORDINARY}
    quarantined-llm    accepts up to {tenant=acme, integrity=any, sensitivity<=PERSONAL}
    payment-processor  accepts up to {tenant=acme, integrity<=ENDORSED, sensitivity<=CARDHOLDER}
    (what these doors accept for this access; another may be offered more or less)

  derivations (2)
    invoice.card.last4     invoice -> last4   << WEAKENS LABELS
    mail.confirmedInvoice  mail -> invoice    << WEAKENS LABELS

  questions (1)
    mail.mentions  asks about mail
    (each answer is one bit and the asker chooses the question, so enough questions
     read the value; a ceiling is what limits who may ask at all)

  2 operation(s) can WEAKEN a label:
    invoice.card.last4     invoice -> last4
    mail.confirmedInvoice  mail -> invoice
```

## Render it for somebody

Pass an access. A door whose ceiling reads the tenant cannot say what it accepts without one, and
in a multi-tenant application that is every door — so a manifest rendered for nobody reports that
it could not evaluate a single ceiling. There is no such thing as what a door accepts in general,
so render one per representative caller.

## What to look for

**The weakening list.** Everything else in the design makes labels more constrained; these are the
only things that can make them less, and there should be few enough to read in one sitting. A name
like `invoiceNumber.trustMe` should stop you dead — which is the point, because **no algebra can
tell you whether a check is strong enough**. An endorsement that merely confirms a record exists
looks identical to one that ties it to the person who asked. That judgement is human.

**Ceilings that are broader than you expected.** `any()` on an axis is a decision somebody made;
confirm it was deliberate.

**Questions.** A question looks like the safe way to use a value because it never hands one over.
Enough of them read it anyway. Ask whether the asker is a person or a loop.

**Doors that read a type nothing produces, or produce a type nothing reads.** A door nobody can
reach is dead authority; it is also the shape a mistake takes when a rename went half-applied.

## Diffing it in CI

Render the manifest for each representative access, commit the output, and fail the build when it
changes without the change being acknowledged. The point is not that change is bad — it is that a
change to what the system permits should be something somebody chose rather than something that
happened.
