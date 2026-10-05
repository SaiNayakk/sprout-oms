-- When an order was last sent to the exchange. The reconciler gives up on an order the exchange never
-- got 30 s after this; updated_at can't be used, because asking about the order touches it.
ALTER TABLE orders ADD COLUMN sent_at timestamptz;
UPDATE orders SET sent_at = created_at WHERE status IN ('PENDING', 'OPEN');
