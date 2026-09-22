# Security Policy

## Supported Versions

We aim to support the latest stable release of Loch and provide critical security patches as
needed.

| Version | Supported |
| ------- | --------- |
| Latest  | Yes       |
| Older   | No        |

## What counts as a vulnerability here

Loch exists to keep values from being disclosed, so a bug in it is often a security bug. The
following are vulnerabilities and are worth reporting privately:

- **Reading above a ceiling.** Any way to obtain plaintext that the declared ceiling should have
  refused, including through a derivation, a fold, a query or an erasure.
- **Forging a label.** Any way to make a value carry a label it was not concealed or derived at,
  or to weaken one without going through a declared lowering.
- **Obtaining a portal you were not handed.** Any lookup by name, reflection route, escaped
  reference, or use of a portal before its charter was sealed or after it should have been shut.
- **Defeating the record.** Any edit, deletion, reordering or replay of the audit trail, or of the
  value graph, that verification reports as intact.
- **Disclosure through a refusal.** A denial that reveals a value's type, contents or label to
  somebody whose ceiling would not have admitted it.
- **Cross-tenant leakage.** Anything that lets a value labelled for one tenant reach another,
  including through a mixture.

Some limits are known, documented, and not vulnerabilities:

- **Tail truncation.** Removing the most recent lines of the audit chain leaves a chain that
  verifies. Detecting it requires an anchor kept where the writer cannot reach it.
- **The query oracle.** A question answers one bit, and enough questions read a value a piece at a
  time. Nothing counts them; what limits the exposure is the ceiling.
- **Existence.** A refusal distinguishes an identifier that exists from one that does not.
- **What application code does with plaintext.** Loch decides who may see a value. It cannot stop
  code that legitimately revealed one from writing it somewhere else.

## Reporting a Vulnerability

**Please DO NOT report security vulnerabilities through public GitHub issues, pull requests, or
discussions.**

Instead, please report via:
- **GitHub Security Advisories**: https://github.com/jwcarman/loch/security/advisories/new (preferred)
- **Direct contact**: @jwcarman on GitHub

Include the following information:
- Detailed description of the vulnerability
- Steps to reproduce the issue, ideally as a failing test
- Potential impact assessment
- Any known workarounds or mitigations
- Whether the vulnerability is publicly known
- Affected versions (if known)

## Our Response Process

1. **Acknowledgment**: We will acknowledge receipt of your report within **3 business days**
2. **Investigation**: We will investigate and validate the issue
3. **Fix Development**: We aim to coordinate a fix within **7-14 business days** for critical issues
4. **Disclosure**: We will work with you to determine an appropriate disclosure timeline
5. **Credit**: We will publicly acknowledge reporters (if desired) in security advisories

## Security Commitments

- Handle all reports confidentially
- Keep you informed throughout the process
- Publish security advisories with CVSS scoring when appropriate
- Issue patches or provide mitigation guidance
- Credit researchers in advisories (upon request)
