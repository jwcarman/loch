# Axes and Labels

An **axis** is a question an application asks about every value it holds. A **label** is one
value's answers.

Loch ships no mandatory vocabulary. In US government nomenclature CONFIDENTIAL sits *below* SECRET;
in the common corporate convention it sits near the top. No regulated organisation will abandon its
mandated scheme because a library has opinions, so you declare your own.

```java
public static final Axis<String>      TENANT      = Axis.matching("tenant").required();
public static final Axis<Integrity>   INTEGRITY   = Axis.ladder("integrity", ENDORSED, UNENDORSED);
public static final Axis<Sensitivity> SENSITIVITY = Axis.ladder("sensitivity",
                                                        ORDINARY, PERSONAL, CARDHOLDER);

Axes axes = Axes.of(TENANT, INTEGRITY, SENSITIVITY);
```

## Two kinds of axis

A **ladder** is ordered, and you state the order where a reviewer will see it:

```java
Axis.ladder("sensitivity", ORDINARY, PERSONAL, CARDHOLDER)
```

There is deliberately **no factory that reads `Enum::ordinal`**. Declaration order is a terrible
place to keep a security-relevant contract: someone sorts a list of constants alphabetically in an
unrelated tidy-up, every test still passes, and the gate now permits the opposite of what it
should. Nothing about an enum declaration says "the order of these lines is load-bearing." Every
constant must appear, so adding one and forgetting it is a failure rather than a silent demotion.

A **matching** axis is not ordered at all. Two tenants are simply different:

```java
Axis.matching("tenant")
```

## Labels

A label says what one value answers on every axis it speaks to. It is immutable; every operation
returns a new one.

```java
Label label = Label.of(TENANT, "acme")
    .with(INTEGRITY, UNENDORSED)
    .with(SENSITIVITY, PERSONAL);
```

**There is no getter.** A label answers predicates instead:

```java
label.says(TENANT, "acme");   // true
label.unsaid(TENANT);         // did a required axis go unanswered?
```

A typed getter would have to answer what a matching axis returns when it holds a *mixture* — not
the application's type, because a mixture is not one, and not an empty `Optional` either, because
that would mean both "mixed" and "nobody said" and those two must never be confused. Not having the
getter means not having the question.

`says` is for assertions, manifests and reports — never for decisions. Asking a label one axis at a
time and acting on the answers is how an application rebuilds the gate badly, outside the audit.

## Join

Two labels combine into the least thing at or above both, axis by axis:

```java
Label combined = a.join(b);
```

An axis missing from one side is not missing from the answer: what a label does not say sits at
that axis's bottom, and bottom is the identity, so the other side's value carries. That is why two
labels describing different axes combine into one describing both.

**Join is what makes safety a theorem.** A derived value's label is the join of its parents', and
join only moves up, so an ordinary derivation cannot weaken a label. Not "must not" — cannot.

## Mixtures

On a matching axis, joining two different values produces a **mixture**, and this is the only place
one can be born.

```java
Label acme   = Label.of(TENANT, "acme");
Label globex = Label.of(TENANT, "globex");
acme.join(globex);   // tenant is now a mixture
```

No ceiling admits a mixture. So a value made from two tenants' data exists, keeps honest lineage to
both parents, and reaches nobody. Cross-tenant leakage is not forbidden by a rule somebody
remembered to write; it is unrepresentable.

## Required axes

An axis nobody answered sits at the **bottom** of its order — and the bottom is below every
ceiling. So an unanswered tenant does not mean "private to nobody"; it means **readable by
everyone**, which is the opposite of what you wanted.

```java
Axis.matching("tenant").required()
```

A required axis must be answered. Concealing a value whose label leaves one unanswered is refused,
a declassification that drops one is refused, and a stored label missing one is refused wherever
it is read back — so marking an axis required on a system that has been writing without it makes
those rows *unreadable* rather than universally readable.

!!! note "Do not mark a ladder required"
    A ladder's bottom usually means something — `ORDINARY` is a real sensitivity, not a gap. A
    matching axis is the case where bottom and "nobody said" look identical, which is what
    `required()` exists for.
