# Getting Started

A worked example: a support desk that reads customer emails, where the email body must never reach
a vendor model and cardholder data must never reach any model.

## 1. Say what you ask about values

```java
public final class BillingAxes {
  public enum Integrity { ENDORSED, UNENDORSED }
  public enum Sensitivity { ORDINARY, PERSONAL, CARDHOLDER }

  public static final Axis<String>      TENANT      = Axis.matching("tenant").required();
  public static final Axis<Integrity>   INTEGRITY   = Axis.ladder("integrity", ENDORSED, UNENDORSED);
  public static final Axis<Sensitivity> SENSITIVITY =
      Axis.ladder("sensitivity", ORDINARY, PERSONAL, CARDHOLDER);
}
```

Note which end of `INTEGRITY` is which. Untrusted data is the **more** constrained end, because it
is the dangerous thing to handle — so vouching for something moves it *down*, which is why the
operation is called lowering.

## 2. Constitute a charter

```java
DefaultCharter charter =
    new DefaultCharter(Axes.of(TENANT, INTEGRITY, SENSITIVITY))
        .currentAccess(() -> AccessContext.of(Map.of(
            "tenant", CurrentTenant.get(),
            "role", currentRole())))
        .mayErase((label, ctx) -> ctx.has("role", "compliance"));
```

`currentAccess` is how identity reaches the gate without being threaded through every call.
A `ThreadLocal`, a `ScopedValue`, Spring's holders — loch has no opinion.

## 3. Declare the doors

```java
static final SurrogateType<Mail> MAIL = SurrogateType.of("mail", Mail.class);

Conceal<Mail> customerMail = charter.source("customer-mail", MAIL,
    ctx -> Label.of(TENANT, ctx.get("tenant").orElseThrow())
                .with(INTEGRITY, UNENDORSED)
                .with(SENSITIVITY, PERSONAL));

Reveal<Mail> quarantinedLlm = charter.destination("quarantined-llm",
    ctx -> Ceiling.of(TENANT, Constraint.any())
                  .with(INTEGRITY, Constraint.any())
                  .with(SENSITIVITY, Constraint.atMost(PERSONAL)),
    MAIL).reading(MAIL);

Reveal<Mail> vendorLlm = charter.destination("vendor-llm",
    ctx -> Ceiling.of(TENANT, Constraint.any())
                  .with(INTEGRITY, Constraint.atMost(ENDORSED))
                  .with(SENSITIVITY, Constraint.atMost(ORDINARY)),
    MAIL).reading(MAIL);
```

Hold onto these. They are the authority; there is no way to look them up later.

## 4. Bring it into force

```java
charter.seal(new MemoryStorage());
```

Once, irreversibly. Before this, no portal works; after it, no further authority can be
constituted.

## 5. Use it

```java
Surrogate<Mail> held = customerMail.conceal(incoming);

vendorLlm.reveal(held);        // Denied — PERSONAL is above ORDINARY, and it is UNENDORSED
quarantinedLlm.reveal(held);   // Allowed
```

## 6. Print what you built

```java
System.out.println(charter.manifest(AccessContext.of(Map.of("tenant", "acme"))));
```

See [Reviewing a Charter](reviewing.md) for what to look for — and note the argument. A ceiling
that reads the tenant cannot be rendered without one.

## Next

- Durable, encrypted storage: [Storage](storage.md)
- Wiring it in an application: [Spring Boot](spring-boot.md)
- The boundaries: [What Loch Does Not Do](../limits.md)
