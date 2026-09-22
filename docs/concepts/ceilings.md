# Ceilings

A **ceiling** is what a reader is entitled to see. A label is what a value is. Reading is permitted
when the label is at or below the ceiling, on every axis the label speaks to.

```java
Ceiling ceiling = Ceiling.of(TENANT, Constraint.atMost("acme"))
    .with(INTEGRITY, Constraint.any())
    .with(SENSITIVITY, Constraint.atMost(PERSONAL));
```

Two constraints, and the difference matters:

| constraint | means |
|---|---|
| `Constraint.atMost(v)` | this axis may be `v` or anything below it |
| `Constraint.any()` | this axis may be anything — except a mixture |

`any()` still refuses a mixture. A reader who is broad about tenants is not thereby entitled to
something belonging to two of them at once.

## Silence is not permission

A label that speaks to an axis the ceiling never constrained is **refused**.

```java
Ceiling ceiling = Ceiling.of(TENANT, Constraint.atMost("acme"));
// a value labelled with SENSITIVITY as well is refused: this ceiling never said anything about it
```

This is the fail-closed direction, and it is what makes adding an axis safe. Declare a fourth axis
and every existing ceiling stops admitting values that speak to it, until somebody says what it
should allow. The alternative — treating an unmentioned axis as "anything" — means a new axis is
invisible to every gate on the day it ships.

Say `any()` when you mean it. Breadth is a decision worth writing down.

## Ceilings usually depend on who is asking

A door's ceiling is a function of the access, not a constant:

```java
charter.destination(
    "support-ui",
    ctx -> Ceiling.of(TENANT, Constraint.atMost(ctx.get("tenant").orElseThrow()))
               .with(SENSITIVITY, Constraint.atMost(ORDINARY)),
    INVOICE);
```

The tenant comes from ambient context, never from anything a caller passes. If a call site could
override what the edge established, any code holding a portal could name itself whichever tenant it
liked, and the gate would agree — which is not a policy system but a formality.

## A ceiling that cannot decide has not said yes

Ceilings are application code, and application code fails. A ceiling that throws, or that returns
`null` — a `ctx.get("tenant").map(...).orElse(null)` on a request that had no tenant — is treated
as a refusal, and the refusal is recorded.

There is deliberately no way to express "this operation accepts anything". A derivation or a query
that never calls `accepting(...)` is refused at configuration time, so a ceiling is always present
and always consulted.

## Ceilings are not authority to act

A ceiling answers *may this value be seen here*. It does not answer *should this happen*. A
capability to refund a card is not a statement about disclosure, and loch has nothing to say about
the amount. Pair it with whatever authorises actions in your system — see
[What Loch Does Not Do](../limits.md).
