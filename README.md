# cloud-itonami-6190

Open Business Blueprint for **ISIC Rev.5 6190**: other telecommunications
activities (VoIP, public telephone and Internet access, telecom reselling,
specialized telecom applications).

This repository designs a forkable OSS business for community
telecommunications access: number provisioning, call routing, billing, and
support — run by a qualified operator so a community keeps its own call
records and numbering data.

## Core Contract

```text
intake + identity + E.164 number assignment
        |
        v
Telecom Advisor -> Telecom Access Governor -> provision, bill, or human approval
        |
        v
CDR + SMS record + routing decision + audit ledger
```

No automated advice can provision a number, suppress a billing record, or
release a call/SMS without governor approval and audit evidence.

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`6190`). Required capabilities are implemented by:

- [`kotoba-lang/phone`](https://github.com/kotoba-lang/phone) — E.164 numbering, SIP URIs, CDR, SMS

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## License

Code and implementation templates are AGPL-3.0-or-later.
