-- Settlement (T+1). Each execution remembers its trade date and the sale proceeds it put in the
-- client's unsettled money, so the day can be checked against the clearing corporation and released.
ALTER TABLE orders ADD COLUMN trade_date date;
ALTER TABLE orders ADD COLUMN unsettled_paise bigint NOT NULL DEFAULT 0;
UPDATE orders SET trade_date = (filled_at AT TIME ZONE 'Asia/Kolkata')::date WHERE status = 'FILLED';
CREATE INDEX orders_by_trade_date ON orders (trade_date) WHERE status = 'FILLED';

-- what a client's short delivery cost them, once per settlement, client and share
CREATE TABLE close_outs (
    settlement_id  text NOT NULL,
    user_id        uuid NOT NULL,
    symbol         text NOT NULL,
    quantity       bigint NOT NULL,
    paise          bigint NOT NULL,
    trade_date     date NOT NULL,
    booked_at      timestamptz NOT NULL,
    PRIMARY KEY (settlement_id, user_id, symbol)
);

-- trade dates whose settlement has been completed for clients (proceeds released, shares delivered)
CREATE TABLE settled_days (
    trade_date     date PRIMARY KEY,
    settlement_id  text NOT NULL,
    completed_at   timestamptz NOT NULL
);
