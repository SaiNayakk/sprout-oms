-- Orders a Sprout service placed for the customer (a plan's instalment: sip:<planId>) carry a tag.
ALTER TABLE orders ADD COLUMN tag text;
