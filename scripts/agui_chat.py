#!/usr/bin/env python3
"""
AG-UI 多轮交互式 SSE 测试客户端

用法：
  python3 scripts/agui_chat.py                                # 进入交互模式
  python3 scripts/agui_chat.py --thread my-test-001          # 自定义 thread_id
  python3 scripts/agui_chat.py --script scripts/scenario.txt # 批量脚本模式（每行一个 user 消息）

特性：
- 同一进程保持 thread_id 不变 → 服务端 session 自动多轮
- 实时解析并彩色打印 SSE 事件（TEXT/TOOL_CALL/ERROR）
- 自动等待 RUN_FINISHED 后才接受下一轮输入
- 单条消息超时（默认 180s）
- /quit 退出，/dump 打印本轮原始事件，/reset 换 thread_id
"""

import argparse
import json
import sys
import time
import uuid

import requests
import sseclient

# ANSI 颜色
C_GRAY = "\033[90m"
C_GREEN = "\033[32m"
C_YELLOW = "\033[33m"
C_BLUE = "\033[34m"
C_RED = "\033[31m"
C_CYAN = "\033[36m"
C_BOLD = "\033[1m"
C_END = "\033[0m"


def send_turn(base_url: str, thread_id: str, user_msg: str,
              tenant_id: str, user_id: str, timeout: int,
              confirm_results: list = None, auto_confirm: bool = True):
    """发送一轮对话，流式打印事件，返回 (full_text, events, elapsed, pending_confirms)。"""
    payload = {
        "thread_id": thread_id,
        "run_id": f"run-{uuid.uuid4().hex[:8]}",
        "messages": [{"role": "user", "content": user_msg}],
    }
    if confirm_results:
        payload["confirm_results"] = confirm_results
    headers = {
        "Content-Type": "application/json",
        "Accept": "text/event-stream",
        "X-Tenant-Id": tenant_id,
        "X-User-Id": user_id,
    }

    t0 = time.time()
    resp = requests.post(
        f"{base_url}/awp/v1/runs",
        json=payload, headers=headers, stream=True, timeout=timeout,
    )
    if resp.status_code != 200:
        print(f"{C_RED}HTTP {resp.status_code}: {resp.text[:200]}{C_END}")
        return "", [], time.time() - t0, []

    client = sseclient.SSEClient(resp)
    full_text_parts = []
    events = []
    current_text_id = None
    pending_confirms = []  # HITL 待确认列表

    for ev in client.events():
        try:
            obj = json.loads(ev.data)
        except json.JSONDecodeError:
            continue
        events.append({"event": ev.event, "data": obj})
        t = obj.get("type", ev.event)

        if t == "RUN_STARTED":
            print(f"{C_GRAY}● RUN_STARTED reply_id={obj.get('reply_id','')[:8]}{C_END}")
        elif t == "TEXT_MESSAGE_CONTENT":
            rid = obj.get("reply_id")
            if rid != current_text_id:
                if current_text_id is not None:
                    print()
                print(f"{C_GREEN}{C_BOLD}AI: {C_END}", end="", flush=True)
                current_text_id = rid
            delta = obj.get("delta", "")
            full_text_parts.append(delta)
            print(f"{C_GREEN}{delta}{C_END}", end="", flush=True)
        elif t == "TEXT_MESSAGE_END":
            if current_text_id is not None:
                print()
                current_text_id = None
        elif t == "TOOL_CALL_START":
            print(f"{C_BLUE}🔧 [{obj.get('tool_name','?')}] start{C_END}")
        elif t == "TOOL_CALL_END":
            pass  # 静默，避免噪音
        elif t == "TOOL_CALL_RESULT":
            state = obj.get("state", "")
            color = C_CYAN if state == "SUCCESS" else C_RED
            print(f"{color}   ↳ [{obj.get('tool_name','?')}] {state}{C_END}")
        elif t == "REQUIRE_USER_CONFIRM":
            tool_calls = obj.get("tool_calls", [])
            print(f"{C_YELLOW}⚠ HITL 确认请求 — {len(tool_calls)} 个工具待批准：{C_END}")
            for tc in tool_calls:
                print(f"{C_YELLOW}   • {tc.get('name','?')} (id={tc.get('id','')[:16]}…){C_END}")
                pending_confirms.append({
                    "tool_call_id": tc.get("id", ""),
                    "tool_name": tc.get("name", ""),
                    "confirmed": True,
                    "input": tc.get("input", {})
                })
        elif t == "RUN_FINISHED":
            elapsed = time.time() - t0
            print(f"{C_GRAY}● RUN_FINISHED ({elapsed:.1f}s, {len(events)} events){C_END}")
            return "".join(full_text_parts), events, elapsed, pending_confirms
        elif t == "RUN_ERROR":
            print(f"{C_RED}✗ ERROR: {obj.get('message','')}{C_END}")
        elif t == "ERROR":
            print(f"{C_RED}✗ ERROR: {obj}{C_END}")

    return "".join(full_text_parts), events, time.time() - t0, pending_confirms


def do_turn_with_auto_confirm(base_url, thread_id, user_msg, tenant_id, user_id, timeout, max_confirms=5):
    """发送一轮对话，遇到 HITL 自动批准并重新发送，最多重试 max_confirms 次。"""
    confirm_results = None
    for attempt in range(max_confirms + 1):
        text, events, elapsed, pending = send_turn(
            base_url, thread_id, user_msg, tenant_id, user_id, timeout,
            confirm_results=confirm_results)
        if not pending:
            return text, events, elapsed
        # 有待确认的工具调用 → 自动批准
        print(f"{C_YELLOW}  → 自动批准 {len(pending)} 个工具，重新发送...{C_END}")
        confirm_results = pending
        user_msg = ""  # 确认轮不需要新消息
        time.sleep(0.5)
    print(f"{C_RED}超过最大 HITL 自动确认次数 ({max_confirms}){C_END}")
    return text, events, elapsed


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="http://localhost:8080")
    ap.add_argument("--thread", default=f"chat-{uuid.uuid4().hex[:8]}")
    ap.add_argument("--tenant", default="test-tenant")
    ap.add_argument("--user", default="test-user")
    ap.add_argument("--timeout", type=int, default=240)
    ap.add_argument("--auto-confirm", action="store_true", default=True,
                    help="遇到 HITL 自动批准（默认开启）")
    ap.add_argument("--no-auto-confirm", dest="auto_confirm", action="store_false",
                    help="遇到 HITL 交互确认")
    ap.add_argument("--script", help="批量执行脚本（每行一个 user 消息，# 开头为注释）")
    args = ap.parse_args()

    print(f"{C_BOLD}AG-UI Chat Client{C_END}")
    print(f"  url={args.url}  thread={args.thread}  tenant={args.tenant}  user={args.user}")
    print(f"  指令: /quit /reset /dump")
    print()

    last_events = []
    thread_id = args.thread

    if args.script:
        with open(args.script) as f:
            messages = [ln.strip() for ln in f
                        if ln.strip() and not ln.strip().startswith("#")]
        for i, msg in enumerate(messages, 1):
            print(f"\n{C_YELLOW}─── Turn {i}/{len(messages)} ───{C_END}")
            print(f"{C_YELLOW}USER: {msg}{C_END}")
            text, events, elapsed = do_turn_with_auto_confirm(
                args.url, thread_id, msg, args.tenant, args.user, args.timeout)
            last_events = events
            time.sleep(1)
        return

    while True:
        try:
            msg = input(f"\n{C_YELLOW}You> {C_END}").strip()
        except (EOFError, KeyboardInterrupt):
            print()
            break
        if not msg:
            continue
        if msg in ("/quit", "/exit"):
            break
        if msg == "/reset":
            thread_id = f"chat-{uuid.uuid4().hex[:8]}"
            print(f"{C_GRAY}New thread_id={thread_id}{C_END}")
            continue
        if msg == "/dump":
            for e in last_events:
                print(json.dumps(e, ensure_ascii=False))
            continue

        text, events, elapsed = do_turn_with_auto_confirm(
            args.url, thread_id, msg, args.tenant, args.user, args.timeout)
        last_events = events


if __name__ == "__main__":
    main()
