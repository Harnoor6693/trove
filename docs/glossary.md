# Trove Glossary

This file described classes from the abandoned microservice/plugin build
(`PluginRegistry`, `ModelRoutingRegistry`, `LiteLlmRestClient`, Authentik,
ValKey, MinIO, and others) — none of which exist in the shipped monolith
(ADR-0020).

The response envelope, auth carrier modes and error shape are defined in the
OpenAPI spec description (`openapi/trove-core.yaml`, `info.description`). The domain
packages are in [docs-site/guide/architecture.md](../docs-site/guide/architecture.md).
The budgeting envelope model is [ADR-0004](adr/0004-envelope-budgeting-model.md).
