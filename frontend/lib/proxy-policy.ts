export function allowedRoute(parts: string[], method: string): boolean {
  const [resource, resourceId, child, nodeId, leaf] = parts;
  if (resource === "designs") {
    if (parts.length === 1) return method === "GET" || method === "POST";
    if (!resourceId) return false;
    if (parts.length === 2) return method === "GET" || method === "DELETE";
    return parts.length === 3 && ((child === "topology" && method === "PUT") || (child === "submissions" && method === "POST"));
  }
  if (resource !== "submissions" || method !== "GET") return false;
  if (parts.length === 1) return true;
  if (!resourceId) return false;
  return parts.length === 2 || (parts.length === 3 && (child === "measurements" || child === "evaluation"))
    || (parts.length === 5 && child === "metrics" && !!nodeId && leaf === "series");
}
