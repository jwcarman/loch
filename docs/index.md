# Loch

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

## Two ideas, both borrowed

**Information flow.** Every value carries a [label](concepts/labels.md); every place a value might
go declares a [ceiling](concepts/ceilings.md). Reading is decided against both. Deriving a value
joins its parents' labels, so an ordinary derivation *cannot* weaken one — that is a theorem about
the algebra rather than a rule somebody enforces. This is Denning's lattice model, applied per axis.

**Object capabilities.** Authority is held, never looked up. There is no registry and no
`charter.get("customer-mail")`. Code can conceal at a door because something handed it that door,
so *"what can this class do?"* is answered by reading its constructor parameters — and a service
handed the door for customer disputes **cannot express** the operation of creating cardholder data.

## What it is for

The case it was built for is an LLM. A model should be able to reason about a customer's email —
refer to it, ask about it, act on it — without the text ever entering the conversation. It holds an
identifier; the tools it may call hold the authority. Naming a surrogate is not authority to redeem
one, so a prompt injection cannot conjure a way to read a value.

The other half is the inbound direction. Anything a model produced, or anything that arrived from
outside, is concealed as *untrusted*, and the only path from untrusted to trusted is a named
[derivation that declassifies](concepts/deriving.md) — which appears in the
[manifest](guides/reviewing.md) where a reviewer will see it.

## Getting started

```xml
<dependency>
  <groupId>org.jwcarman.loch</groupId>
  <artifactId>loch-core</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Then read **[Getting Started](guides/getting-started.md)**, or
**[What Loch Does Not Do](limits.md)** if you would rather know the limits first. That page is not
an apology — knowing what a security library declines to promise is how you find out whether it
fits.

!!! warning "Nothing has been released yet"
    The public API is still moving. Identifiers, column names and digests have all changed more
    than once, so a database written by one commit is not necessarily readable by the next.
    Migrations begin at the first release.

## Elsewhere

- [Source, issues and security advisories](https://github.com/jwcarman/loch)
- [Contributing](https://github.com/jwcarman/loch/blob/main/CONTRIBUTING.md)

## Requirements

Java 25, and Postgres if you want durable storage. A `MemoryStorage` is included for tests and for
proving a policy before a database is involved.
