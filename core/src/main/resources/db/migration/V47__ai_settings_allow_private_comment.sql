-- The column comment V16 wrote said the opt-in reaches a "link-local" target. It never has:
-- OutboundUrlPolicy refuses link-local whatever the two keys say. V16 is applied and immutable,
-- so the corrected comment is a migration of its own.
COMMENT ON COLUMN ai_settings.allow_private_endpoint IS
    'This member''s own opt-in to a private (RFC 1918, CGNAT, unique-local, site-local) or '
    'loopback target, to plain http, and to an IPv6 address in the deprecated IPv4-compatible '
    '(::/96) or the IPv4-mapped (::ffff:0:0/96) form (its IPv4 address is still judged). '
    'Effective only when the install also sets '
    'TROVE_EGRESS_ALLOW_PRIVATE; OutboundUrlPolicy requires both. Never reaches link-local, '
    'cloud metadata or the other always-refused ranges.';
