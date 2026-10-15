#!/usr/bin/env node
// npm-audit-gate.mjs
//
// A dependency-free gate around `npm audit`. It runs `npm audit --json
// --omit=dev` in a target directory, looks only at high/critical severity
// findings, and fails (exit 1) only for advisories that are not covered by
// an unexpired entry in that directory's `.audit-allowlist.json`.
//
// Usage:
//   node scripts/npm-audit-gate.mjs [dir]
//
// `dir` defaults to the current working directory. Run it from the repo
// root as `node scripts/npm-audit-gate.mjs apps/web`, or from inside an app
// as `node ../../scripts/npm-audit-gate.mjs`.
//
// Allowlist format (`<dir>/.audit-allowlist.json`), an array of:
//   {
//     "id": "GHSA-xxxx-xxxx-xxxx",
//     "package": "some-package",
//     "reason": "why this is accepted here",
//     "expires": "YYYY-MM-DD"
//   }
// An advisory is allowed only if its GHSA id matches an entry AND today's
// date is strictly before that entry's `expires` date.
//
// The allowlist path can be overridden with the AUDIT_ALLOWLIST_PATH env
// var (e.g. for testing the failure path against a scratch file without
// touching `<dir>/.audit-allowlist.json`); it still runs `npm audit`
// against `dir` itself.

import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';

const targetDir = path.resolve(process.argv[2] ?? process.cwd());
const allowlistPath = process.env.AUDIT_ALLOWLIST_PATH
  ? path.resolve(process.env.AUDIT_ALLOWLIST_PATH)
  : path.join(targetDir, '.audit-allowlist.json');

const HIGH_SEVERITIES = new Set(['high', 'critical']);

function runNpmAudit(dir) {
  // `npm audit` exits non-zero whenever vulnerabilities are found, which is
  // expected here -- we still need its stdout, so we don't let a non-zero
  // exit code stop us. Only treat it as a hard failure if we get no JSON at
  // all (e.g. npm itself failed to run).
  try {
    const stdout = execFileSync(
      'npm',
      ['audit', '--json', '--omit=dev'],
      { cwd: dir, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 }
    );
    return stdout;
  } catch (err) {
    // execFileSync throws when the child exits non-zero; the JSON we want
    // is still on err.stdout.
    if (err.stdout) {
      return err.stdout;
    }
    console.error(`Failed to run "npm audit" in ${dir}:`);
    console.error(err.message);
    process.exit(1);
  }
}

function parseAuditJson(stdout, dir) {
  try {
    return JSON.parse(stdout);
  } catch (err) {
    console.error(`Could not parse "npm audit --json" output from ${dir} as JSON.`);
    console.error(err.message);
    console.error('--- raw output ---');
    console.error(stdout);
    process.exit(1);
  }
}

function loadAllowlist(filePath) {
  if (!existsSync(filePath)) {
    return [];
  }
  let raw;
  try {
    raw = readFileSync(filePath, 'utf8');
  } catch (err) {
    console.error(`Could not read allowlist file ${filePath}: ${err.message}`);
    process.exit(1);
  }
  let parsed;
  try {
    parsed = JSON.parse(raw);
  } catch (err) {
    console.error(`Could not parse allowlist file ${filePath} as JSON: ${err.message}`);
    process.exit(1);
  }
  if (!Array.isArray(parsed)) {
    console.error(`Allowlist file ${filePath} must contain a JSON array.`);
    process.exit(1);
  }
  return parsed;
}

function isExpired(entry, today) {
  // entry.expires is "YYYY-MM-DD"; compare as calendar dates, not instants.
  return !(today < entry.expires);
}

function collectHighSeverityAdvisories(auditJson) {
  const vulnerabilities = auditJson.vulnerabilities ?? {};
  const advisories = [];

  for (const [pkgName, finding] of Object.entries(vulnerabilities)) {
    if (!HIGH_SEVERITIES.has(finding.severity)) {
      continue;
    }
    const via = Array.isArray(finding.via) ? finding.via : [];
    const advisoryObjects = via.filter((v) => typeof v === 'object' && v !== null);

    for (const advisory of advisoryObjects) {
      const url = advisory.url ?? null;
      const id = url ? url.split('/').pop() : (advisory.source ?? 'UNKNOWN');
      advisories.push({
        id,
        url,
        package: pkgName,
        title: advisory.title ?? '',
        severity: advisory.severity ?? finding.severity,
      });
    }
  }

  return advisories;
}

function main() {
  if (!existsSync(targetDir)) {
    console.error(`Target directory does not exist: ${targetDir}`);
    process.exit(1);
  }

  const stdout = runNpmAudit(targetDir);
  const auditJson = parseAuditJson(stdout, targetDir);
  const allowlist = loadAllowlist(allowlistPath);

  const today = new Date().toISOString().slice(0, 10); // YYYY-MM-DD

  // Fail fast if the allowlist itself contains an expired entry -- even if
  // that entry's advisory no longer appears in the audit, an expired entry
  // sitting in the file is a sign the allowlist needs to be revisited.
  const expiredEntries = allowlist.filter((entry) => isExpired(entry, today));
  if (expiredEntries.length > 0) {
    console.error('The following allowlist entries have expired and must be renewed or removed:');
    for (const entry of expiredEntries) {
      console.error(`  - ${entry.id} (${entry.package}): expired ${entry.expires}`);
    }
    console.error(`\nAllowlist file: ${allowlistPath}`);
    process.exit(1);
  }

  const advisories = collectHighSeverityAdvisories(auditJson);

  const rows = advisories.map((advisory) => {
    const match = allowlist.find(
      (entry) => entry.id === advisory.id && entry.package === advisory.package
    );
    const allowed = Boolean(match) && !isExpired(match, today);
    return { ...advisory, allowed, match };
  });

  console.log(`\nnpm audit gate: ${targetDir}`);
  console.log('='.repeat(72));

  if (rows.length === 0) {
    console.log('No high or critical severity advisories found.');
  } else {
    for (const row of rows) {
      const status = row.allowed ? 'ALLOWED' : 'BLOCKED';
      console.log(`\n[${status}] ${row.id}  (${row.package}, ${row.severity})`);
      if (row.title) {
        console.log(`  ${row.title}`);
      }
      if (row.url) {
        console.log(`  ${row.url}`);
      }
      if (row.allowed) {
        console.log(`  reason: ${row.match.reason}`);
        console.log(`  expires: ${row.match.expires}`);
      }
    }
  }

  const meta = auditJson.metadata?.vulnerabilities ?? {};
  const moderate = meta.moderate ?? 0;
  const low = meta.low ?? 0;
  console.log(
    `\n(info) moderate: ${moderate}, low: ${low} -- not gated, shown for awareness only.`
  );

  const blocked = rows.filter((row) => !row.allowed);
  console.log('\n' + '='.repeat(72));
  if (blocked.length > 0) {
    console.log(`RESULT: FAIL -- ${blocked.length} blocked high/critical advisor${blocked.length === 1 ? 'y' : 'ies'}.`);
    process.exit(1);
  }

  console.log(`RESULT: PASS -- ${rows.length} high/critical advisor${rows.length === 1 ? 'y' : 'ies'}, all allowed.`);
  process.exit(0);
}

main();
