import { NextRequest, NextResponse } from "next/server";

/**
 * CopilotKit Thread Persistence API (stub)
 * CopilotKit 会尝试加载历史对话线程，这里返回空对象表示无历史线程。
 */
export async function GET(_req: NextRequest) {
  return NextResponse.json({ threads: [], nextCursor: null });
}
