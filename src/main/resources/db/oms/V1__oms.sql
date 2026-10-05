-- Orders, what customers own, and the money movements still to be booked in the ledger.

CREATE TABLE orders (
    id                  uuid PRIMARY KEY,
    user_id             uuid NOT NULL,
    idempotency_key     text NOT NULL,
    request_hash        text NOT NULL,
    symbol              text NOT NULL,
    side                text NOT NULL CHECK (side IN ('BUY', 'SELL')),
    quantity            int NOT NULL CHECK (quantity > 0),
    order_type          text NOT NULL CHECK (order_type IN ('MARKET', 'LIMIT')),
    limit_paise         bigint,
    protection_paise    bigint,
    product             text NOT NULL CHECK (product IN ('CNC', 'MIS')),
    variety             text NOT NULL CHECK (variety IN ('REGULAR', 'AMO')),
    status              text NOT NULL CHECK (status IN ('AMO_QUEUED', 'PENDING', 'OPEN', 'FILLED', 'CANCELLED', 'EXPIRED', 'REJECTED')),
    opening             boolean NOT NULL,                                       -- MIS: adds to a position (needs margin)
    hold_paise          bigint NOT NULL DEFAULT 0 CHECK (hold_paise >= 0),      -- what placing it asked the ledger to hold
    blocked_paise       bigint NOT NULL DEFAULT 0 CHECK (blocked_paise >= 0),   -- held in the ledger for this order now
    position_session    date,            -- the intraday position an MIS order trades (set when it reaches the exchange)
    fill_price_paise    bigint,
    trade_id            uuid,
    brokerage_paise     bigint,
    stt_paise           bigint,
    exchange_paise      bigint,
    sebi_paise          bigint,
    stamp_paise         bigint,
    gst_paise           bigint,
    realised_pnl_paise  bigint,
    auto_square_off     boolean NOT NULL DEFAULT false,
    rejection_code      text,
    rejection_message   text,
    reason              text,
    filled_at           timestamptz,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    UNIQUE (user_id, idempotency_key)
);

CREATE INDEX orders_by_user ON orders (user_id, created_at DESC);
CREATE INDEX orders_working ON orders (status, updated_at) WHERE status IN ('AMO_QUEUED', 'PENDING', 'OPEN');

-- Delivery holdings. cost is what the shares still held cost in all, so the average is cost / quantity.
CREATE TABLE holdings (
    user_id      uuid NOT NULL,
    symbol       text NOT NULL,
    quantity     int NOT NULL CHECK (quantity >= 0),
    t1_quantity  int NOT NULL CHECK (t1_quantity >= 0 AND t1_quantity <= quantity),
    cost_paise   bigint NOT NULL CHECK (cost_paise >= 0),
    PRIMARY KEY (user_id, symbol)
);

-- Intraday positions, one per user, symbol and session. quantity is signed (negative = short);
-- cost is the absolute value of what the open quantity cost; margin is held in the ledger for it.
CREATE TABLE positions (
    user_id            uuid NOT NULL,
    symbol             text NOT NULL,
    session_date       date NOT NULL,
    quantity           int NOT NULL,
    cost_paise         bigint NOT NULL CHECK (cost_paise >= 0),
    margin_paise       bigint NOT NULL CHECK (margin_paise >= 0),
    realised_pnl_paise bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, symbol, session_date)
);

CREATE INDEX positions_open ON positions (session_date) WHERE quantity <> 0;

-- Ledger entries decided but maybe not yet posted, posted in order until the ledger accepts them.
-- kind POST is an entry to post as is; UNDO_HOLD makes sure a hold whose fate is unknown ends at zero.
CREATE TABLE ledger_outbox (
    seq         bigserial PRIMARY KEY,
    key         text NOT NULL UNIQUE,
    kind        text NOT NULL CHECK (kind IN ('POST', 'UNDO_HOLD')),
    body        text NOT NULL,
    created_at  timestamptz NOT NULL,
    posted_at   timestamptz,
    attempts    int NOT NULL DEFAULT 0,
    last_error  text
);

CREATE INDEX ledger_outbox_due ON ledger_outbox (seq) WHERE posted_at IS NULL;

-- Customers who may owe Sprout (an intraday loss beyond their money), to recover from their cash.
CREATE TABLE dues (
    user_id    uuid PRIMARY KEY,
    since      timestamptz NOT NULL
);

CREATE TABLE exchange_events (
    event_id     uuid PRIMARY KEY,
    received_at  timestamptz NOT NULL
);
