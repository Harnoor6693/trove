# ADR-0008: MinIO for Report Storage and Retrieval

**Date**: 2026-04-27  
Status: Superseded by ADR-0020 (monolith collapse, 2026-09-26 — MinIO was cut outright; receipts and exports are stored on local disk under `TROVE_DATA_DIR`, inside the deployment's own volume, see `finance.trove.receipts`)  
**Deciders**: Architecture and infrastructure team

## Context

Trove's report-service generates monthly financial reports (PDF, Excel) that users can download. Reports can be large (5-50 MB) and numerous (one per user per month). Storing reports in the PostgreSQL database would waste disk space, degrade database performance, and make backups expensive. The solution is to use object storage (S3-compatible API) where reports are stored in blobs with unique keys. MinIO is an open-source S3-compatible object storage server that can be deployed on-premises or in the cloud.

## Decision

Use MinIO as the object storage backend for report persistence. Reports are stored with keys like `reports/{userId}/{reportId}.pdf` and retrieved via S3 API (GetObject). The report-service requests MinIO URLs synchronously but does not block on storage (async generation). If MinIO is unavailable, report generation fails (reports are marked FAILED in the database). Reports are retained indefinitely by default; old reports can be garbage-collected via a cron job that deletes reports older than N months.

## Alternatives Considered

### Alternative 1: Store Reports in PostgreSQL Database
- **Pros**: No additional infrastructure; single source of truth; built-in backup/replication
- **Cons**: Bloats database; degrades query performance; expensive backups; large object handling is clumsy in SQL
- **Why not**: Database is not optimized for large binary blobs; scales poorly with report volume

### Alternative 2: AWS S3 (Managed Service)
- **Pros**: Highly available; world-class reliability; no infrastructure to maintain
- **Cons**: Vendor lock-in; ongoing costs; requires AWS account and network access to AWS
- **Why not**: MinIO provides S3-compatible API with self-hosted option; no lock-in

### Alternative 3: File System Storage (Local Disk)
- **Pros**: Simple to implement; no external dependency; fast access
- **Cons**: Not distributed (doesn't work across multiple servers); no redundancy; manual backup complexity
- **Why not**: Trove's microservices may run on different servers; local disk storage doesn't provide shared access

## Consequences

### Positive
- Reports are stored efficiently in object storage (no database bloat)
- S3-compatible API allows future migration to AWS S3 without code changes
- MinIO is open-source and self-hosted (no vendor lock-in)
- Object storage is optimized for large binary blobs (fast reads/writes)
- Reports can be served directly from MinIO to users (via signed URLs)
- Easy to implement retention policies and cleanup

### Negative
- Adds an operational dependency (MinIO cluster must be provisioned, patched, monitored)
- Requires MinIO expertise on the team
- Report generation workflow becomes async (users wait for report to be ready)
- MinIO cluster requires persistent storage (local disk or network-attached storage)

### Risks
- **MinIO unavailability**: If MinIO is down, report generation fails and users cannot download reports. Mitigated by: (1) deploying MinIO with high availability (replication, load balancing), (2) retrying failed report requests, (3) serving stale cached reports if MinIO is temporarily down
- **Data loss**: If MinIO's persistent storage is lost, all reports are gone. Mitigated by: (1) backing up MinIO data regularly, (2) storing reports in multiple MinIO instances (geo-redundancy), (3) keeping database records of generated reports for audit purposes
- **Bucket misconfiguration**: Incorrect bucket permissions could expose user reports to others. Mitigated by: (1) setting strict IAM policies (users can only download their own reports), (2) using signed URLs with time-limited access, (3) regular permission audits
