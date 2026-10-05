"use client";
import { Handle, Position, type NodeProps } from "reactflow";
import type { CanvasNode } from "@/lib/contracts";
import { componentLabel, nodeLabel } from "@/lib/topology";
export interface FlowNodeData { wire: CanvasNode }
export function ComponentNode({ data, selected }: NodeProps<FlowNodeData>) {
  const label = nodeLabel(data.wire);
  return <div className={`component-node ${selected ? "selected" : ""}`}>
    <Handle type="target" position={Position.Left} aria-label={`Connect to ${label}`} />
    <span className="node-kind">{componentLabel(data.wire.type)}</span>
    <strong>{label}</strong><span className="node-id">{data.wire.id.slice(0, 8)}</span>
    <Handle type="source" position={Position.Right} aria-label={`Connect from ${label}`} />
  </div>;
}
