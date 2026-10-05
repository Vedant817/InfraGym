export function Loading({ children = "Loading…" }: { children?: React.ReactNode }) {
  return <p className="notice" role="status">{children}</p>;
}
export function Failure({ error, retry }: { error: string; retry: () => void }) {
  return <div className="notice error" role="alert"><p>{error}</p><button onClick={retry}>Retry request</button></div>;
}
export function Empty({ children }: { children: React.ReactNode }) {
  return <p className="notice">{children}</p>;
}
