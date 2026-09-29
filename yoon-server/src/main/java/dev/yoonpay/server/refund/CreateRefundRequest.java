package dev.yoonpay.server.refund;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** @param amount omitted: refund whatever is left of the payment */
public record CreateRefundRequest(@Positive Long amount, @Size(max = 500) String reason) {
}
