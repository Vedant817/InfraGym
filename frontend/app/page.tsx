"use client";

import { useState } from "react";
import { SystemDesignCanvas } from "../components/canvas/SystemDesignCanvas";

export default function Home() {
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [submissionResult, setSubmissionResult] = useState<any>(null);

  const handleSubmit = async () => {
    setIsSubmitting(true);
    try {
      const response = await fetch("/api/v1/compile", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          nodes: [],
          edges: [],
        }),
      });
      const result = await response.json();
      setSubmissionResult(result);
    } catch (error) {
      setSubmissionResult({ error: "Submission failed" });
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div className="flex flex-col h-screen">
      <header className="flex items-center justify-between px-6 py-3 bg-gray-900 text-white">
        <h1 className="text-xl font-bold">InfraGym - System Design Evaluator</h1>
        <div className="flex gap-3">
          <button
            onClick={handleSubmit}
            disabled={isSubmitting}
            className="px-4 py-2 bg-blue-600 rounded hover:bg-blue-700 disabled:opacity-50"
          >
            {isSubmitting ? "Submitting..." : "Submit Design"}
          </button>
        </div>
      </header>

      <div className="flex flex-1 overflow-hidden">
        <div className="flex-1">
          <SystemDesignCanvas />
        </div>

        <div className="w-80 border-l bg-gray-50 p-4 overflow-y-auto">
          <h2 className="font-bold text-lg mb-4">Design Panel</h2>

          <div className="space-y-4">
            <div>
              <h3 className="font-semibold mb-2">Instructions</h3>
              <ul className="text-sm text-gray-600 space-y-1">
                <li>Drag nodes from the toolbar</li>
                <li>Connect nodes by dragging between handles</li>
                <li>Click a node to select it</li>
                <li>Submit your design for evaluation</li>
              </ul>
            </div>

            {submissionResult && (
              <div className="p-3 bg-white rounded border">
                <h3 className="font-semibold mb-2">Result</h3>
                <pre className="text-xs overflow-auto">
                  {JSON.stringify(submissionResult, null, 2)}
                </pre>
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
