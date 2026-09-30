@extends('layout')

@section('content')
    <div class="card">
        <small>Order #{{ $order->id }}</small>
        <h1>{{ $order->product }}</h1>
        <div class="price">{{ number_format($order->amount, 0, ',', ' ') }} FCFA</div>
        <p>Status: <span class="status">{{ $order->status }}</span></p>
        @if (session('instructions'))<p>{{ session('instructions') }}</p>@endif
        @if ($order->status === 'pending')
            <p><small>Waiting for Yoon's confirmation. This page updates when Yoon's webhook arrives — refresh.</small></p>
        @endif
        <p><a href="{{ route('shop') }}">Back to the shop</a></p>
    </div>
@endsection
