import { HttpAgent } from "@ag-ui/client";
import {
  CopilotRuntime,
  ExperimentalEmptyAdapter,
  copilotRuntimeNextJSAppRouterEndpoint,
} from "@copilotkit/runtime";
import { NextRequest } from "next/server";

const BACKEND_URL = process.env.AGENTIC_BACKEND_URL || "http://localhost:8080";

export async function POST(req: NextRequest) {
  // 从前端请求提取租户信息，透传给后端
  const tenantId = req.headers.get("x-tenant-id") || "default";
  const userId = req.headers.get("x-user-id") || "anonymous";

  const agent = new HttpAgent({
    url: `${BACKEND_URL}/awp/v1/runs`,
    headers: { "X-Tenant-Id": tenantId, "X-User-Id": userId },
  });

  const runtime = new CopilotRuntime({
    agents: { agentic: agent },
  });

  const { handleRequest } = copilotRuntimeNextJSAppRouterEndpoint({
    runtime,
    serviceAdapter: new ExperimentalEmptyAdapter(),
    endpoint: "/api/copilotkit",
  });

  return handleRequest(req);
}
