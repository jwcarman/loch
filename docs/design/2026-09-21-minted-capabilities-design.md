# Minted capabilities

**Status:** design, not yet built. Supersedes the id-and-registry mechanics described in the README.

## The problem

Every door into a loch is opened by naming it.

```java
loch.dereference(handle, DestinationId.of("payment-processor"), ctx);
```

`DestinationId.of` is a public factory. So is `DerivationId.of`, and so is `QuestionId.of`. Any class
on the classpath can construct the name of any door and present it. The only thing standing between
an arbitrary caller and cardholder plaintext is that `dereference` re-evaluates the ceiling against
the ambient context — a runtime check, on every call, on a path the caller chose to walk.

The write side is worse, because there is no name to forge: `hold` takes the label directly.

```java
loch.hold(value, String.class, BillingLabels.of("globex", ENDORSED, CARDHOLDER));
```

Anything holding the `Loch` bean can mint a value at any label in the lattice. We closed the obvious
half of this with `mayHold`, a `BiPredicate<A, AccessContext>` that refuses a hold at a label the
acting context has no business writing at. That works, and the billing example's policy is tight —
it refuses unattributed and conflicted labels, not merely other tenants' — but it answers the
question in the wrong place. "Which code in this application can mint an endorsed value?" is
answered by reasoning about a lambda, and the answer is "all of it, subject to a predicate."

Both problems are the same problem. **Authority is carried by names, and names are forgeable.**

## The change

Authority becomes carried by object references, minted during configuration and handed out by
constructor injection. There is no way to ask for one by name.

```java
// configuration: the only place capabilities are minted
disputeIntake    = c.inlet(DISPUTE_CLAIM, DisputeClaim.class, ctx -> of(ctx, UNENDORSED, ORDINARY));
cardIntake       = c.inlet(CARD_TOKEN,    String.class,       ctx -> of(ctx, ENDORSED, CARDHOLDER));
paymentProcessor = c.outlet(PAYMENT_PROCESSOR,                ctx -> of(ctx, ENDORSED, CARDHOLDER));

// application code: holds what it was given, and nothing else
class DisputeService {
  DisputeService(Inlet<DisputeClaim> disputeIntake, Outlet supportDesk) { ... }

  Handle<DisputeClaim> file(DisputeClaim claim) {
    return disputeIntake.hold(claim);        // there is no label argument
  }
}
```

`DisputeService` cannot write cardholder data. Not *is refused at runtime* — it cannot express the
operation, because it was not handed `cardIntake`. "Which code can mint an endorsed value" becomes a
grep for a constructor parameter.

### The rule the whole design rests on

**No lookup by id after construction.** No `loch.inlet(MAIL)`, no `loch.outlet(id)`, no surviving
`dereference(handle, DestinationId, ctx)`. The moment any of those exists the id is a key again and
the capability is decoration.

Ids do not disappear. The manifest and every audit record need a stable name for a door. They become
*labels on* a capability rather than a way to *obtain* one.

A consequence worth stating: the registry leaves the critical path. Today `dereference` looks a
destination up by id in order to dispatch. With outlets the caller already holds the object, so the
map survives only so `manifest()` can enumerate what exists. A lookup table that nothing looks up
cannot be a back door.

### Vocabulary

`Inlet` and `Outlet`, with the loch as the frame of reference — things enter the loch through an
inlet and leave through an outlet. The frame matters: `Source`/`Sink` is relative to a flow, and two
flows cross here (plaintext in, plaintext out; handle out, handle in), so a reader cannot tell which
frame a `Source` belongs to. The loch does not move, so inlet and outlet do not swap.

`OpaqueValue*` as a prefix was considered and rejected: it puts the adjective on the wrong noun. The
value is ordinary; the *reference* is opaque. That is why the reference is called `Handle`.

### What is minted

All four, or none. Derivations and questions carry the same forgeable-id problem, and a
half-conversion is worse than the status quo because it looks safe.

| Capability | Operation | Authority it carries |
|---|---|---|
| `Inlet<T>` | `hold(T)` → `Handle<T>` | mint a value at this inlet's label |
| `Outlet` | `read(Handle<T>)` → `Dereferenced<T>` | read plaintext at or below this ceiling |
| `Derivation<I, O>` | `derive(Handle<I>)`, `deriveAll(List<Handle<I>>)` | read plaintext to compute; possibly lower |
| `Question<I, Q>` | `ask(Handle<I>, Q)` → `Answer` | learn one bit about a held value |

Derivations and questions barely convert. They are already objects the application builds; the id
round-trip through the registry was always pointless. The application stops calling `.build()` and
keeps what `c.derivation(...)` hands back.

`Loch<A>` keeps `erase`, `label`, `lineage`, `holds` and `manifest` — administration and reporting.
That is a coherent thing to be, and it is the only surface that still warrants a wide interface.
Four different authorities were previously four methods on one object that everybody injected.

### What goes away

`mayHold` is deleted. The label is no longer a caller-supplied argument, so there is nothing to
police. `hold` also loses its `AccessContext` parameter: the label comes from the inlet's function
reading ambient context, and the caller no longer influences the label even indirectly.

## Prior art

The pattern is old; the combination is what is unusual here.

- **Dennis & Van Horn (1966)**, *Programming Semantics for Multiprogrammed Computations*. A
  capability both designates an object and authorizes access to it.
- **Miller (2006)**, *Robust Composition*. The four ways to obtain a capability — initial
  conditions, parenthood, endowment, introduction. Lookup by string is not among them, which is
  exactly the no-`loch.inlet(id)` rule above.
- **Flume** (Krohn et al., SOSP 2007) is the closest match. DIFC over ordinary OS abstractions, where
  a process holds unforgeable capabilities `t⁺` and `t⁻` — the authority to add or remove a tag.
  `t⁺` is the authority to label data at a tag, which is what an `Inlet` is. Asbestos (2005) and
  HiStar (2006) are the same family; HiStar's *gates* mint privileged transitions at creation.
- **LIO** (Stefan, Russo, Mitchell, Mazières, Haskell Symposium 2011). `Priv` tokens are unforgeable
  values, and holding one *is* the authority to declassify — a value you hold, not a predicate you
  satisfy.
- **WASI preopens.** A Wasm module may only touch descriptors handed to it at instantiation; there is
  no ambient filesystem to name. Mint-at-construction, in current practice.
- **`MethodHandles.Lookup`.** The JDK's own capability object: its authority derives from who created
  it, it cannot be forged for another class, and it can only be received. Evidence that this is
  idiomatic Java rather than an import from another paradigm.
- **Joe-E** (Mettler, Wagner, Close, NDSS 2010), a capability-safe subset of Java — cited here for
  its limits rather than its results. See below.

The powerbox (Stiegler) — a trusted component holding broad authority that hands out narrowed
capabilities — describes what the configuration object becomes. Wiring constructor injection up as
the powerbox appears to be folklore rather than a written-down pattern.

## What this does not fix

Stated plainly, because the design is easy to oversell.

**Ambient identity is still only as good as its source.** The billing example reads `X-Tenant` off
the request. If no gateway strips that header from client traffic, a caller picks their own tenant
and every inlet's label function agrees with them. Loch cannot verify this and never will; it is
outside the boundary.

**Ingest that serves many tenants still writes at whatever the context claims.** A webhook, a batch
import, a migration — the acting identity is the system, not the tenant. What changes is that the
decision lives in one inlet's label function instead of being spread across every `hold` call site.

**Unforgeability is bounded by the language.** An `Inlet` interface whose only implementation is
package-private, requiring a package-private engine reference to construct, is unforgeable against
ordinary application code. It is not unforgeable against reflection, nor against a class added to
`org.jwcarman.loch` on the application's own classpath. Sealing the jar addresses the second. The
first is why Joe-E had to forbid reflection *at the language level* to make object-capability
discipline sound in Java — a subset we are not going to adopt. This belongs in the javadoc, not
only in this document.

**`hold` remains the axiom, not a theorem.** Monotone join proves that derivation cannot weaken a
label. Nothing proves that a value labelled `ENDORSED` deserves it, because at the moment of minting
there is no prior label to check against. Minting is enumerable now; it is not verifiable.

## Mechanics

### Minting returns the capability

`c.inlet(...)` returns the `Inlet`, not the config, so the fluent chain breaks and the configuration
block becomes imperative. Every fluent alternative explored required a retrieval step
(`minted.inlet(MAIL)`), which is the back door. This is the ergonomic bill for unforgeability, and
it buys one small thing back: a capability cannot be registered and then be unreachable.

### Binding

The engine that actually writes — storage, auditor, lattice — does not exist while the configuration
lambda is running, so a capability minted mid-configuration cannot hold a live reference yet. It
needs a one-shot binding that `Loch` construction fills, and **an unbound capability must throw
rather than no-op**. This is the sharp edge of the whole design: a test asserting only the happy path
will not notice it is broken.

### Spring

Each capability is a `@Bean`, and injection is the distribution mechanism. Two `Outlet` beans share a
type, so Spring disambiguates by parameter name — `Outlet paymentProcessor` finds the bean of that
name. A typo fails at startup rather than resolving to the wrong door, which is acceptable, but it is
the one place this design leans on a string again and it should be called out in the example.

### Possible bonus

Because the configuration supplies `A`, the type-witness verbosity on registrations
(`Derivations.<BillingLabels, Domain.Mail, Domain.Invoice>checking(...)`) may fall out for free. This
is unverified — backward inference through a stored lambda is exactly where javac gives up — and it
must be checked against the compiler before it is claimed anywhere.

## How this gets judged

By rewriting the billing example against it and reading the result, the same way every other decision
in this library has been judged. The measure is application code, not library code.
