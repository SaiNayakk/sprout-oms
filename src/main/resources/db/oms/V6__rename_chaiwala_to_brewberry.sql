-- CHAIWALA is renamed BREWBERRY (Brewberry Beverages Ltd.): the old name carried connotations a demo
-- shouldn't have. The market keeps its place in the list, so its prices are unchanged; every service that
-- stores the symbol renames it in the same release.

UPDATE orders SET symbol = 'BREWBERRY' WHERE symbol = 'CHAIWALA';
UPDATE holdings SET symbol = 'BREWBERRY' WHERE symbol = 'CHAIWALA';
UPDATE positions SET symbol = 'BREWBERRY' WHERE symbol = 'CHAIWALA';
UPDATE close_outs SET symbol = 'BREWBERRY' WHERE symbol = 'CHAIWALA';
UPDATE ledger_outbox SET body = replace(body, 'CHAIWALA', 'BREWBERRY') WHERE body LIKE '%CHAIWALA%';
