"use client";

import React, { useCallback, useState } from "react";
import ReactFlow, {
  Node,
  Edge,
  Background,
  Controls,
  MiniMap,
  addEdge,
  Connection,
  useNodesState,
  useEdgesState,
} from "reactflow";
import "reactflow/dist/style.css";
import { ServiceNode } from "../nodes/ServiceNode";
import { DatabaseNode } from "../nodes/DatabaseNode";
import { CacheNode } from "../nodes/CacheNode";
import { QueueNode } from "../nodes/QueueNode";
import { LoadBalancerNode } from "../nodes/LoadBalancerNode";

const nodeTypes = {
  service: ServiceNode,
  database: DatabaseNode,
  cache: CacheNode,
  queue: QueueNode,
  loadBalancer: LoadBalancerNode,
};

const initialNodes: Node[] = [
  {
    id: "1",
    type: "loadBalancer",
    position: { x: 250, y: 0 },
    data: { label: "Load Balancer" },
  },
  {
    id: "2",
    type: "service",
    position: { x: 100, y: 150 },
    data: { label: "API Service" },
  },
  {
    id: "3",
    type: "service",
    position: { x: 400, y: 150 },
    data: { label: "Worker Service" },
  },
  {
    id: "4",
    type: "cache",
    position: { x: 100, y: 300 },
    data: { label: "Redis Cache" },
  },
  {
    id: "5",
    type: "database",
    position: { x: 400, y: 300 },
    data: { label: "PostgreSQL" },
  },
];

const initialEdges: Edge[] = [
  { id: "e1-2", source: "1", target: "2" },
  { id: "e1-3", source: "1", target: "3" },
  { id: "e2-4", source: "2", target: "4" },
  { id: "e3-5", source: "3", target: "5" },
];

export function SystemDesignCanvas() {
  const [nodes, setNodes, onNodesChange] = useNodesState(initialNodes);
  const [edges, setEdges, onEdgesChange] = useEdgesState(initialEdges);
  const [selectedNode, setSelectedNode] = useState<Node | null>(null);

  const onConnect = useCallback(
    (params: Connection) => setEdges((eds) => addEdge(params, eds)),
    [setEdges]
  );

  const onNodeClick = useCallback((_: React.MouseEvent, node: Node) => {
    setSelectedNode(node);
  }, []);

  const onPaneClick = useCallback(() => {
    setSelectedNode(null);
  }, []);

  const addNode = useCallback(
    (type: string) => {
      const newNode: Node = {
        id: `${Date.now()}`,
        type,
        position: { x: Math.random() * 500, y: Math.random() * 500 },
        data: { label: `New ${type}` },
      };
      setNodes((nds) => nds.concat(newNode));
    },
    [setNodes]
  );

  const deleteSelected = useCallback(() => {
    if (selectedNode) {
      setNodes((nds) => nds.filter((n) => n.id !== selectedNode.id));
      setEdges((eds) =>
        eds.filter(
          (e) => e.source !== selectedNode.id && e.target !== selectedNode.id
        )
      );
      setSelectedNode(null);
    }
  }, [selectedNode, setNodes, setEdges]);

  return (
    <div className="w-full h-full flex flex-col">
      <div className="flex gap-2 p-2 bg-gray-100 border-b">
        <button
          onClick={() => addNode("service")}
          className="px-3 py-1 bg-blue-500 text-white rounded hover:bg-blue-600"
        >
          + Service
        </button>
        <button
          onClick={() => addNode("database")}
          className="px-3 py-1 bg-green-500 text-white rounded hover:bg-green-600"
        >
          + Database
        </button>
        <button
          onClick={() => addNode("cache")}
          className="px-3 py-1 bg-yellow-500 text-white rounded hover:bg-yellow-600"
        >
          + Cache
        </button>
        <button
          onClick={() => addNode("queue")}
          className="px-3 py-1 bg-purple-500 text-white rounded hover:bg-purple-600"
        >
          + Queue
        </button>
        <button
          onClick={() => addNode("loadBalancer")}
          className="px-3 py-1 bg-red-500 text-white rounded hover:bg-red-600"
        >
          + Load Balancer
        </button>
        {selectedNode && (
          <button
            onClick={deleteSelected}
            className="px-3 py-1 bg-red-500 text-white rounded hover:bg-red-600 ml-auto"
          >
            Delete Selected
          </button>
        )}
      </div>
      <div className="flex-1">
        <ReactFlow
          nodes={nodes}
          edges={edges}
          onNodesChange={onNodesChange}
          onEdgesChange={onEdgesChange}
          onConnect={onConnect}
          onNodeClick={onNodeClick}
          onPaneClick={onPaneClick}
          nodeTypes={nodeTypes}
          fitView
        >
          <Background />
          <Controls />
          <MiniMap />
        </ReactFlow>
      </div>
    </div>
  );
}
