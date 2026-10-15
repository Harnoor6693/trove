/**
 * The connected Trove server: one base URL for the whole app.
 *
 * Deliberately a plain module rather than a React store, because
 * `client.ts`'s axios interceptors need the value outside any component tree
 * and importing a zustand store from there would create a cycle. `authStore`
 * mirrors the connection onto `state.server` for the UI to read.
 *
 * `EXPO_PUBLIC_API_BASE_URL` (`app.config.ts` `extra.apiBaseUrl`) is now only
 * a **development default** — a convenience so `expo start` against a local
 * `trove-core` does not need the connect screen every time. Release profiles
 * ship it empty and the user supplies the address (readiness gap P4-19).
 */

import Constants from 'expo-constants';
import type { ServerMeta } from './types';
import { readJson, remove, StorageKeys, writeJson } from './secureStorage';

export interface ServerConnection {
  /** Origin only, no trailing slash: `https://trove.example.com`. */
  baseUrl: string;
  /** The "allow http on my network" opt-in that let a LAN address through. */
  allowInsecureLan: boolean;
  /** Last successful `GET /api/v1/status` body (§4.2). */
  meta: ServerMeta | null;
}

let connection: ServerConnection | null = null;

function isServerConnection(value: unknown): value is ServerConnection {
  if (typeof value !== 'object' || value === null) return false;
  const candidate = value as Partial<ServerConnection>;
  return typeof candidate.baseUrl === 'string' && candidate.baseUrl.length > 0;
}

/** The dev-only fallback origin, or null in a release build. */
export function devDefaultBaseUrl(): string | null {
  if (!__DEV__) return null;
  const fromConfig = Constants.expoConfig?.extra?.apiBaseUrl;
  const raw = typeof fromConfig === 'string' ? fromConfig : process.env.EXPO_PUBLIC_API_BASE_URL;
  return typeof raw === 'string' && raw.trim() !== '' ? raw.replace(/\/+$/, '') : null;
}

/** The connected server, or null when the app has never been paired. */
export function getServerConnection(): ServerConnection | null {
  return connection;
}

/** Base URL for HTTP calls; falls back to the dev default in development. */
export function getBaseUrl(): string | null {
  return connection?.baseUrl ?? devDefaultBaseUrl();
}

/** Load the persisted connection into memory. Call once on launch. */
export async function hydrateServerConnection(): Promise<ServerConnection | null> {
  const stored = await readJson<ServerConnection>(StorageKeys.server, isServerConnection);
  if (stored) {
    connection = {
      baseUrl: stored.baseUrl.replace(/\/+$/, ''),
      allowInsecureLan: stored.allowInsecureLan === true,
      meta: stored.meta ?? null,
    };
  }
  return connection;
}

/** Persist and activate a server connection. */
export async function setServerConnection(next: ServerConnection): Promise<void> {
  connection = { ...next, baseUrl: next.baseUrl.replace(/\/+$/, '') };
  await writeJson(StorageKeys.server, connection);
}

/** Forget the server entirely — "Change server" / "Disconnect" in Settings. */
export async function clearServerConnection(): Promise<void> {
  connection = null;
  await remove(StorageKeys.server);
}

/** Test seam: reset module state between tests. */
export function __resetServerConnectionForTests(): void {
  connection = null;
}
