<?php

use App\Http\Controllers\ShopController;
use Illuminate\Support\Facades\Route;

Route::get('/', [ShopController::class, 'index'])->name('shop');
Route::post('/checkout', [ShopController::class, 'checkout'])->name('checkout');
Route::get('/orders/{order}', [ShopController::class, 'show'])->name('orders.show');

// Yoon posts signed events here (YOON_APPS_<APP>_WEBHOOK_URL on the Yoon side).
Route::post('/yoon/webhook', [ShopController::class, 'webhook'])->middleware('yoon.webhook');
