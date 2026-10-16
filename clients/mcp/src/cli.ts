#!/usr/bin/env node
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { readConfig } from "./config";
import { createServer } from "./server";

// stdout carries the protocol: anything human goes to stderr, and never a setting's value.
try {
  const config = readConfig();
  await createServer(config).connect(new StdioServerTransport());
  console.error(`yoon-mcp: ready (writes ${config.writes ? "on" : "off"})`);
} catch (error) {
  console.error(error instanceof Error ? error.message : "yoon-mcp: failed to start");
  process.exit(1);
}
