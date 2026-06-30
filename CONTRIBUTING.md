# Contributing

`cloud-itonami-6190` accepts contributions to the OSS blueprint, capability
bindings, policy tests, documentation and operator model.

## Development

The capability layer lives in `kotoba-lang/phone`. This repo holds the
business blueprint and operator contracts.

```bash
# in kotoba-lang/phone:
clojure -X:test
clojure -M:lint
```

Keep changes small and include tests for E.164 validation, SIP parsing, or
CDR/SMS structure.

## Rules

- Do not commit real subscriber numbers, traffic data, credentials or
  customer records.
- Keep number provisioning, routing and billing behind the Telecom Access
  Governor.
- Treat telecom workflows as high-risk: add tests for identity, purpose,
  billing, disclosure and audit logging.
- Document any new business-model or operator assumption in `docs/`.

## Pull Requests

PRs should describe:

- what behavior changed
- which policy invariant is affected
- how it was tested
- whether operator or certification docs need updates
