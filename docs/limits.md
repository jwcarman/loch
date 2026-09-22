# What Loch Does Not Do

Knowing what a security library declines to promise is how you find out whether it fits. None of
what follows is a bug; each is a documented boundary, and several have tests that pin the boundary
in place.

## It decides disclosure, not action

Loch answers *may this value be seen here*. It does not answer *should this happen*.

A model that can name a surrogate can ask a tool to act on it, and if the ceiling admits the value,
loch says yes — correctly. Whether a refund of that size, to that account, at that moment, should
occur is an authorisation question, and the amount is not a labelled value at all.

Pair loch with whatever authorises actions in your system. The two are different mechanisms
answering different questions, and a system needs both.

## It cannot stop code that legitimately revealed a value

Once a `Reveal` hands back plaintext, what the holder does with it is beyond the gate. Writing it
to a log, returning it in a response, or putting it back into a conversation are all ordinary code.

What the design gives you instead is that the list of code holding a `Reveal` is short, greppable
and in the [manifest](guides/reviewing.md).

## Questions read a value a bit at a time

A `Query` never hands the value over, which makes it look like the safe way to use one. But each
answer is a bit and the asker chooses the question, so enough questions reconstruct the value.

Nothing counts them, deliberately. A budget small enough to stop reconstruction of a card number is
small enough to make the feature useless — the bound usually cited is about aggregates over many
rows, and this is an exact predicate over one value, so any number of questions above zero leaks.
What actually separates a probe from a question is the shape of what the caller may choose, which
only whoever wrote the predicate knows.

What limits the exposure is the **ceiling**: a value you may not ask about gives you no questions
at all. What makes it visible is the **trail**, which records every question against the value it
was asked about — so *"how many questions did this caller ask about this value this hour"* is a
query you can already run and alert on.

If the asker is a loop rather than a person, put the limit where the loop is.

## Tail truncation is undetectable from the data alone

Removing the most recent lines of the audit chain leaves a chain that verifies. Detecting it
requires an anchor kept somewhere the writer cannot reach.

## A refusal reveals existence

The ceiling is checked before the type, so a refusal will not say what kind of value it turned
away. It does still distinguish an identifier that exists from one that does not. Identifiers are
122 random bits, so this answers only about identifiers a caller already holds.

## No transaction participation

Storage opens its own connection. A `conceal` inside an `@Transactional` method **will not roll
back with it**. This is the largest missing feature, and it is a correctness surprise rather than a
philosophical position.

## One backend, and no migrations yet

Postgres only — the storage uses advisory locks, recursive CTEs, `FOR SHARE` and `clock_timestamp()`.
Schema creation is `CREATE TABLE IF NOT EXISTS` and nothing alters an existing table, so a database
written by one pre-release commit is not necessarily readable by the next.

## The privileged methods are public on the implementation

`Charter` is the interface every bean is handed, and it cannot seal, erase, or report on a held
value. The implementation can, and its methods are public — so a cast on an injected bean defeats
the guarantee. The interface is a statement of intent backed by what code asks for, not a sandbox.
