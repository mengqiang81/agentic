"use client";

import { CopilotKit } from "@copilotkit/react-core";
import { CopilotChat } from "@copilotkit/react-ui";
import "@copilotkit/react-ui/styles.css";
import { InterruptApproval } from "@/components/InterruptApproval";

export default function Home() {
  return (
    <CopilotKit runtimeUrl="/api/copilotkit" agent="agentic">
      <div className="h-screen flex flex-col">
        <header className="border-b px-6 py-3 flex items-center gap-3 bg-white">
          <h1 className="text-lg font-semibold">Agentic</h1>
          <span className="text-sm text-gray-500">通用智能体平台</span>
        </header>
        <main className="flex-1 overflow-hidden">
          <InterruptApproval />
          <CopilotChat
            labels={{
              title: "Agentic",
              initial: "你好！我是 Agentic 智能体，有什么可以帮你的？",
              placeholder: "输入消息...",
            }}
            className="h-full"
          />
        </main>
      </div>
    </CopilotKit>
  );
}
