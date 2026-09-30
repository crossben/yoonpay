<?php

namespace App\Http\Controllers;

use App\Models\Order;
use Illuminate\Http\Request;
use Yoon\Laravel\Facades\Yoon;
use Yoon\YoonException;

class ShopController extends Controller
{
    private const PRODUCT = ['name' => 'Thiéboudienne for two', 'amount' => 5000, 'currency' => 'XOF'];

    public function index()
    {
        return view('shop', ['product' => self::PRODUCT, 'orders' => Order::latest()->take(5)->get()]);
    }

    public function checkout(Request $request)
    {
        $data = $request->validate([
            'phone' => ['required', 'string', 'max:20'],
            'method' => ['required', 'in:wave,orange_money,free_money,card'],
        ]);

        $order = Order::create([
            'product' => self::PRODUCT['name'],
            'amount' => self::PRODUCT['amount'],
            'currency' => self::PRODUCT['currency'],
            'phone' => $data['phone'],
        ]);

        try {
            // The order id is the idempotency key: retrying this request can never charge twice.
            $payment = Yoon::createPayment([
                'amount' => $order->amount,
                'currency' => $order->currency,
                'country' => 'SN',
                'method' => $data['method'],
                'customer' => ['phone' => $order->phone],
                'reference' => 'order_' . $order->id,
                'description' => $order->product,
                'return_url' => route('orders.show', $order),
            ], 'order-' . $order->id);
        } catch (YoonException $e) {
            $order->update(['status' => 'failed']);
            return back()->withErrors(['payment' => $e->getMessage() . ($e->problemCode() ? " ({$e->problemCode()})" : '')]);
        }

        $order->update(['yoon_payment_id' => $payment->getId()]);

        if ($payment->getStatus() === 'failed') {
            $order->update(['status' => 'failed']);
            return redirect()->route('orders.show', $order);
        }

        // Hosted checkout (card, most wallets) or a push/USSD instruction to show.
        return $payment->getCheckoutUrl()
            ? redirect()->away($payment->getCheckoutUrl())
            : redirect()->route('orders.show', $order)->with('instructions', $payment->getInstructions());
    }

    public function show(Order $order)
    {
        return view('order', ['order' => $order->fresh()]);
    }

    /**
     * Yoon's events. The `yoon.webhook` middleware has already verified the signature and dropped
     * duplicates; delivery order is not guaranteed, so act on the payment's state, not the arrival order.
     */
    public function webhook(Request $request)
    {
        /** @var \Yoon\Webhook\Event $event */
        $event = $request->attributes->get('yoon_event');
        $payment = $event->object();

        if (($payment['object'] ?? null) === 'payment') {
            $status = match ($payment['status']) {
                'succeeded' => 'paid',
                'failed' => 'failed',
                'expired' => 'expired',
                default => null,
            };
            if ($status) {
                Order::where('yoon_payment_id', $payment['id'])->update(['status' => $status]);
            }
        }

        return response()->json(['ok' => true]);
    }
}
