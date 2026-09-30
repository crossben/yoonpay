@extends('layout')

@section('content')
    <div class="card">
        <small>Yoon example shop</small>
        <h1>{{ $product['name'] }}</h1>
        <div class="price">{{ number_format($product['amount'], 0, ',', ' ') }} FCFA</div>

        <form method="post" action="{{ route('checkout') }}">
            @csrf
            <label for="phone">Phone</label>
            <input id="phone" name="phone" value="{{ old('phone', '+221 77 123 45 67') }}" required>
            <label for="method">Pay with</label>
            <select id="method" name="method">
                <option value="wave">Wave</option>
                <option value="orange_money">Orange Money</option>
                <option value="free_money">Free Money</option>
                <option value="card">Card</option>
            </select>
            <button type="submit">Buy</button>
        </form>
        @error('payment')<p class="error">{{ $message }}</p>@enderror
    </div>

    @if ($orders->isNotEmpty())
        <div class="card">
            <small>Recent orders</small>
            @foreach ($orders as $order)
                <p><a href="{{ route('orders.show', $order) }}">Order #{{ $order->id }}</a> — <span class="status">{{ $order->status }}</span></p>
            @endforeach
        </div>
    @endif
@endsection
