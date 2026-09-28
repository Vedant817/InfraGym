"use client";

import { Handle, Position } from "reactflow";

export function LoadBalancerNode({ data }: { data: { label: string } }) {
  return (
    <div className="px-4 py-2 shadow-md rounded-md bg-white border-2 border-red-500 min-w-[150px]">
      <Handle type="target" position={Position.Top} />
      <div className="flex items-center">
        <div className="w-3 h-3 rounded-full bg-red-500 mr-2" />
        <div className="font-bold">{data.label}</div>
      </div>
      <div className="text-xs text-gray-500 mt-1">Load Balancer</div>
      <Handle type="source" position={Position.Bottom} />
    </div>
  );
}
