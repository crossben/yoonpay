import type { ResponseError } from "../generated/src/runtime";

/**
 * An error answered by Yoon (application/problem+json), or a transport failure.
 *
 * - `problemCode` is Yoon's stable code, e.g. `no_provider_for_method`; `null` when Yoon
 *   could not be reached — then retry with the same idempotency key;
 *   `unreadable_response` when Yoon answered something this client could not read
 *   (not retryable).
 * - `problem` is the full problem document when Yoon sent one.
 * - `isRetryable()` is true when retrying with the same idempotency key is the right
 *   move: Yoon unreachable, busy (`idempotency_in_progress`) or failing (5xx).
 *
 * The API key never appears in the message, the stack or the problem document.
 */
export class YoonException extends Error {
  readonly httpStatus: number | null;
  readonly problemCode: string | null;
  readonly problem: Record<string, unknown> | null;

  constructor(
    message: string,
    options: {
      httpStatus?: number | null;
      problemCode?: string | null;
      problem?: Record<string, unknown> | null;
      cause?: unknown;
    } = {},
  ) {
    super(message, options.cause !== undefined ? { cause: options.cause } : undefined);
    this.name = "YoonException";
    this.httpStatus = options.httpStatus ?? null;
    this.problemCode = options.problemCode ?? null;
    this.problem = options.problem ?? null;
  }

  isRetryable(): boolean {
    return (
      this.problemCode === null ||
      this.problemCode === "idempotency_in_progress" ||
      (this.httpStatus !== null && this.httpStatus >= 500)
    );
  }

  /** Yoon did not answer (connection failed, timeout): retry with the same idempotency key. */
  static transport(cause: unknown, detail?: string): YoonException {
    const message = cause instanceof Error ? cause.message : String(cause);
    return new YoonException(`Yoon unreachable: ${detail ?? message}`, { cause });
  }

  /** Yoon answered 2xx but the body could not be read as JSON: not retryable. */
  static unreadable(httpStatus: number, cause: unknown): YoonException {
    const message = cause instanceof Error ? cause.message : String(cause);
    return new YoonException(`Unreadable answer from Yoon: ${message}`, {
      httpStatus,
      problemCode: "unreadable_response",
      cause,
    });
  }

  /** Yoon answered a non-2xx status; the body may or may not be a problem document. */
  static async fromResponse(response: Response): Promise<YoonException> {
    let body: unknown = null;
    try {
      body = JSON.parse(await response.text());
    } catch {
      // not a problem document (e.g. a proxy's HTML error page)
    }
    const problem =
      body !== null && typeof body === "object" ? (body as Record<string, unknown>) : null;
    const code = typeof problem?.code === "string" ? problem.code : null;
    const detail = typeof problem?.detail === "string" ? problem.detail : undefined;
    return new YoonException(detail ?? `Yoon answered HTTP ${response.status}`, {
      httpStatus: response.status,
      problemCode: code,
      problem,
    });
  }

  /** Maps an error thrown by the generated client into a {@link YoonException}. */
  static async fromGeneratedError(error: unknown): Promise<YoonException | undefined> {
    // `ResponseError.name` survives minification; instanceof breaks when two copies of the
    // generated runtime end up in one process (app bundles the core transitively).
    if (
      error instanceof Error &&
      (error.constructor.name === "ResponseError" || error.name === "ResponseError")
    ) {
      return YoonException.fromResponse((error as ResponseError).response);
    }
    if (error instanceof Error && error.constructor.name === "FetchError") {
      return YoonException.transport(error.cause ?? error);
    }
    return undefined;
  }
}
