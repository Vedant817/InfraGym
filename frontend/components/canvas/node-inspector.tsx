"use client";
import { useState, type FormEvent } from "react";
import type { CanvasNode } from "@/lib/contracts";
import { componentLabel, dataWithoutLabel, nodeLabel, parseParameters, withLabel } from "@/lib/topology";
import { message } from "@/lib/format";

interface Props { node: CanvasNode; disabled: boolean; onUpdate: (node: CanvasNode) => void; onDraft: (dirty: boolean) => void; onDelete: () => void }
// Seeded from the wire's data.label, and never undefined: an undefined seed makes the
// input uncontrolled on first render and React warns when the user types into it.
const initialLabel = (node: CanvasNode) => nodeLabel(node);
const initialParameters = (node: CanvasNode) => JSON.stringify(dataWithoutLabel(node), null, 2);

export function NodeInspector({ node, disabled, onUpdate, onDraft, onDelete }: Props) {
  const [label, setLabel] = useState(() => initialLabel(node));
  const [parameters, setParameters] = useState(() => initialParameters(node));
  const [error, setError] = useState("");
  const [dirty, setDirty] = useState(false);
  function markDirty() { setDirty(true); onDraft(true); }
  function apply(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    try {
      // The label goes to data.label, where the design service reads it. A top-level
      // label is dropped on save, so an edit made there was discarded silently.
      onUpdate(withLabel({ ...node, data: parseParameters(parameters) }, label.trim()));
      setError(""); setDirty(false); onDraft(false);
    } catch (reason) { setError(message(reason)); }
  }
  function discard() {
    setLabel(initialLabel(node)); setParameters(initialParameters(node));
    setDirty(false); setError(""); onDraft(false);
  }
  return <form className="stack" onSubmit={apply}>
    <h2>Component parameters</h2>
    <p className="muted">{componentLabel(node.type)} · <code>{node.id}</code></p>
    <label>Label<input value={label} onChange={(event) => { setLabel(event.target.value); markDirty(); }} required pattern={".*\\S.*"} disabled={disabled} /></label>
    <label>Parameters (JSON object)<textarea className="code-input" rows={12} value={parameters} onChange={(event) => { setParameters(event.target.value); markDirty(); }} disabled={disabled} spellCheck={false} aria-describedby="parameter-help" /></label>
    <p className="muted" id="parameter-help">Use the parameter names supported by your compiler. The label is edited above, not here. Existing values are preserved; no defaults are added.</p>
    {dirty && <p className="notice warning">Apply or discard these parameter edits before selecting another component, saving, or running.</p>}
    {error && <p role="alert" className="notice error">{error}</p>}
    <div className="actions"><button className="primary" disabled={disabled || !dirty}>Apply parameters</button><button type="button" onClick={discard} disabled={disabled || !dirty}>Discard edits</button></div>
    <button type="button" className="danger" onClick={onDelete} disabled={disabled || dirty}>Delete component</button>
  </form>;
}
