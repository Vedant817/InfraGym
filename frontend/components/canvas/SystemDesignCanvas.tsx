"use client";

import { useRef, useState, type DragEvent, type FormEvent } from "react";
import ReactFlow, { Background, Controls, MiniMap, MarkerType, ReactFlowProvider, addEdge, useNodesState, useEdgesState, type Connection, type Edge, type ReactFlowInstance } from "reactflow";
import type { JsonObject, Topology } from "@/lib/contracts";
import { componentLabel, componentTypes, newNode, nodeLabel, serializeTopology } from "@/lib/topology";
import { ComponentNode, type FlowNodeData } from "../nodes/component-node";
import { NodeInspector } from "./node-inspector";

const nodeTypes = { component: ComponentNode };
// Defined here, outside the component, on purpose: React Flow warns and re-registers
// every node type when it is handed a new object, and a literal in the render body
// would be one on every render.
interface Props { initialTopology: Topology; disabled: boolean; onChange: (topology: Topology) => void; onDraft: (dirty: boolean) => void }
function Canvas({ initialTopology, disabled, onChange, onDraft }: Props) {
  const [nodes, setNodes, applyNodes] = useNodesState<FlowNodeData>(initialTopology.nodes.map((node) => ({ id: node.id, type: "component", position: node.position, data: { wire: node } })));
  const [edges, setEdges, applyEdges] = useEdgesState<JsonObject>(initialTopology.edges.map((edge) => ({ ...edge, type: "smoothstep", markerEnd: { type: MarkerType.ArrowClosed } })));
  const latestNodes = useRef(nodes);
  const latestEdges = useRef(edges);
  const [instance, setInstance] = useState<ReactFlowInstance<FlowNodeData> | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [draft, setDraft] = useState(false);
  const [source, setSource] = useState("");
  const [target, setTarget] = useState("");
  const [notice, setNotice] = useState("");
  const selectedNode = nodes.find((node) => node.id === selected);
  const locked = disabled || draft;

  function publish(nextNodes = latestNodes.current, nextEdges = latestEdges.current) {
    latestNodes.current = nextNodes;
    latestEdges.current = nextEdges;
    onChange(serializeTopology(nextNodes, nextEdges, initialTopology));
  }
  function add(type: string, position = { x: 80 + (nodes.length % 4) * 220, y: 80 + Math.floor(nodes.length / 4) * 120 }) {
    if (locked) return;
    const node = newNode(type, crypto.randomUUID(), position);
    const next = [...nodes, { id: node.id, type: "component", position, data: { wire: node } }];
    setNodes(next); setSelected(node.id); publish(next); setNotice(`Added ${componentLabel(type)}.`);
  }
  function connect(connection: Connection) {
    if (locked || !connection.source || !connection.target) return;
    const next = addEdge({ ...connection, id: crypto.randomUUID(), type: "smoothstep", markerEnd: { type: MarkerType.ArrowClosed } }, edges);
    setEdges(next); publish(nodes, next);
  }
  function drop(event: DragEvent<HTMLDivElement>) {
    event.preventDefault();
    const type = event.dataTransfer.getData("application/archforge-component");
    if (componentTypes.some((candidate) => candidate === type) && instance) add(type, instance.screenToFlowPosition({ x: event.clientX, y: event.clientY }));
  }
  function keyboardConnect(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!nodes.some((node) => node.id === source) || !nodes.some((node) => node.id === target)) return;
    connect({ source, target, sourceHandle: null, targetHandle: null });
    setNotice("Connection added.");
  }
  function removeSelected() {
    const nextNodes = nodes.filter((node) => node.id !== selected);
    const nextEdges = edges.filter((edge) => edge.source !== selected && edge.target !== selected);
    setNodes(nextNodes); setEdges(nextEdges); setSelected(null); publish(nextNodes, nextEdges);
  }
  function draftChanged(value: boolean) { setDraft(value); onDraft(value); }
  // A dangling endpoint is not reachable through nodes, so it is named by id rather
  // than rendering an empty arrow at the user.
  function endpointLabel(id: string) {
    const node = nodes.find((candidate) => candidate.id === id);
    return node ? nodeLabel(node.data.wire) : id;
  }

  return <div className="editor-grid">
    <aside className="palette panel" aria-label="Component palette">
      <h2>Components</h2><p className="muted">Drag onto the canvas or click to add.</p>
      <div className="palette-items">{componentTypes.map((type) => <button key={type} draggable={!locked} disabled={locked} onClick={() => add(type)}
        onDragStart={(event) => { event.dataTransfer.setData("application/archforge-component", type); event.dataTransfer.effectAllowed = "copy"; }}>
        <span aria-hidden="true">＋</span>{componentLabel(type)}
      </button>)}</div>
    </aside>
    <section className="canvas-panel" aria-label="System topology">
      <div className="canvas-summary"><span>{nodes.length} components / {edges.length} connections</span><span>Arrow keys move focused nodes · Delete removes selected elements</span></div>
      <div className="flow-surface" onDrop={drop} onDragOver={(event) => { event.preventDefault(); event.dataTransfer.dropEffect = "copy"; }}>
        <ReactFlow nodes={nodes} edges={edges} nodeTypes={nodeTypes} onInit={setInstance}
          onNodesChange={(changes) => {
            if (locked) { applyNodes(changes.filter((change) => change.type === "dimensions")); return; }
            applyNodes(changes);
            const removes = new Set(changes.filter((change) => change.type === "remove").map((change) => change.id));
            const nextNodes = latestNodes.current.filter((node) => !removes.has(node.id)).map((node) => {
              const movement = changes.find((change) => change.type === "position" && change.id === node.id);
              return movement?.type === "position" && movement.position ? { ...node, position: movement.position } : node;
            });
            if (changes.some((change) => change.type === "position" || change.type === "remove")) {
              const nextEdges = latestEdges.current.filter((edge) => !removes.has(edge.source) && !removes.has(edge.target));
              if (selected && removes.has(selected)) setSelected(null);
              setEdges(nextEdges); publish(nextNodes, nextEdges);
            }
          }}
          onEdgesChange={(changes) => {
            if (locked) return;
            applyEdges(changes);
            const removes = new Set(changes.filter((change) => change.type === "remove").map((change) => change.id));
            if (removes.size) publish(latestNodes.current, latestEdges.current.filter((edge) => !removes.has(edge.id)));
          }}
          onConnect={connect} onNodeClick={(_, node) => { if (!draft) setSelected(node.id); }}
          onPaneClick={() => { if (!draft) setSelected(null); }}
          nodesDraggable={!locked} nodesConnectable={!locked} elementsSelectable={!locked}
          deleteKeyCode={locked ? null : ["Backspace", "Delete"]} fitView minZoom={0.15} maxZoom={2}>
          <Background color="#c7d0dc" gap={20} /><Controls showInteractive={false} /><MiniMap pannable zoomable />
        </ReactFlow>
        {nodes.length === 0 && <p className="canvas-empty">Add a component from the palette to begin your topology.</p>}
      </div>
      <p className="sr-only" role="status">{notice}</p>
    </section>
    <aside className="inspector panel" aria-label="Topology inspector">
      <label>Inspect a component<select value={selected ?? ""} disabled={locked} onChange={(event) => setSelected(event.target.value || null)}><option value="">Select component</option>{nodes.map((node) => <option key={node.id} value={node.id}>{nodeLabel(node.data.wire)} ({node.id.slice(0, 8)})</option>)}</select></label>
      {selectedNode ? <NodeInspector key={selectedNode.id} node={selectedNode.data.wire} disabled={disabled} onDraft={draftChanged} onDelete={removeSelected}
        onUpdate={(wire) => { const next = nodes.map((node) => node.id === wire.id ? { ...node, data: { wire } } : node); setNodes(next); publish(next); }} />
        : <p className="muted">Select a component to edit its label and parameters.</p>}
      <hr />
      <form className="stack" onSubmit={keyboardConnect}>
        <h2>Add a connection</h2>
        <label>Source<select value={source} disabled={locked} required onChange={(event) => setSource(event.target.value)}><option value="">Select source</option>{nodes.map((node) => <option key={node.id} value={node.id}>{nodeLabel(node.data.wire)} ({node.id.slice(0, 8)})</option>)}</select></label>
        <label>Target<select value={target} disabled={locked} required onChange={(event) => setTarget(event.target.value)}><option value="">Select target</option>{nodes.map((node) => <option key={node.id} value={node.id}>{nodeLabel(node.data.wire)} ({node.id.slice(0, 8)})</option>)}</select></label>
        <button disabled={locked || nodes.length < 2}>Connect components</button>
      </form>
      {edges.length > 0 && <div className="stack"><h2>Connections</h2>{edges.map((edge: Edge<JsonObject>) => <div className="connection-row" key={edge.id}><span>{endpointLabel(edge.source)} → {endpointLabel(edge.target)}</span><button aria-label={`Remove connection ${endpointLabel(edge.source)} to ${endpointLabel(edge.target)}`} disabled={locked} onClick={() => { const next = edges.filter((candidate) => candidate.id !== edge.id); setEdges(next); publish(nodes, next); }}>Remove</button></div>)}</div>}
    </aside>
  </div>;
}
export function SystemDesignCanvas(props: Props) { return <ReactFlowProvider><Canvas {...props} /></ReactFlowProvider>; }
