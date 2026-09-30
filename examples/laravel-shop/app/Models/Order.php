<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;

class Order extends Model
{
    protected $fillable = ['product', 'amount', 'currency', 'phone', 'status', 'yoon_payment_id'];
}
