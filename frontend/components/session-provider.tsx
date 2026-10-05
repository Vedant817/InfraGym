"use client";

import { createContext, useCallback, useContext, useState, useSyncExternalStore, type FormEvent, type ReactNode } from "react";
import { api } from "@/lib/api";
import { message } from "@/lib/format";
import {
  notifySessionChanged, parseSession, readRaw, serverSessionSnapshot,
  subscribeSession, writeRaw, type StoredSession,
} from "@/lib/session-store";

interface Session extends StoredSession { signOut: () => void }
const SessionContext = createContext<Session | null>(null);

export function useSession(): Session {
  const session = useContext(SessionContext);
  if (!session) throw new Error("An authenticated session is required.");
  return session;
}

export function SessionProvider({ children }: { children: ReactNode }) {
  // useSyncExternalStore rather than an effect that calls setState: storage is an
  // external system, this subscribes to it, and the server snapshot keeps the first
  // client render in agreement with the server-rendered HTML. An effect would render
  // the sign-in form first and then swap to the workspace, which is both a hydration
  // hazard and a flash of the wrong page for every refresh.
  const raw = useSyncExternalStore(subscribeSession, readRaw, serverSessionSnapshot);
  const credentials = parseSession(raw);
  const [error, setError] = useState("");
  const [pending, setPending] = useState(false);

  const persist = useCallback((next: StoredSession | null) => {
    writeRaw(next ? JSON.stringify(next) : "");
    notifySessionChanged();
  }, []);

  const signOut = useCallback(() => persist(null), [persist]);

  async function signIn(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    const username = String(data.get("username") ?? "");
    const password = String(data.get("password") ?? "");
    if (username.includes(":")) { setError("The username cannot contain a colon."); return; }
    const bytes = new TextEncoder().encode(`${username}:${password}`);
    const authorization = `Basic ${btoa(Array.from(bytes, (byte) => String.fromCharCode(byte)).join(""))}`;
    setPending(true); setError("");
    try {
      // Verified against the API before it is stored, so a wrong password never
      // persists and a reload does not restore a session that cannot authenticate.
      await api.designs(authorization);
      form.reset();
      persist({ authorization, username });
    } catch (reason) { setError(message(reason)); }
    finally { setPending(false); }
  }

  if (!credentials) return (
    <section className="sign-in panel" aria-labelledby="sign-in-title">
      <p className="eyebrow">ArchForge / engineering workspace</p>
      <h1 id="sign-in-title">Open your design workspace</h1>
      <p>Sign in with your design-service account to edit topologies and inspect simulation runs.</p>
      <form onSubmit={signIn} className="stack">
        <label>Username<input name="username" autoComplete="username" required disabled={pending} /></label>
        <label>Password<input name="password" type="password" autoComplete="current-password" required disabled={pending} /></label>
        {error && <p className="notice error" role="alert">{error}</p>}
        <button className="primary" disabled={pending}>{pending ? "Signing in…" : "Sign in"}</button>
      </form>
      <p className="muted">Credentials are kept in this tab&apos;s session storage, so a refresh or a deep link keeps you signed in. Closing the tab ends the session.</p>
    </section>
  );
  return <SessionContext.Provider value={{ ...credentials, signOut }}>
    <div className="account-bar"><span>Account: <strong>{credentials.username}</strong></span><button data-leave-workspace onClick={signOut}>Sign out</button></div>
    {children}
  </SessionContext.Provider>;
}