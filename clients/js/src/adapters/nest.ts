import { Inject, Module, type DynamicModule, type NestMiddleware } from "@nestjs/common";
import type { NextFunction, Request, RequestHandler, Response } from "express";
import { Yoon } from "../Yoon";
import { yoonWebhook } from "./express";
import { MemoryEventStore, type YoonWebhookOptions, type YoonWebhookStore } from "../WebhookReceiver";

export const YOON_MODULE_OPTIONS = "YOON_MODULE_OPTIONS";
export const YOON_CLIENT = "YOON_CLIENT";

export interface YoonNestOptions {
  /** Base URL of your Yoon instance, e.g. https://pay.example.com */
  url: string;
  /** The application API key (yk_…). */
  apiKey: string;
  /**
   * The webhook secret (`YOON_APPS_<APP>_WEBHOOK_SECRET`). Required for
   * {@link YoonWebhookMiddleware}; optional when you only want the client.
   */
  webhookSecret?: string;
  /** Store for already-handled event ids (default: in-process {@link MemoryEventStore}). */
  webhookStore?: YoonWebhookStore;
  toleranceSeconds?: number;
  now?: () => number;
  timeoutMs?: number;
  connectTimeoutMs?: number;
  /** Forwarded to the client; see {@link YoonOptions.dangerouslyAllowBrowser}. */
  dangerouslyAllowBrowser?: boolean;
}

/**
 * Nest middleware wrapping `@yoonpay/yoon/express`. Apply it to the route Yoon posts to:
 *
 * ```ts
 * @Module({})
 * export class WebhookModule implements NestModule {
 *   configure(consumer: MiddlewareConsumer) {
 *     consumer.apply(YoonWebhookMiddleware).forRoutes("yoon/webhook");
 *   }
 * }
 * ```
 *
 * Nest parses JSON bodies by default, which breaks signature verification. Keep the raw
 * body for Yoon's route only, in `main.ts`:
 *
 * ```ts
 * const app = await NestFactory.create(AppModule);
 * app.use(
 *   express.json({ type: (req) => !req.url!.startsWith("/yoon/webhook") }),
 *   express.raw({ type: (req) => req.url!.startsWith("/yoon/webhook") }),
 * );
 * ```
 */
export class YoonWebhookMiddleware implements NestMiddleware {
  private readonly handle: RequestHandler;

  constructor(@Inject(YOON_MODULE_OPTIONS) options: YoonNestOptions) {
    if (!options.webhookSecret) {
      throw new Error("YoonWebhookMiddleware needs webhookSecret in YoonModule.forRoot(...)");
    }
    const webhookOptions: YoonWebhookOptions = {
      secret: options.webhookSecret,
      store: options.webhookStore ?? new MemoryEventStore(),
      toleranceSeconds: options.toleranceSeconds,
      now: options.now,
    };
    this.handle = yoonWebhook(webhookOptions);
  }

  use(req: Request, res: Response, next: NextFunction): void {
    this.handle(req, res, next);
  }
}

/**
 * Provides the {@link Yoon} client as `YOON_CLIENT` and the {@link YoonWebhookMiddleware}:
 *
 * ```ts
 * @Module({ imports: [YoonModule.forRoot(config)], exports: [YoonModule] })
 * export class YoonFeatureModule {}
 * ```
 */
@Module({})
export class YoonModule {
  static forRoot(options: YoonNestOptions): DynamicModule {
    return {
      module: YoonModule,
      providers: [
        { provide: YOON_MODULE_OPTIONS, useValue: options },
        {
          provide: YOON_CLIENT,
          inject: [YOON_MODULE_OPTIONS],
          useFactory: (moduleOptions: YoonNestOptions) =>
            new Yoon(moduleOptions.url, moduleOptions.apiKey, {
              timeoutMs: moduleOptions.timeoutMs,
              connectTimeoutMs: moduleOptions.connectTimeoutMs,
              dangerouslyAllowBrowser: moduleOptions.dangerouslyAllowBrowser,
            }),
        },
        YoonWebhookMiddleware,
      ],
      exports: [YOON_CLIENT, YoonWebhookMiddleware],
    };
  }
}
