<?php

return [
    // Where your Yoon instance runs, e.g. https://pay.example.com
    'url' => env('YOON_URL', 'http://localhost:8080'),

    // This application's API key (yk_…), from `yoon apps create <name>`.
    'api_key' => env('YOON_API_KEY'),

    // The secret Yoon signs webhooks with (YOON_APPS_<APP>_WEBHOOK_SECRET on the Yoon side).
    'webhook_secret' => env('YOON_WEBHOOK_SECRET'),

    'timeout' => (float) env('YOON_TIMEOUT', 30),

    // How long the webhook middleware remembers delivered event ids, to drop duplicates.
    'dedupe_seconds' => 3 * 24 * 3600,
];
