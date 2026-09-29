-- Applications: each app that talks to Yoon. API keys arrive with the public API.
CREATE TABLE applications (
    id         UUID        PRIMARY KEY,
    name       TEXT        NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- Shadow ledger. Double entry: every posting's entries sum to zero per currency.
-- Enforced HERE, by the database, at commit — not only by Java code.
-- ---------------------------------------------------------------------------
CREATE TABLE ledger_postings (
    id             BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    application_id UUID        NOT NULL REFERENCES applications (id),
    description    TEXT        NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ledger_entries (
    id         BIGINT  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    posting_id BIGINT  NOT NULL REFERENCES ledger_postings (id),
    account    TEXT    NOT NULL CHECK (account <> ''),
    currency   CHAR(3) NOT NULL,
    amount     BIGINT  NOT NULL CHECK (amount <> 0)
);

CREATE INDEX ledger_entries_posting_idx ON ledger_entries (posting_id);
CREATE INDEX ledger_entries_account_idx ON ledger_entries (account, currency);

-- Runs at COMMIT (deferred), once per inserted entry, so a posting can be written in
-- several INSERTs inside one transaction and is checked only when complete.
CREATE FUNCTION ledger_check_posting_balanced() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    entry_count INT;
    bad_currency CHAR(3);
BEGIN
    SELECT count(*) INTO entry_count FROM ledger_entries WHERE posting_id = NEW.posting_id;
    IF entry_count < 2 THEN
        RAISE EXCEPTION 'ledger posting % has % entries, needs at least 2', NEW.posting_id, entry_count
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT currency INTO bad_currency
    FROM ledger_entries
    WHERE posting_id = NEW.posting_id
    GROUP BY currency
    HAVING sum(amount) <> 0
    LIMIT 1;

    IF bad_currency IS NOT NULL THEN
        RAISE EXCEPTION 'ledger posting % does not balance in %', NEW.posting_id, bad_currency
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER ledger_entries_balanced
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_check_posting_balanced();

-- A posting with no entries at all would escape the entry trigger: check postings too.
CREATE FUNCTION ledger_check_posting_has_entries() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM ledger_entries WHERE posting_id = NEW.id) THEN
        RAISE EXCEPTION 'ledger posting % has no entries', NEW.id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER ledger_postings_have_entries
    AFTER INSERT ON ledger_postings
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_check_posting_has_entries();

-- Append-only: corrections are new postings, never edits.
CREATE FUNCTION ledger_forbid_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'the ledger is append-only (% on %)', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

CREATE TRIGGER ledger_postings_append_only
    BEFORE UPDATE OR DELETE ON ledger_postings
    FOR EACH ROW EXECUTE FUNCTION ledger_forbid_change();

CREATE TRIGGER ledger_entries_append_only
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION ledger_forbid_change();

-- ---------------------------------------------------------------------------
-- Idempotency keys. The row is inserted BEFORE any provider call (IN_PROGRESS);
-- the primary key makes a concurrent duplicate lose the race.
-- ---------------------------------------------------------------------------
CREATE TABLE idempotency_keys (
    application_id  UUID        NOT NULL REFERENCES applications (id),
    idempotency_key TEXT        NOT NULL CHECK (length(idempotency_key) BETWEEN 1 AND 255),
    request_hash    TEXT        NOT NULL,
    state           TEXT        NOT NULL CHECK (state IN ('IN_PROGRESS', 'COMPLETED')),
    response_status INT,
    response_body   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ,
    PRIMARY KEY (application_id, idempotency_key),
    CHECK ((state = 'COMPLETED') = (response_status IS NOT NULL AND completed_at IS NOT NULL))
);

CREATE INDEX idempotency_keys_created_idx ON idempotency_keys (created_at);
