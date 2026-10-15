# Architecture Decision Records

This directory contains Architecture Decision Records (ADRs) for the Trove platform. Each ADR documents a significant architectural decision, the context that led to it, alternatives considered, and consequences of the choice.

## Index

| ADR | Title | Status | Date |
|-----|-------|--------|------|
| [0001](0001-microservice-per-domain.md) | Microservice Architecture per Business Domain | superseded by ADR-0020 | 2026-04-27 |
| [0002](0002-jwt-shared-secret.md) | JWT Signing with Shared Symmetric Secret (HMAC-SHA256) | superseded by ADR-0020 | 2026-04-27 |
| [0003](0003-plugin-architecture.md) | Plugin Architecture for Bank Data Sources | superseded by ADR-0020 | 2026-04-27 |
| [0004](0004-envelope-budgeting-model.md) | Envelope Budgeting with Read-Time Health Calculation | accepted | 2026-04-27 |
| [0005](0005-lombok-usage.md) | Lombok for Boilerplate Reduction in Entity Classes | accepted | 2026-04-27 |
| [0006](0006-valkey-for-rate-limiting.md) | ValKey (Redis Clone) for Distributed Rate Limiting | superseded by ADR-0020 | 2026-04-27 |
| [0007](0007-authentik-as-identity-provider.md) | Authentik as External Identity Provider (SSO/OIDC) | superseded by ADR-0020 | 2026-04-27 |
| [0008](0008-minio-for-report-storage.md) | MinIO for Report Storage and Retrieval | superseded by ADR-0020 | 2026-04-27 |
| [0009](0009-deduplication-strategy.md) | Three-Step Transaction Deduplication Strategy | accepted | 2026-04-27 |
| [0010](0010-aes-gcm-field-encryption.md) | AES-256-GCM Field-Level Encryption for Sensitive Credentials | accepted | 2026-04-27 |
| [0011](0011-threat-model.md) | Threat Model — OWASP Top 10 (A04 Insecure Design) | accepted | 2026-05-05 |
| [0020](0020-monolith-collapse.md) | Collapse the Microservice Split into One Spring Boot Module | accepted | 2026-09-26 |

## How to Use This Directory

- **Finding decisions**: Search the index above for a keyword or topic
- **Reading an ADR**: Click on the ADR number to read the full document
- **Creating a new ADR**: Follow the MADR format used in existing ADRs. Assign the next sequential number and add an entry to the index above
- **Updating status**: If a decision is superseded or deprecated, update the Status column and reference the replacement ADR

## Format

All ADRs follow the MADR (Markdown Architectural Decision Records) format with these sections:

- **Title**: Concise description of the decision
- **Date**: ISO 8601 date when the decision was made
- **Status**: `proposed`, `accepted`, `deprecated`, or `superseded by ADR-NNNN`
- **Deciders**: People or teams involved in the decision
- **Context**: The problem or situation that prompted the decision
- **Decision**: What was decided and why
- **Alternatives Considered**: Other options evaluated and why they were rejected (3-5 alternatives)
- **Consequences**: Positive impacts, negative trade-offs, and risks

## References

- [MADR Format](https://adr.github.io/madr/)
- [Architecture Decision Records](https://adr.github.io/)
