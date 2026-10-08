-- What is still in transit is what was bought on days that haven't settled. Until now a settled day released it by the
-- depository's net delivery, so a customer who bought one share and sold another the same day (net: nothing) kept the
-- bought share in transit forever and the books showed one share fewer delivered than the depository holds.
-- This puts every holding right, by the rule settlement now follows.
UPDATE holdings h SET t1_quantity = LEAST(h.quantity, COALESCE((
    SELECT SUM(o.quantity) FROM orders o
    WHERE o.user_id = h.user_id AND o.symbol = h.symbol AND o.status = 'FILLED' AND o.product = 'CNC'
      AND o.side = 'BUY' AND o.trade_date IS NOT NULL
      AND o.trade_date NOT IN (SELECT trade_date FROM settled_days)), 0))
WHERE h.t1_quantity <> LEAST(h.quantity, COALESCE((
    SELECT SUM(o.quantity) FROM orders o
    WHERE o.user_id = h.user_id AND o.symbol = h.symbol AND o.status = 'FILLED' AND o.product = 'CNC'
      AND o.side = 'BUY' AND o.trade_date IS NOT NULL
      AND o.trade_date NOT IN (SELECT trade_date FROM settled_days)), 0));
