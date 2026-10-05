"use client";
import { useEffect, useState } from "react";
import { api } from "./api";
import type { SubmissionDetail } from "./contracts";
import { message, terminal } from "./format";

interface PollState { detail: SubmissionDetail | null; error: string; lastUpdated: string | null }
export function useSubmission(authorization: string, id: string) {
  const [state, setState] = useState<PollState>({ detail: null, error: "", lastUpdated: null });
  const [version, setVersion] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;
    let failures = 0;
    async function poll() {
      try {
        const detail = await api.submission(authorization, id, AbortSignal.any([controller.signal, AbortSignal.timeout(35_000)]));
        if (controller.signal.aborted) return;
        failures = 0;
        setState({ detail, error: "", lastUpdated: new Date().toISOString() });
        if (!terminal(detail.submission.status)) timer = setTimeout(poll, 2_000);
      } catch (reason) {
        if (controller.signal.aborted) return;
        failures += 1;
        setState((previous) => ({ ...previous, error: message(reason) }));
        // Keep the last successful audit snapshot visible while retrying.
        timer = setTimeout(poll, Math.min(30_000, 2_000 * 2 ** Math.min(failures, 4)));
      }
    }
    void poll();
    return () => { controller.abort(); if (timer) clearTimeout(timer); };
  }, [authorization, id, version]);
  return { ...state, retry: () => setVersion((current) => current + 1) };
}
