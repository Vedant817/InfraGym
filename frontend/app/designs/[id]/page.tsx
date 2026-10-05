import type { Metadata } from "next";
import { DesignWorkspace } from "@/components/design-workspace";
export const metadata: Metadata = { title: "Design editor" };
export default async function DesignPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <DesignWorkspace key={id} id={id} />;
}
