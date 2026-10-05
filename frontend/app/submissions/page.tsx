import type { Metadata } from "next";
import { SubmissionList } from "@/components/submission-list";
export const metadata: Metadata = { title: "Simulation runs" };
export default function RunsPage() { return <div className="page stack"><h1>Simulation runs</h1><SubmissionList /></div>; }
