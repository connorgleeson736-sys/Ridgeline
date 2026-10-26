import handler from "vinext/server/fetch-handler";
import { runWithConnectorBinding } from "../lib/connector-context";
import type { ConnectorBinding } from "../lib/connector-contract.mjs";
import { lidarStorage } from "../lib/lidar-storage.mjs";
import { retiredViewerResponse } from "../lib/retired-viewers.mjs";

export default {
  async fetch(request: Request, env: Cloudflare.Env, ctx: ExecutionContext<{ CONNECTORS?: ConnectorBinding }>) {
    const url = new URL(request.url);
    const retired = retiredViewerResponse(url);
    if (retired) return retired;
    if (url.pathname.startsWith('/api/lidar/')) return lidarStorage(request, env);
    // Serve the planner directly; retired viewers redirect above. No framework
    // UI or hydration is introduced into the planner.
    if (env.ASSETS) {
      if (url.pathname === '/') url.pathname = '/index.html';
      const response = await env.ASSETS.fetch(new Request(url, request));
      // Entry documents must discover the current versioned renderer imports.
      // Keep long-lived vendor assets cacheable through their existing URLs.
      if (url.pathname === '/index.html' || url.pathname === '/viewer.mjs') {
        const headers = new Headers(response.headers);
        headers.set('Cache-Control', 'no-cache, must-revalidate');
        return new Response(response.body, { status: response.status, statusText: response.statusText, headers });
      }
      return response;
    }
    let binding = ctx.props?.CONNECTORS;
    // Local preview emulates the same request-scoped capability. This branch and
    // the auxiliary service binding are absent from production builds.
    if (import.meta.env.DEV && !binding && env.CONNECTORS) {
      const preview = env.CONNECTORS;
      const expiresAt = Date.now() + 60_000;
      binding = {
        async getContext() {
          if (Date.now() >= expiresAt) return { status: "request_context_expired" };
          return preview.getContext?.() ?? { status: "binding_unavailable" };
        },
        async invoke(connectorId, actionName, args) {
          if (Date.now() >= expiresAt) {
            return { status: "request_context_expired", message: "This request has expired. Please try again." };
          }
          return preview.invoke(connectorId, actionName, args);
        },
      };
    }
    return runWithConnectorBinding(binding, () => handler.fetch(request, env, ctx));
  },
};
