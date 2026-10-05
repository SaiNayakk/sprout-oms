# sprout-oms

Sprout's order management: customers' orders, the risk checks before them, and what executions do to
their holdings, intraday positions and money. Orders go to the
[Sprout Stock Exchange](https://github.com/SaiNayakk/sprout-exchange); money lives in the
[ledger](https://github.com/SaiNayakk/sprout-ledger).

**Products.** Delivery (`CNC`): buy with your own money, sell only what you hold. Intraday (`MIS`): 5x
leverage, short selling, and Sprout closes every position at 15:20 (₹50 + GST), or earlier if its loss
reaches 90% of the margin. Losses beyond the customer's money become dues, recovered from their next
deposit.

**Orders.** `MARKET` (with 3% price protection) or `LIMIT`; `REGULAR` while the market is open, `AMO`
while it is closed, sent at the next open.

**Charges** as a discount broker in India charges them: brokerage (none on delivery; intraday ₹20 or
0.03%), STT, exchange transaction charges, SEBI fees, stamp duty and GST, shown on every executed order.

Rules that keep the books right:

- **Money is blocked before an order goes anywhere**, and the order records exactly what is blocked for it. After every test, what the ledger holds for a customer equals what their working orders and open positions say.
- **Shares can't be sold twice.** Risk checks that depend on what else is working run under a per-customer lock.
- **The exchange is the truth about executions.** Its answer, its signed callback, or (when both are lost) the reconciler's question apply the same change, at most once.
- **Ledger entries are decided with the change that implies them** and written to an outbox in the same transaction, then posted in order under fixed idempotency keys. A hold whose answer was lost is undone safely, whichever way it went.

The tests drive these paths against stand-ins for accounts, market data, a faithful ledger and the
exchange, each of which can be steered or taken away.

**Settlement (T+1).** Every execution records its trade date and the sale proceeds it left unsettled.
The settlement back office reads a day's summary by Sprout's books (to check the clearing corporation's
obligation against), has short deliveries charged to the client, and, once the day has settled, makes
clients' sale proceeds cash and their bought shares delivered: once per trade date.

**Orders for customers.** Sprout's services can place a customer's order on their behalf (a plan's
monthly instalment), with the same checks and charges, tagged so it can be told apart (`sip:<plan>`).

## Part of Sprout

[Sprout](https://sainayakk.github.io/sprout-platform/) is a simulated brokerage built from scratch as
separate services, each with its own repository and contract. Architecture, environments and test
evidence live in [sprout-platform](https://github.com/SaiNayakk/sprout-platform); this service's API is
[`oms-v1.yaml`](https://github.com/SaiNayakk/sprout-contracts/blob/main/src/main/resources/sprout/contracts/openapi/oms-v1.yaml)
in sprout-contracts. It runs inside the **trading** host.

`./mvnw verify` runs the tests on a real Postgres (Docker needed), every JSON response checked against
the contract.

## License

MIT
