import type { Metadata } from "next";
import { SubmissionWorkspace } from "@/components/submission-workspace";
export const metadata: Metadata = { title: "Run inspection" };
export default async function SubmissionPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <SubmissionWorkspace key={id} id={id} />;
}
