import type { Metadata } from "next";
import Link from "next/link";
import { SessionProvider } from "@/components/session-provider";
import "reactflow/dist/style.css";
import "./globals.css";

export const metadata: Metadata = {
  title: { default: "ArchForge · System design workspace", template: "%s · ArchForge" },
  description: "Build system topologies and inspect measured simulation results and evidence-backed evaluations.",
  robots: { index: false, follow: false },
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>
        <a className="skip-link" href="#main">Skip to workspace</a>
        <header className="app-header"><Link className="brand" href="/">ArchForge<span>System design lab</span></Link><nav aria-label="Main navigation"><Link href="/">Designs</Link><Link href="/submissions">Runs</Link></nav></header>
        <main id="main"><SessionProvider>{children}</SessionProvider></main>
      </body>
    </html>
  );
}
