# Portals

A **charter** is where an application constitutes its authority. Each declaration hands back the
one object able to perform that operation — a **portal**.

| portal | what holding it lets you do |
|---|---|
| `Conceal<T>` | hand over a real value, leave with a surrogate |
| `Reveal<T>` | turn a surrogate back into a value, at one declared destination |
| `Derivation<I,O>` | make one value from another |
| `Fold<I,O>` | make one value from many |
| `Query<I,Q>` | ask one question of a value without the value leaving |

## Authority is held, never looked up

There is deliberately no method that trades a name for the door it names. A name is what the
manifest and the audit trail call a door; it is not a way through it.

Miller's four ways to come by a capability are initial conditions, parenthood, endowment and
introduction. **Lookup by name is not among them**, and adding it would quietly return this library
to policing labels rather than distributing authority.

The practical consequence: *"which code in this application can read cardholder data?"* is answered
by grepping for a constructor parameter.

```java
public final class ChargeCard {
  private final Reveal<String> toGateway;      // this class can. Nothing else can.

  public ChargeCard(Reveal<String> toGateway) {
    this.toGateway = toGateway;
  }
}
```

## Conceal takes no label

```java
Conceal<Mail> customerMail =
    charter.source("customer-mail", MAIL, ctx -> label(ctx, UNENDORSED, PERSONAL));

Surrogate<Mail> held = customerMail.conceal(incoming);   // no label argument
```

The door carries its own label, decided once when it was declared, so code holding it writes at
that label and no other. A service handed the door for customer-submitted disputes **cannot create
cardholder data** — not "is refused at runtime", but cannot express the operation, because the only
door it has says something else. Writing at another tenant's label is less refused than unsayable.

The label may still depend on who is acting: a door fixes what is a property of the door itself —
what arrives there, how far it is trusted, how sensitive it is — and reads the tenant from ambient
context. So it is not quite a constant, but nothing a caller passes influences it.

!!! note "Concealing is the axiom"
    Concealing is where data enters, and at that moment there is no earlier label to check against.
    Monotone join makes it a *theorem* that derivation cannot weaken a label; concealing is the
    axiom that theorem rests on. Declaring doors makes those axioms enumerable, which is all
    anything can do.

## There is no reveal without a destination

```java
Reveal<Invoice> paymentProcessor =
    charter.destination("payment-processor", ctx -> ceiling(ctx, ENDORSED, CARDHOLDER), INVOICE)
           .reading(INVOICE);

Revealed<Invoice> out = paymentProcessor.reveal(held);
```

No overload omits the destination. You cannot obtain plaintext "in general", only plaintext for
somewhere — and that somewhere is what the ceiling hangs off and what the audit records.

`Revealed<T>` is a sealed result rather than an exception: being turned away while reading is an
ordinary outcome worth branching on.

```java
switch (out) {
  case Revealed.Allowed<Invoice> allowed -> send(allowed.value());
  case Revealed.Denied<Invoice> denied   -> log(denied.reason());
}
```

## Surrogates disclose nothing

A `Surrogate<T>` prints as its identifier and nothing else, and carries no runtime type. Knowing
that a surrogate is a card token rather than a display name is itself a disclosure, so a refusal
will not tell you either — the ceiling is checked before the type, and a caller who may not see the
value is not told what kind of value it is.

## The lifecycle

A charter is declared, then **sealed**, once and irreversibly. After sealing, no further authority
can be constituted; before it, no portal will work.

Whoever constructs a charter keeps the ability to seal it, and that reference is not on the
`Charter` interface every bean is handed. Nor is erasure, nor anything that reports on a *held
value*. The interface declares portals and reports on the *declarations* — `axes()` and
`manifest()` — which describe the system and never a value.
