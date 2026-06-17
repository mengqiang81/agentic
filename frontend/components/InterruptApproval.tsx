"use client";

import { useEffect } from "react";
import { useLangGraphInterrupt } from "@copilotkit/react-core";

interface InterruptMetadata {
  name?: string;
  input?: Record<string, unknown>;
}

interface ToolCallInterrupt {
  id: string;
  reason: string;
  toolCallId: string;
  message: string;
  metadata?: InterruptMetadata;
}

/**
 * HITL 审批组件。
 *
 * 使用 CopilotKit 的 useLangGraphInterrupt hook 处理 AG-UI 标准 interrupt。
 * 当后端发送 CUSTOM(on_interrupt) 事件后 RUN_FINISHED 时，
 * CopilotKit 会触发此组件渲染，展示待确认工具信息并提供批准/拒绝按钮。
 */
export function InterruptApproval() {
  useLangGraphInterrupt<ToolCallInterrupt[]>({
    agentId: "agentic",
    enabled: ({ eventValue }) => {
      console.log("[InterruptApproval] enabled check, eventValue:", eventValue);
      return true;
    },
    render: ({ event, resolve }) => {
      console.log("[InterruptApproval] render called, event:", event);
      const interrupts = event.value || [];

      return (
        <div className="my-3 rounded-lg border border-amber-200 bg-amber-50 p-4 shadow-sm">
          <div className="mb-3 flex items-center gap-2">
            <span className="text-lg">⚠️</span>
            <h3 className="font-semibold text-amber-800">需要确认</h3>
          </div>

          {interrupts.map((interrupt, idx) => (
            <div
              key={interrupt.id || idx}
              className="mb-3 rounded border border-amber-100 bg-white p-3"
            >
              <div className="mb-1 text-sm font-medium text-gray-700">
                工具: <code className="rounded bg-gray-100 px-1.5 py-0.5 text-xs">
                  {interrupt.metadata?.name || interrupt.toolCallId || "unknown"}
                </code>
              </div>
              {interrupt.metadata?.input && (
                <details className="mt-1">
                  <summary className="cursor-pointer text-xs text-gray-500">
                    查看参数
                  </summary>
                  <pre className="mt-1 max-h-32 overflow-auto rounded bg-gray-50 p-2 text-xs text-gray-600">
                    {JSON.stringify(interrupt.metadata.input, null, 2)}
                  </pre>
                </details>
              )}
              {interrupt.message && (
                <p className="mt-1 text-sm text-gray-600">{interrupt.message}</p>
              )}
            </div>
          ))}

          <div className="flex gap-2">
            <button
              onClick={() => resolve(JSON.stringify({ approved: true }))}
              className="rounded-md bg-green-600 px-4 py-2 text-sm font-medium text-white hover:bg-green-700 transition-colors"
            >
              批准执行
            </button>
            <button
              onClick={() => resolve(JSON.stringify({ approved: false }))}
              className="rounded-md bg-red-600 px-4 py-2 text-sm font-medium text-white hover:bg-red-700 transition-colors"
            >
              拒绝
            </button>
          </div>
        </div>
      );
    },
  });

  useEffect(() => {
    console.log("[InterruptApproval] mounted");
    return () => console.log("[InterruptApproval] unmounted");
  }, []);

  return null; // hook 组件不渲染自身，只注册 interrupt handler
}
