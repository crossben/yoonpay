<?php

declare(strict_types=1);

namespace Yoon\Laravel;

use Illuminate\Support\ServiceProvider;
use RuntimeException;
use Yoon\Yoon;

final class YoonServiceProvider extends ServiceProvider
{
    public function register(): void
    {
        $this->mergeConfigFrom(__DIR__ . '/../../config/yoon.php', 'yoon');

        $this->app->singleton(Yoon::class, function ($app) {
            $config = $app['config']->get('yoon');
            if (empty($config['api_key'])) {
                throw new RuntimeException('Set YOON_API_KEY (from `yoon apps create <name>`).');
            }
            return new Yoon($config['url'], $config['api_key'], null, (float) $config['timeout']);
        });
    }

    public function boot(): void
    {
        $this->publishes([__DIR__ . '/../../config/yoon.php' => config_path('yoon.php')], 'yoon-config');
        $this->app['router']->aliasMiddleware('yoon.webhook', Http\Middleware\VerifyYoonWebhook::class);
    }
}
