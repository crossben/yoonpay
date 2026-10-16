/** Settings read from the environment. Values are never echoed in errors. */
export interface McpConfig {
  /** Base URL of the Yoon gateway, e.g. https://pay.example.com. */
  url: string;
  /** The application API key given to the assistant (`apps add-key <app>`). */
  apiKey: string;
  /** `YOON_MCP_WRITES=true`: list the few write tools (never money out). */
  writes: boolean;
  /** `YOON_MCP_SHOW_CONTACTS=true`: return phone numbers and e-mails unmasked. */
  showContacts: boolean;
}

export function readConfig(env: Record<string, string | undefined> = process.env): McpConfig {
  const url = env.YOON_URL?.trim();
  const apiKey = env.YOON_API_KEY?.trim();
  const missing = [!url && "YOON_URL", !apiKey && "YOON_API_KEY"].filter(Boolean);
  if (missing.length > 0) {
    throw new Error(`yoon-mcp: set ${missing.join(" and ")} in the MCP server's environment.`);
  }
  return {
    url: url!,
    apiKey: apiKey!,
    writes: flag(env.YOON_MCP_WRITES),
    showContacts: flag(env.YOON_MCP_SHOW_CONTACTS),
  };
}

function flag(value: string | undefined): boolean {
  return value?.trim().toLowerCase() === "true";
}
