"use client";
import Link from "next/link";
import { message } from "@/lib/format";
export default function ErrorPage({ error, retry }: { error: unknown; retry: () => void }) {
  return <section className="page panel" role="alert"><h1>Workspace could not be displayed</h1><p>{message(error)}</p><div className="actions"><button onClick={retry}>Try again</button><Link href="/">Return to designs</Link></div></section>;
}
