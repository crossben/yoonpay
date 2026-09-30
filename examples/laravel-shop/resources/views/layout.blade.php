<!doctype html>
<html lang="en">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Yoon example shop</title>
    <style>
        body { font-family: system-ui, sans-serif; background: #f4f2ee; color: #1d1b18; margin: 0; }
        main { max-width: 32rem; margin: 3rem auto; padding: 0 16px; }
        .card { background: #fff; border-radius: 12px; padding: 1.5rem; box-shadow: 0 2px 12px #0001; margin-bottom: 1rem; }
        .price { font-size: 1.75rem; font-weight: 700; }
        label { display: block; margin: .75rem 0 .25rem; font-size: .9rem; }
        input, select, button { font: inherit; padding: .55rem .7rem; border-radius: 8px; border: 1px solid #d8d2c6; width: 100%; box-sizing: border-box; }
        button { background: #1d1b18; color: #fff; border: 0; margin-top: 1rem; cursor: pointer; }
        .status { font-weight: 600; text-transform: uppercase; letter-spacing: .05em; }
        .error { color: #a02c2c; }
        small { color: #6b655b; }
    </style>
</head>
<body><main>@yield('content')</main></body>
</html>
