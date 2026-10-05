"use client";

import { useEffect, useState } from "react";
import { message } from "./format";

export interface Resource<T> { data: T | null; loading: boolean; error: string }
export function useResource<T>(load: (signal: AbortSignal) => Promise<T>) {
  const [state, setState] = useState<Resource<T>>({ data: null, loading: true, error: "" });
  const [version, setVersion] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    void Promise.resolve().then(async () => {
      if (controller.signal.aborted) return;
      setState({ data: null, loading: true, error: "" });
      try {
        const data = await load(controller.signal);
        if (!controller.signal.aborted) setState({ data, loading: false, error: "" });
      } catch (reason) {
        if (!controller.signal.aborted) setState({ data: null, loading: false, error: message(reason) });
      }
    });
    return () => controller.abort();
  }, [load, version]);
  return { ...state, reload: () => setVersion((current) => current + 1) };
}
