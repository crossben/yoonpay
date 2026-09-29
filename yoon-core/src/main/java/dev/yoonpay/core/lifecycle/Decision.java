package dev.yoonpay.core.lifecycle;

/** What to do with a requested status change that is not illegal. */
public enum Decision {
    /** Apply the transition and record it. */
    APPLY,
    /** Record the attempt in the event log, leave the status unchanged (duplicate or late news). */
    IGNORE
}
