import type { FetchAPI } from "../../generated/src/runtime";

/**
 * Fetch wrapper adding Yoon's timeouts without hiding retries (there are none):
 * - `connectTimeoutMs` (default 5 000): until the response headers arrive;
 * - `timeoutMs` (default 30 000): the whole exchange, headers plus body read.
 *
 * Works on any runtime with `fetch` and `AbortController` (Node ≥ 18, Bun, Deno, edge);
 * `AbortSignal.any` is deliberately not used so the Node 18 floor holds. A timeout is a
 * lost request: it surfaces as a retryable `YoonException` (`problemCode === null`).
 */
export function timedFetch(fetchImpl: FetchAPI, timeoutMs: number, connectTimeoutMs: number): FetchAPI {
  return async (input, init) => {
    const controller = new AbortController();
    const external = init?.signal ?? null;
    const onExternalAbort = () => controller.abort(external?.reason);
    if (external) {
      if (external.aborted) controller.abort(external.reason);
      else external.addEventListener("abort", onExternalAbort);
    }

    // Abort with a TimeoutError-shaped reason so callers can tell a timeout from a refusal.
    const timeoutReason = new DOMException("The request timed out", "TimeoutError");
    const connectReason = new DOMException("The connection timed out", "TimeoutError");

    const overall = setTimeout(() => controller.abort(timeoutReason), timeoutMs);
    let connect: ReturnType<typeof setTimeout> | undefined = setTimeout(
      () => controller.abort(connectReason),
      connectTimeoutMs,
    );
    // Timers must not keep a Node process alive on their own.
    (overall as unknown as { unref?: () => void }).unref?.();
    (connect as unknown as { unref?: () => void }).unref?.();

    try {
      const response = await fetchImpl(input, { ...init, signal: controller.signal });
      clearTimeout(connect);
      connect = undefined;
      return response;
    } finally {
      clearTimeout(overall);
      if (connect !== undefined) clearTimeout(connect);
      if (external) external.removeEventListener("abort", onExternalAbort);
    }
  };
}
