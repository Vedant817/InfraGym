import { allowedRoute } from "@/lib/proxy-policy";

async function proxy(request: Request, context: { params: Promise<{ path: string[] }> }) {
  const { path } = await context.params;
  if (!allowedRoute(path, request.method)) return Response.json({ message: "Unsupported API route." }, { status: 404 });
  const authorization = request.headers.get("authorization");
  if (!authorization?.startsWith("Basic ")) return Response.json({ message: "Basic authentication is required." }, { status: 401 });
  const origin = process.env.API_GATEWAY_URL ?? "http://localhost:8080";
  const url = new URL(`/api/v1/${path.map(encodeURIComponent).join("/")}`, origin);
  if (path[2] === "metrics") url.searchParams.set("metric", "latency_ms");
  try {
    const upstream = await fetch(url, {
      method: request.method,
      headers: {
        Authorization: authorization, "Content-Type": "application/json", Accept: "application/json",
        ...(request.headers.has("if-match") ? { "If-Match": request.headers.get("if-match")! } : {}),
      },
      body: ["POST", "PUT"].includes(request.method) ? await request.text() : undefined,
      cache: "no-store", redirect: "error", signal: AbortSignal.any([request.signal, AbortSignal.timeout(30_000)]),
    });
    return new Response(upstream.status === 204 ? null : upstream.body, {
      status: upstream.status,
      headers: {
        "Content-Type": upstream.headers.get("content-type") ?? "application/json", "Cache-Control": "no-store",
        ...(upstream.headers.has("etag") ? { ETag: upstream.headers.get("etag")! } : {}),
      },
    });
  } catch {
    return Response.json({ message: "API gateway is unavailable. Check its connection and try again." }, { status: 502 });
  }
}
export { proxy as GET, proxy as POST, proxy as PUT, proxy as DELETE };
