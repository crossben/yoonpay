<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        Schema::create('orders', function (Blueprint $table) {
            $table->id();
            $table->string('product');
            $table->unsignedBigInteger('amount');       // XOF has no minor unit: 5000 = 5 000 FCFA
            $table->string('currency', 3)->default('XOF');
            $table->string('phone');
            $table->string('status')->default('pending'); // pending, paid, failed, expired
            $table->string('yoon_payment_id')->nullable()->unique();
            $table->timestamps();
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('orders');
    }
};
