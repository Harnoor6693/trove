# Trove Glossary

This file described classes from the abandoned microservice/plugin build
(`PluginRegistry`, `ModelRoutingRegistry`, `LiteLlmRestClient`, Authentik,
ValKey, MinIO, and others) — none of which exist in the shipped monolith
(ADR-0020).

For the real terms in active use (envelope pattern, auth carrier modes, error
shape, domain packages) see the OpenAPI spec's own description
(`openapi/trove-core.yaml`, `info.description`) and
[docs-site/guide/architecture.md](../docs-site/guide/architecture.md), which
define them where they're used rather than in a separate list that can drift.
