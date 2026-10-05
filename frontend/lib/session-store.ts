/**
 * Where the session survives to.
 *
 * `sessionStorage`, chosen deliberately over the two alternatives.
 *
 * Memory only (the previous behaviour) means a refresh or a deep link logs the user
 * out with no way back, which is the defect this replaces.
 *
 * `localStorage` would also survive a refresh, but it persists indefinitely and is
 * shared by every tab of the origin, so a signed-in credential would outlive the
 * browsing session by weeks. `sessionStorage` is scoped to the tab and dies with it.
 *
 * The tradeoff, stated plainly: this holds a Basic-auth credential — which is the
 * user's actual password, base64-encoded rather than encrypted — in storage readable
 * by any JavaScript on the origin. An attacker who can run script here (an XSS in
 * this app, a hostile browser extension, a compromised dependency) gets the
 * credential outright, and would not need to wait for the user to sign in. That is
 * strictly worse than an `HttpOnly` cookie, which script cannot read at all.
 *
 * `sessionStorage` does not mitigate that: both web storages are equally readable by
 * script already running on the page. It is chosen for its *lifetime*, not for any
 * protection against script.
 *
 * Why not a cookie then: the gateway authenticates each request from an
 * `Authorization` header that the browser attaches, so the credential has to be
 * reachable by JavaScript in order to be forwarded at all. An `HttpOnly` cookie
 * cannot be read and re-sent, and this app has no server-side session of its own to
 * fall back to. Storing the credential is the only option that preserves the existing
 * per-request header model, and `sessionStorage` is the narrowest one available.
 *
 * The real fix is a server-issued `HttpOnly; Secure; SameSite` session cookie, which
 * requires a session endpoint in the gateway. Until that exists the exposure above is
 * the price. It is why a session is only ever written after sign-in has succeeded
 * against the API, and why a stored entry that does not look like a Basic credential
 * is discarded rather than sent.
 */

export const SESSION_KEY = "archforge.session";

export interface StoredSession { authorization: string; username: string }

const EMPTY = "";

/**
 * The stored session, or null when there is nothing usable to restore.
 *
 * Validated rather than trusted: a truncated write, a tampered value, or an entry from
 * an older format yields the sign-in form instead of a request with a broken
 * `Authorization` header. A non-string `authorization` or one that is not a Basic
 * credential is rejected here rather than forwarded to the gateway.
 */
export function parseSession(raw: string | null | undefined): StoredSession | null {
  if (!raw) return null;
  let parsed: unknown;
  try { parsed = JSON.parse(raw); } catch { return null; }
  if (!parsed || typeof parsed !== "object") return null;
  const { authorization, username } = parsed as Partial<StoredSession>;
  if (typeof authorization !== "string" || !authorization.startsWith("Basic ")) return null;
  if (authorization.length <= "Basic ".length) return null;
  if (typeof username !== "string" || !username) return null;
  return { authorization, username };
}

function storageOrNull(): Storage | null {
  try {
    // Access throws rather than returning null in browsers that block storage, and
    // there is no window at all during a server render.
    return typeof window === "undefined" ? null : window.sessionStorage;
  } catch {
    return null;
  }
}

/** Reads the raw entry. The raw string, not the parsed object, so snapshots are stable. */
export function readRaw(storage: Storage | null = storageOrNull()): string {
  if (!storage) return EMPTY;
  try { return storage.getItem(SESSION_KEY) ?? EMPTY; } catch { return EMPTY; }
}

/** Writes or clears the entry. A blocked or full store throws and is swallowed. */
export function writeRaw(raw: string, storage: Storage | null = storageOrNull()): void {
  if (!storage) return;
  try {
    if (raw) storage.setItem(SESSION_KEY, raw);
    else storage.removeItem(SESSION_KEY);
  } catch {
    // Persistence is best-effort. The caller keeps the session in memory either way,
    // so a blocked store costs persistence, not the working session.
  }
}

const listeners = new Set<() => void>();

/**
 * Notifies subscribers that the stored session changed.
 *
 * Needed because a storage write is not a React state update. Without it a
 * `useSyncExternalStore` reader keeps the pre-write snapshot and the UI does not
 * follow sign-in. Fires only in this document: a session is per-tab, and another tab
 * of the same origin is a separate session by construction.
 */
export function notifySessionChanged(): void {
  for (const listener of listeners) listener();
}

export function subscribeSession(listener: () => void): () => void {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}

/**
 * The server snapshot.
 *
 * Empty, because a server render has no session and must not claim one: the first
 * client render has to agree with it or hydration mismatches, and a signed-in user
 * would otherwise be shown the sign-in form for a frame.
 */
export function serverSessionSnapshot(): string {
  return EMPTY;
}