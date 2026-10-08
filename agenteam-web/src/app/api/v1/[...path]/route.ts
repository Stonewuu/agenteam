import {proxyBackendRequest} from "@/lib/server/backend-proxy";

export const dynamic = "force-dynamic";

type ApiRouteContext = { params: Promise<{ path: string[] }> };

async function forward(request: Request, context: ApiRouteContext) {
  const {path} = await context.params;
  const query = new URL(request.url).search;
  const backendPath = `/api/v1/${path.map(encodeURIComponent).join("/")}${query}`;
  return proxyBackendRequest(request, backendPath);
}

export {forward as GET, forward as POST, forward as PUT, forward as PATCH, forward as DELETE};
