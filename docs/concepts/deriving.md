# Deriving

A derived value's label is the **join** of its parents': the least thing at or above all of them.
Join only moves up, so an ordinary derivation cannot weaken a label. That is a theorem, not a rule
anyone applies.

```java
Derivation<Invoice, Last4> cardLast4 =
    charter.derivation("invoice.card.last4", INVOICE, LAST4,
        invoice -> new Last4(last4(invoice.cardToken())),
        d -> d.accepting(ctx -> ceiling(ctx, ENDORSED, CARDHOLDER))
              .lowering(joined -> joined.with(SENSITIVITY, PERSONAL))
              .availableTo(ctx -> ctx.has("role", "approver")));
```

Three things are settled at declaration and cannot change afterwards, which is what makes the
manifest a complete answer:

- **`accepting`** — what this derivation may read. Required.
- **`lowering`** — whether it weakens the label, and how. Optional; its absence is the common case.
- **`availableTo`** — whether it is offered to this access at all. Optional.

## One parent or many

`Fold` is the same operation over a list. Same ceiling, same lowering, same lineage, same audit
line — only the arity differs.

```java
Fold<Note, Report> summarise = charter.fold("notes.summarise", NOTE, REPORT, notes -> ..., d -> ...);
Derived<Report> report = summarise.fold(List.of(acmeNote, globexNote));
```

The join does the quiet job: fold one ordinary note with one containing a home address and the
result is `PERSONAL`, so it reaches the quarantined model and not the vendor's. And it does the
loud one: fold two tenants' notes and the tenant axis joins to a mixture that is below no ceiling,
so the report exists, remembers both parents honestly, and reaches nobody.

## Declassification

Some operations genuinely reduce what a value is. Truncating a card to its last four digits is a
real reduction in sensitivity; tying a customer's claim to the mailbox it arrived from is a real
increase in trust. Such an operation declares `lowering(...)`, and may weaken **only** what it
declares:

```java
.lowering(joined -> joined.with(INTEGRITY, ENDORSED))
```

The result must be at or below the join of the parents. It must also still answer every required
axis — a relabel that stops mentioning one would be at or below everything, including the label it
came from, so `atOrBelow` cannot see that mistake and a separate check does.

Every declassifying operation appears in the [manifest](../guides/reviewing.md) under **can WEAKEN
a label**. That list is the control, because **no algebra can tell you whether a check is strong
enough**: an endorsement that merely confirms a record exists looks exactly like one that ties it
to the person who asked. That judgement is human, so the list exists to put it in front of a human.

## Derivations that decline

A lookup finds nothing; a check fails. Use `checking`, whose function returns an `Optional` and
which also sees the access:

```java
Derivation<Mail, Invoice> confirm =
    charter.checking("mail.confirmedInvoice", MAIL, INVOICE,
        (mail, ctx) -> invoices.findFor(mail, ctx),        // Optional<Invoice>
        d -> d.accepting(...).lowering(joined -> joined.with(INTEGRITY, ENDORSED)));
```

This is the shape that promotes untrusted input. What elevates a claim is **agreement with
something already trusted** — never the fact that it arrived neatly.

## Reading happens once, and late

A derivation checks every label before it reads any plaintext. Types, ceilings and the join are all
settled from metadata; only then are the permitted values read, in one batch, and handed to the
application's function.

If that function then fails — throws, or answers with nothing — the read already happened, so it is
recorded as a refusal rather than allowed to escape as an exception. A crash must not be quieter
than a decline.

## Every derivation makes a new value

There is no deduplication and no "deterministic" flag. A derivation function sees the access
context, so reusing a result keyed on its parents alone would hand a second caller the first
caller's answer without the function running — across tenants. Saving a row is not worth a rule
with an exception in it.
