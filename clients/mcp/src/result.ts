import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";
import { YoonException } from "@yoonpay/yoon";

/**
 * Turns tool output into an MCP result. Every text that leaves goes through `scrub`, so the API
 * key cannot reach the assistant even if some error message carried it.
 */
export class Results {
  constructor(private readonly apiKey: string) {}

  ok(data: unknown): CallToolResult {
    return { content: [{ type: "text", text: this.scrub(JSON.stringify(data, null, 2)) }] };
  }

  /** Yoon's stable problem codes go back to the assistant; anything else is a generic message. */
  error(error: unknown): CallToolResult {
    const body =
      error instanceof YoonException
        ? {
            error: error.problemCode ?? "yoon_unavailable",
            http_status: error.httpStatus,
            retryable: error.isRetryable(),
            detail: typeof error.problem?.detail === "string" ? error.problem.detail : undefined,
          }
        : { error: "tool_failed", detail: error instanceof Error ? error.message : String(error) };
    return { isError: true, content: [{ type: "text", text: this.scrub(JSON.stringify(body)) }] };
  }

  scrub(text: string): string {
    return text.split(this.apiKey).join("[redacted]");
  }
}
