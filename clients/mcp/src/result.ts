import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";
import { YoonException } from "@yoonpay/yoon";
import { shape } from "./shape";

/**
 * Turns tool output into an MCP result. Data is shaped (contacts masked, customer text marked
 * untrusted), then every text that leaves goes through `scrub`, so the API key cannot reach the
 * assistant even if some error message carried it.
 */
export class Results {
  constructor(
    private readonly apiKey: string,
    private readonly showContacts: boolean,
  ) {}

  ok(data: unknown): CallToolResult {
    const text = JSON.stringify(shape(data, this.showContacts), null, 2);
    return { content: [{ type: "text", text: this.scrub(text) }] };
  }

  /** Yoon's stable problem codes go back to the assistant; anything else is a generic message. */
  error(error: unknown): CallToolResult {
    const body =
      error instanceof YoonException
        ? {
            error: error.problemCode ?? "yoon_unavailable",
            http_status: error.httpStatus,
            retryable: error.isRetryable(),
            untrusted_text: typeof error.problem?.detail === "string" ? { detail: error.problem.detail } : undefined,
          }
        : { error: "tool_failed", untrusted_text: { detail: error instanceof Error ? error.message : String(error) } };
    return { isError: true, content: [{ type: "text", text: this.scrub(JSON.stringify(body)) }] };
  }

  /** Runs a tool body; any failure becomes an error result instead of a protocol error. */
  async run(fn: () => Promise<unknown>): Promise<CallToolResult> {
    try {
      return this.ok(await fn());
    } catch (error) {
      return this.error(error);
    }
  }

  scrub(text: string): string {
    return text.split(this.apiKey).join("[redacted]");
  }
}
