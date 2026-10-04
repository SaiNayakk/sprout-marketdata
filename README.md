# sprout-marketdata

Prices for **Sprout**, a simulated end-to-end brokerage: instruments, quotes, candles and a live price stream. Architecture, environments and test evidence live in [sprout-platform](https://github.com/SaiNayakk/sprout-platform); the API is [`marketdata-v1.yaml`](https://github.com/SaiNayakk/sprout-contracts/blob/main/src/main/resources/sprout/contracts/openapi/marketdata-v1.yaml) in sprout-contracts.

## A simulated market

The prices are **not real**. The market is 20 fictional companies across five sectors, plus an index (`SPROUT20`). It's simulated rather than replayed so it can be shown publicly, never needs re-recording, runs every weekday forever, and can be told to crash on demand for testing.

It is built to behave like a real market:

- **Market + sector + company.** Each stock's move is a market factor scaled by its beta, a factor shared with its sector, and noise of its own. Banks move together; nearly everything falls on a crash day.
- **Days have character.** Most are normal; some trend, some are volatile, and rarely there's a crash or a rally. Any day can be forced to a scenario in configuration.
- **Intraday shape.** Part of each day's move happens overnight (the opening gap). The rest is a minute-by-minute random walk pinned to the day's close, busier at the open and close than at lunch. Volume is U-shaped. Now and then a stock jumps 2-6% on "news".
- **Ticks add up.** Each minute is filled with individual trades whose first and last prices are the minute's open and close, which touch its high and low, and whose sizes sum to its volume.
- **Deterministic.** The same seed gives the same market. Days follow on from a fixed origin (1 Jan 2024), so the two-year daily history is exactly the minutes added up.

The default seed (12) gives a believable backdrop for an app about investing steadily: roughly +38% from 2024 to October 2026, one correction of about 23%, and 15 of 20 stocks up, so there are losers to learn diversification from.

## The market clock

| `MARKETDATA_CLOCK` | Behaviour |
|---|---|
| `WALL` (default) | Follows real Indian time: open 09:15-15:30 IST on weekdays, closed otherwise. |
| `ACCELERATED` | Runs `MARKETDATA_SPEED` times faster from `MARKETDATA_START_DATE`, one session after another with a short closed pause. For demos, pre-prod and tests. |

The service **never reveals the future**: a minute's ticks are released only as market time reaches them, and its candle only once the minute is over.

If the service falls behind (the phone slept, the process was paused) it skips ahead silently instead of flooding clients with stale ticks, and every open stream gets a fresh `market` and `quote` snapshot.

## Streaming

`GET /v1/stream?symbols=...` is Server-Sent Events: a `market` event and a `quote` per symbol on connect, then a `tick` per price change, `market` on state changes, and a heartbeat comment every 15 s.

Each client has its own virtual thread and, per symbol, room for **one** pending tick. A slow client therefore gets the latest price rather than a growing backlog (conflation), and can never slow the market or use more memory. `seq` only ever increases on the wire.

## Events

Every tick is also published to NATS as a `marketdata.tick` v1 event on `md.tick.<SYMBOL>`. NATS is optional: the service starts without it and reconnects in the background. While NATS is down, events are dropped and counted (`md.nats.ticks{result=dropped}`), never queued: a tick is superseded within seconds, so a backlog of stale prices would be worse than a gap.

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `MARKETDATA_PORT` / `MARKETDATA_BIND` | `8103` / `127.0.0.1` | Listen address (only the gateway calls it) |
| `MARKETDATA_CLOCK` | `WALL` | See above |
| `MARKETDATA_SPEED` | `1` | Market seconds per real second (`ACCELERATED`) |
| `MARKETDATA_START_DATE` | today | First session (`ACCELERATED`) |
| `MARKETDATA_SEED` | `12` | Which simulated market |
| `MARKETDATA_MAX_STREAMS` | `1000` | Open streams before answering `503` |
| `MARKETDATA_NATS_URL` | empty (off) | e.g. `nats://127.0.0.1:4222` |

No database: everything is regenerated from the seed at start-up (a few seconds).

## Health and metrics

`/actuator/health` is `DOWN` if the market clock has stalled for more than 30 s, and shows whether NATS is connected (NATS being down doesn't make the service unhealthy). Metrics include `md.engine.lag.ms`, `md.stream.subscribers`, `md.stream.events`, `md.stream.conflated` and `md.nats.ticks`.

## Tests

`./mvnw verify` (needs Docker for NATS):

- the simulator: deterministic, well-formed bars on the tick grid, each day opening from the last close, history equal to the minutes, a forced crash taking most stocks down together, ticks adding up to their minute;
- the engine: opening at 09:15, never revealing the future, `seq` rising by one, a full session closing and rolling to Monday with Friday's close as the reference, catching up silently after a sleep, and following real Indian time;
- the API against the contract, including errors and request ids;
- the stream over real HTTP, a slow client getting the latest price instead of 5,000 backlogged ticks, a vanished client being forgotten, and the stream limit;
- events on a real NATS matching the published schema, and the service staying healthy without NATS.

## License

MIT
