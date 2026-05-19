"""
mcp_client.py
─────────────
Interactive CLI client that:
  1. Connects to the Java MCP server via HTTP/SSE
  2. Discovers available tools
  3. Sends natural-language questions to Qwen2.5 (via Ollama)
  4. Executes tool calls returned by the LLM in a ReAct loop
  5. Returns the final answer to the user

Usage:
  python mcp_client.py
  python mcp_client.py --server http://localhost:8080 --model qwen2.5:14b

Requirements:
  pip install mcp ollama rich
"""

import argparse
import asyncio
import json
import sys
from typing import Any

import ollama
from mcp import ClientSession
from mcp.client.sse import sse_client
from rich.console import Console
from rich.markdown import Markdown
from rich.panel import Panel
from rich.prompt import Prompt
from rich.table import Table

console = Console()

# ─────────────────────────────────────────────────────────────────────────────
# MCP ↔ Ollama bridge
# ─────────────────────────────────────────────────────────────────────────────

def mcp_tools_to_ollama(mcp_tools) -> list[dict]:
    """Convert MCP tool objects to Ollama's tool format (OpenAI-compatible)."""
    result = []
    for t in mcp_tools:
        schema = t.inputSchema if hasattr(t, "inputSchema") else {}
        # inputSchema is already a dict from the MCP SDK
        if hasattr(schema, "__dict__"):
            schema = dict(schema)
        result.append({
            "type": "function",
            "function": {
                "name": t.name,
                "description": t.description or "",
                "parameters": schema,
            },
        })
    return result


async def call_mcp_tool(session: ClientSession, name: str, arguments: dict) -> str:
    """Call a tool on the MCP server and return the text result."""
    console.print(f"  [dim]→ calling tool [bold]{name}[/bold] {json.dumps(arguments)}[/dim]")
    result = await session.call_tool(name, arguments)
    # Extract text from the first TextContent block
    if result.content:
        return result.content[0].text if hasattr(result.content[0], "text") else str(result.content[0])
    return "{}"


async def react_loop(
    session: ClientSession,
    ollama_tools: list[dict],
    user_message: str,
    model: str,
    max_iterations: int = 12,
) -> str:
    """
    ReAct loop:
      repeat {
        call LLM → may return tool_calls
        execute each tool → append results to messages
      } until LLM returns a plain text answer or max_iterations reached
    """
    messages = [
        {
            "role": "system",
            "content": (
                "You are a helpful database assistant with tools to read and write "
                "to a relational database. "
                "READ workflow: list_tables → describe_table → search_column / "
                "get_rows_where / execute_sql → answer. "
                "UPDATE workflow: (1) search_column or get_rows_where to confirm the "
                "target row exists, (2) update_row with exact where_column/where_value, "
                "(3) verify with get_rows_where, (4) report rows_affected and new value. "
                "Never guess column names — call describe_table first if unsure. "
                "Give a concise final answer once you have confirmed the result."
            ),
        },
        {"role": "user", "content": user_message},
    ]

    for iteration in range(max_iterations):
        console.print(f"  [dim]iteration {iteration + 1}[/dim]")

        response = ollama.chat(
            model=model,
            messages=messages,
            tools=ollama_tools,
        )
        msg = response["message"]
        messages.append(msg)

        # No tool calls → final answer
        if not msg.get("tool_calls"):
            return msg.get("content", "")

        # Execute each tool call
        for tc in msg["tool_calls"]:
            fn = tc["function"]
            tool_result = await call_mcp_tool(session, fn["name"], fn.get("arguments", {}))
            messages.append({
                "role": "tool",
                "content": tool_result,
            })

    return "Reached max iterations without a final answer."


# ─────────────────────────────────────────────────────────────────────────────
# Pretty-printing helpers
# ─────────────────────────────────────────────────────────────────────────────

def print_tools_table(tools) -> None:
    table = Table(title="Available MCP Tools", show_lines=True)
    table.add_column("Tool", style="bold cyan", no_wrap=True)
    table.add_column("Description")
    for t in tools:
        table.add_row(t.name, t.description or "")
    console.print(table)


# ─────────────────────────────────────────────────────────────────────────────
# Main REPL
# ─────────────────────────────────────────────────────────────────────────────

async def run_repl(server_url: str, model: str) -> None:
    console.print(
        Panel.fit(
            f"[bold green]MCP ↔ Qwen2.5 SQL Client[/]\n"
            f"Server : [cyan]{server_url}[/]\n"
            f"Model  : [cyan]{model}[/]\n"
            f"Type [bold]exit[/] or [bold]quit[/] to stop. [bold]/tools[/] to list tools.",
            border_style="green",
        )
    )

    sse_url = server_url.rstrip("/") + "/sse"

    async with sse_client(url=sse_url) as (read, write):
        async with ClientSession(read, write) as session:
            await session.initialize()
            console.print("[green]✓ Connected to MCP server[/]")

            tools_response = await session.list_tools()
            mcp_tools = tools_response.tools
            ollama_tools = mcp_tools_to_ollama(mcp_tools)
            console.print(f"[green]✓ Discovered {len(mcp_tools)} tools[/]\n")

            while True:
                try:
                    user_input = Prompt.ask("[bold blue]You[/]")
                except (EOFError, KeyboardInterrupt):
                    console.print("\n[yellow]Goodbye![/]")
                    break

                if not user_input.strip():
                    continue

                if user_input.strip().lower() in {"exit", "quit"}:
                    console.print("[yellow]Goodbye![/]")
                    break

                if user_input.strip().lower() == "/tools":
                    print_tools_table(mcp_tools)
                    continue

                with console.status("[bold green]Thinking…[/]"):
                    try:
                        answer = await react_loop(
                            session, ollama_tools, user_input, model
                        )
                    except Exception as exc:
                        console.print(f"[red]Error: {exc}[/]")
                        continue

                console.print("\n[bold green]Assistant[/]")
                console.print(Markdown(answer))
                console.print()


# ─────────────────────────────────────────────────────────────────────────────
# Entry point
# ─────────────────────────────────────────────────────────────────────────────

def main() -> None:
    parser = argparse.ArgumentParser(
        description="MCP client for the Java SQL Server gateway, powered by Qwen2.5"
    )
    parser.add_argument(
        "--server",
        default="http://localhost:8094",
        help="Base URL of the MCP server (default: http://localhost:8080)",
    )
    parser.add_argument(
        "--model",
        default="qwen2.5",
        help="Ollama model to use (default: qwen2.5). Use qwen2.5:14b for better accuracy.",
    )
    args = parser.parse_args()

    asyncio.run(run_repl(args.server, args.model))


if __name__ == "__main__":
    main()
