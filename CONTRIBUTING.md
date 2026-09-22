# Contributing to Loch

Thanks for your interest in contributing to Loch! We welcome pull requests, issues, and feedback
from the community.

## Before you start

Loch is a security library, so two things are worth knowing up front.

**If you have found a way to read a value you should not have been able to read, that is not an
issue — it is a vulnerability.** Please follow [SECURITY.md](SECURITY.md) and report it privately
rather than opening a public issue.

**A change to what the library permits needs a reason, stated in the commit.** The audit trail, the
ceilings and the label algebra are the product; a change that makes any of them more permissive
should say what it buys and what it costs, because the next person to read it will need to decide
whether it was a good trade.

## How to Contribute

### Reporting Bugs

If you find a bug, please open an issue and include:
- A clear description of the problem
- Steps to reproduce the issue
- Expected vs actual behavior
- Version of the library and relevant environment details (Java version, Postgres version)

### Requesting Features

We're happy to hear your ideas! Before opening a feature request, check if one already exists. If
not, open a new issue and include:
- A description of the proposed feature
- Why it would be useful
- Any relevant use cases or examples

### Submitting a Pull Request

1. **Fork** the repository and create a new branch from `main`
2. Make your changes, writing tests
3. Run the build and tests:
   ```bash
   ./mvnw clean verify
   ```
4. Ensure formatting and licence headers pass:
   ```bash
   ./mvnw spotless:apply license:format
   ```
5. Open a pull request and describe your changes

For larger changes, consider opening an issue first to discuss the approach.

## Testing

**You need Docker.** The JDBC tests run a real Postgres through Testcontainers, because the storage
uses advisory locks, recursive CTEs and `FOR SHARE`, and an in-memory stand-in has none of them.
Nothing here needs network access to any other service.

```bash
./mvnw clean verify
```

Two conventions are worth following, because this codebase has been bitten by both:

**Write the failing test first, and watch it fail.** Several defects here were found only because a
test failed before the fix and passed after. A test written afterwards tends to assert what the code
now does.

**A test for a security property should fail when the property is broken.** That sounds obvious and
is easy to get wrong — asserting on a value that would be refused anyway, or wrapping a list in
another list so it can never be empty, both look like tests and check nothing. If a change fixes a
gate, try reintroducing the bug and confirm your test goes red.

## Code Style and Conventions

- **Java 25+**: Use modern Java features judiciously -- prefer clarity and simplicity
- **Formatting**: Google Java Format (enforced by Spotless)
- **License headers**: Apache 2.0 headers on all source files (enforced by `license:check`)
- **No `@SuppressWarnings`**: Fix the underlying issue instead
- **No star imports**: Always use explicit single-symbol imports

### Commit Messages

Follow conventional commit format:
- `feat: add support for a new axis kind`
- `fix: a refusal no longer says what kind of value it refused`
- `docs: explain why questions are not counted`
- `test: cover a lowering that drops a required axis`
- `refactor: extract the transaction helper`

## Community Standards

We strive to foster a welcoming and respectful community. By participating, you agree to abide by
our [Code of Conduct](CODE_OF_CONDUCT.md).

## License

By contributing to this project, you agree that your contributions will be licensed under the
Apache License 2.0.
