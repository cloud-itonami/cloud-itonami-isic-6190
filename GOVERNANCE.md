# Governance

`cloud-itonami-6190` is an OSS open-business blueprint for community
telecommunications access. Governance covers both the capability layer and
the operator model.

## Maintainers

Maintainers may merge changes that preserve these invariants:

- numbers cannot be provisioned or rerouted without identity and governor
  approval.
- the Telecom Access Governor remains independent of the advisor.
- hard policy violations (lawful-intercept bypass, billing suppression)
  cannot be overridden by human approval.
- every provision, route, bill and disclose path is auditable.
- customer traffic, location and credential data stays outside Git.

## Decision Records

Architecture decisions live in `docs/adr/`. Changes to the trust model,
storage contract, public business model, operator certification or license
should add or update an ADR.

## Operator Governance

Anyone may fork and operate independently. itonami.cloud certification is a
separate trust mark and should require security, audit, lawful-intercept
readiness and data-flow review.

Certified operators can lose certification for:

- bypassing provisioning or billing policy checks
- mishandling customer traffic or location data
- misrepresenting certification status
- failing to respond to security incidents
- hiding material changes to customer-facing operation
