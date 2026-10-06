package app.sprout.marketdata.domain;

import app.sprout.marketdata.domain.Model.Bar;
import app.sprout.marketdata.domain.Model.Candle;
import app.sprout.marketdata.domain.Model.Instrument;
import app.sprout.marketdata.domain.Model.MarketListener;
import app.sprout.marketdata.domain.Model.MarketState;
import app.sprout.marketdata.domain.Model.MarketView;
import app.sprout.marketdata.domain.Model.Mode;
import app.sprout.marketdata.domain.Model.Quote;
import app.sprout.marketdata.domain.Model.Tick;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The market: a clock, the current trading session, and the ticks it produces.
 *
 * <p>Market time runs at {@code speed} times wall time ({@link ClockMode#ACCELERATED}), or is simply
 * the real time in India ({@link ClockMode#WALL}). A session goes PRE_OPEN, OPEN from 09:15 to 15:30,
 * then CLOSED, and the next trading day follows.
 *
 * <p><b>It never reveals the future.</b> A minute's ticks are released only as market time passes
 * them, and a minute's candle only once the minute is over.
 *
 * <p>One thread drives it, calling {@link #advance}. Readers on other threads see consistent,
 * immutable snapshots. If the driver falls behind (the machine slept, the process was paused), the
 * engine catches up silently instead of flooding clients with stale ticks, then tells listeners to
 * resync.
 */
public final class MarketEngine {

    public enum ClockMode { ACCELERATED, WALL }

    /**
     * {@code epoch}: ACCELERATED only. The real instant the first session began (its pre-open), so a
     * restarted engine resumes where the market would be now instead of starting over. Needs {@code startDate}.
     */
    public record Settings(ClockMode clockMode, double speed, LocalDate startDate, Duration preOpen,
                           Duration closedPause, int ticksPerMinute, Duration catchUpAfter, Instant epoch) {

        /** Without an epoch: the market begins when the engine starts. */
        public Settings(ClockMode clockMode, double speed, LocalDate startDate, Duration preOpen, Duration closedPause,
                        int ticksPerMinute, Duration catchUpAfter) {
            this(clockMode, speed, startDate, preOpen, closedPause, ticksPerMinute, catchUpAfter, null);
        }
    }

    /** What readers see of the session. Replaced, never mutated. */
    private record SessionView(LocalDate date, Map<String, Bar[]> bars, Map<String, Double> prevClose,
                               MarketState state, Instant openAt, Instant closeAt, Instant currentMinute,
                               Instant wallAnchor, Instant marketAnchor) {}

    private record Snapshot(Quote quote, Candle partial, boolean traded) {}

    private record Pending(Instant ts, String symbol, double price, long size) {}

    private static final Comparator<Pending> BY_TIME =
            Comparator.comparing(Pending::ts).thenComparing(Pending::symbol);

    private final MarketSimulator sim;
    private final Settings settings;
    private final Clock wall;
    private final List<MarketListener> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, Instrument> instruments = new LinkedHashMap<>();
    private final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();

    // ── engine-thread state ──
    private final Map<String, Long> seq = new HashMap<>();
    private final Map<String, Double> avgVolume = new HashMap<>();
    private LocalDate session;
    private Map<String, Bar[]> bars;
    private Map<String, Double> prevClose;
    private Instant openAt;
    private Instant closeAt;
    private Instant wallAnchor;
    private Instant marketAnchor;
    private MarketState state;
    private Instant closedAtWall;
    private int minute;
    private List<Pending> batch = List.of();
    private int batchPos;
    private volatile SessionView view;
    private volatile Duration lag = Duration.ZERO;

    public MarketEngine(MarketSimulator sim, Settings settings, Clock wall) {
        this.sim = sim;
        this.settings = settings;
        this.wall = wall;
        sim.instruments().forEach(i -> instruments.put(i.symbol(), i));
        Instant now = wall.instant();
        if (settings.clockMode() == ClockMode.WALL) {
            LocalDate today = now.atZone(Model.IST).toLocalDate();
            LocalDate day = MarketSimulator.isTradingDay(today) ? today : previousTradingDay(today);
            load(day, now, now);
        } else if (settings.epoch() != null) {
            resume(now);
        } else {
            LocalDate start = MarketSimulator.onOrAfter(
                    settings.startDate() != null ? settings.startDate() : now.atZone(Model.IST).toLocalDate());
            load(start, now, Model.at(start, Model.OPEN).minus(settings.preOpen()));
        }
    }

    /**
     * Sessions follow each other at a fixed period in real time (the pre-open and the session at
     * {@code speed}, then the pause), so which session the market is in, and how far, follows from when it
     * began. The engine loads that session at the moment it began and catches up silently to now.
     */
    private void resume(Instant now) {
        if (settings.startDate() == null) {
            throw new IllegalArgumentException("An epoch needs a start date: without one the market can't be placed again after a restart.");
        }
        LocalDate day = MarketSimulator.onOrAfter(settings.startDate());
        Instant epoch = settings.epoch();
        if (now.isBefore(epoch)) {
            load(day, now, Model.at(day, Model.OPEN).minus(settings.preOpen()));   // not begun yet: start at the first session
            return;
        }
        long period = sessionPeriod().toNanos();
        long sessions = Duration.between(epoch, now).toNanos() / period;
        for (long i = 0; i < sessions; i++) {
            day = MarketSimulator.nextTradingDay(day);
        }
        load(day, epoch.plusNanos(sessions * period), Model.at(day, Model.OPEN).minus(settings.preOpen()));
    }

    /** Real time from one session's pre-open to the next's: pre-open and session at {@code speed}, then the closed pause. */
    private Duration sessionPeriod() {
        long marketNanos = settings.preOpen().toNanos() + Model.MINUTES_PER_SESSION * 60_000_000_000L;
        return Duration.ofNanos((long) (marketNanos / settings.speed())).plus(settings.closedPause());
    }

    public void addListener(MarketListener l) {
        listeners.add(l);
    }

    public Mode mode() {
        return Mode.SYNTHETIC;
    }

    public double speed() {
        return settings.clockMode() == ClockMode.WALL ? 1.0 : settings.speed();
    }

    /** How far the engine is behind its own schedule. Near zero when healthy. */
    public Duration lag() {
        return lag;
    }

    // ── driving ──────────────────────────────────────────────────────────────

    /**
     * Processes everything due by {@code now} and returns when the next thing is due. Call it from
     * one thread only.
     */
    public synchronized Instant advance(Instant now) {
        boolean silent = Duration.between(nextEventWall(), now).compareTo(settings.catchUpAfter()) > 0;
        for (long guard = 0; guard < 50_000_000L; guard++) {
            Instant due = nextEventWall();
            if (due.isAfter(now)) {
                break;
            }
            step(due, silent);
        }
        Instant next = nextEventWall();
        lag = now.isAfter(next) ? Duration.between(next, now) : Duration.ZERO;
        if (silent) {
            listeners.forEach(MarketListener::onResync);
            publishMarket(false);
        }
        return next;
    }

    private void step(Instant dueWall, boolean silent) {
        switch (state) {
            case PRE_OPEN -> {
                state = MarketState.OPEN;
                minute = 0;
                buildBatch();
                publishView();
                publishMarket(silent);
            }
            case OPEN -> {
                if (batchPos < batch.size()) {
                    emit(batch.get(batchPos++), silent);
                } else {
                    minute++;
                    if (minute >= Model.MINUTES_PER_SESSION) {
                        state = MarketState.CLOSED;
                        closedAtWall = dueWall;
                        batch = List.of();
                        publishView();
                        publishMarket(silent);
                    } else {
                        buildBatch();
                        publishView();
                    }
                }
            }
            case CLOSED -> {
                LocalDate next = MarketSimulator.nextTradingDay(session);
                Instant nextMarketStart = Model.at(next, Model.OPEN).minus(settings.preOpen());
                if (settings.clockMode() == ClockMode.WALL) {
                    load(next, nextMarketStart, nextMarketStart);
                } else {
                    load(next, dueWall, nextMarketStart);
                }
                publishMarket(silent);
            }
        }
    }

    private Instant nextEventWall() {
        return switch (state) {
            case PRE_OPEN -> wallOf(openAt);
            case OPEN -> batchPos < batch.size()
                    ? wallOf(batch.get(batchPos).ts())
                    : wallOf(openAt.plusSeconds(60L * (minute + 1)));
            case CLOSED -> settings.clockMode() == ClockMode.WALL
                    ? wallOf(Model.at(MarketSimulator.nextTradingDay(session), Model.OPEN).minus(settings.preOpen()))
                    : closedAtWall.plus(settings.closedPause());
        };
    }

    private Instant wallOf(Instant marketTime) {
        long nanos = (long) (Duration.between(marketAnchor, marketTime).toNanos() / speed());
        return wallAnchor.plusNanos(nanos);
    }

    private Instant marketOf(Instant wallTime, SessionView v) {
        long nanos = (long) (Duration.between(v.wallAnchor(), wallTime).toNanos() * speed());
        return v.marketAnchor().plusNanos(nanos);
    }

    private void load(LocalDate day, Instant wallStart, Instant marketStart) {
        session = day;
        Map<String, List<Bar>> minuteBars = sim.minuteBars(day);
        prevClose = sim.previousCloses(day);
        bars = new HashMap<>();
        minuteBars.forEach((sym, list) -> {
            Bar[] slots = new Bar[Model.MINUTES_PER_SESSION];
            Instant open = Model.at(day, Model.OPEN);
            for (Bar b : list) {
                int m = (int) Duration.between(open, b.start()).toMinutes();
                if (m >= 0 && m < slots.length) {
                    slots[m] = b;
                }
            }
            bars.put(sym, slots);
            avgVolume.put(sym, list.stream().mapToLong(Bar::volume).average().orElse(0));
        });
        openAt = Model.at(day, Model.OPEN);
        closeAt = Model.at(day, Model.CLOSE);
        wallAnchor = wallStart;
        marketAnchor = marketStart;
        // always starts PRE_OPEN; if the open is already past (WALL mode mid-day), advance() catches up
        state = MarketState.PRE_OPEN;
        minute = 0;
        batch = List.of();
        batchPos = 0;
        closedAtWall = null;
        for (Instrument i : instruments.values()) {
            double pc = prevClose.getOrDefault(i.symbol(), 0.0);
            long s = seq.getOrDefault(i.symbol(), 0L);
            snapshots.put(i.symbol(), new Snapshot(new Quote(i.symbol(), pc, pc, pc, pc, pc, 0, openAt, s), null, false));
        }
        publishView();
    }

    private void buildBatch() {
        Instant minuteStart = openAt.plusSeconds(60L * minute);
        List<Pending> next = new ArrayList<>();
        for (Instrument i : instruments.values()) {
            Bar[] slots = bars.get(i.symbol());
            Bar bar = slots == null ? null : slots[minute];
            if (bar == null) {
                continue;
            }
            for (TickSynthesizer.SynthTick t : TickSynthesizer.ticks(i.symbol(), bar, i.tickSize(), tickCount(i, bar))) {
                next.add(new Pending(minuteStart.plusMillis(t.offsetMillis()), i.symbol(), t.price(), t.size()));
            }
        }
        next.sort(BY_TIME);
        batch = next;
        batchPos = 0;
    }

    private int tickCount(Instrument i, Bar bar) {
        int base = settings.ticksPerMinute();
        double avg = avgVolume.getOrDefault(i.symbol(), 0.0);
        if (bar.volume() <= 0 || avg <= 0) {
            return base;
        }
        return (int) Math.max(4, Math.min(3L * base, Math.round(base * Math.sqrt(bar.volume() / avg))));
    }

    private void emit(Pending p, boolean silent) {
        long s = seq.merge(p.symbol(), 1L, Long::sum);
        Snapshot old = snapshots.get(p.symbol());
        Quote q = old.quote();
        boolean first = !old.traded();
        double open = first ? p.price() : q.open();
        double high = first ? p.price() : Math.max(q.high(), p.price());
        double low = first ? p.price() : Math.min(q.low(), p.price());
        Quote quote = new Quote(p.symbol(), p.price(), open, high, low, q.prevClose(), q.volume() + p.size(), p.ts(), s);

        Instant minuteStart = openAt.plusSeconds(60L * minute);
        Candle c = old.partial();
        Candle partial = c == null || !c.start().equals(minuteStart)
                ? new Candle(minuteStart, p.price(), p.price(), p.price(), p.price(), p.size(), false)
                : new Candle(minuteStart, c.open(), Math.max(c.high(), p.price()), Math.min(c.low(), p.price()),
                        p.price(), c.volume() + p.size(), false);
        snapshots.put(p.symbol(), new Snapshot(quote, partial, true));
        if (!silent) {
            Tick tick = new Tick(p.symbol(), p.price(), p.size(), p.ts(), s, wall.instant());
            for (MarketListener l : listeners) {
                l.onTick(tick);
            }
        }
    }

    private void publishView() {
        view = new SessionView(session, Collections.unmodifiableMap(bars), Map.copyOf(prevClose), state, openAt,
                closeAt, openAt.plusSeconds(60L * minute), wallAnchor, marketAnchor);
    }

    private void publishMarket(boolean silent) {
        if (!silent) {
            MarketView m = market();
            listeners.forEach(l -> l.onMarket(m));
        }
    }

    private static LocalDate previousTradingDay(LocalDate d) {
        LocalDate p = d.minusDays(1);
        while (!MarketSimulator.isTradingDay(p)) {
            p = p.minusDays(1);
        }
        return p;
    }

    // ── reading (any thread) ─────────────────────────────────────────────────

    public List<Instrument> instruments() {
        return List.copyOf(instruments.values());
    }

    public Optional<Instrument> instrument(String symbol) {
        return Optional.ofNullable(instruments.get(symbol));
    }

    public MarketView market() {
        SessionView v = view;
        Instant marketTime = switch (v.state()) {
            case PRE_OPEN -> min(marketOf(wall.instant(), v), v.openAt());
            case OPEN -> min(marketOf(wall.instant(), v), v.closeAt());
            case CLOSED -> settings.clockMode() == ClockMode.WALL ? max(wall.instant(), v.closeAt()) : v.closeAt();
        };
        return new MarketView(mode(), v.state(), v.date(), marketTime, speed(), sim.source());
    }

    public Quote quote(String symbol) {
        Snapshot s = snapshots.get(symbol);
        return s == null ? null : s.quote();
    }

    /** One-minute candles of the current session up to now, the forming one last. */
    public List<Candle> minuteCandles(String symbol, int limit) {
        SessionView v = view;
        Bar[] slots = v.bars().get(symbol);
        if (slots == null) {
            return List.of();
        }
        List<Candle> out = new ArrayList<>();
        for (Bar b : slots) {
            if (b == null) {
                continue;
            }
            boolean done = v.state() == MarketState.CLOSED
                    || (v.state() == MarketState.OPEN && b.start().isBefore(v.currentMinute()));
            if (!done) {
                break;
            }
            out.add(new Candle(b.start(), b.open(), b.high(), b.low(), b.close(), b.volume(), true));
        }
        Snapshot s = snapshots.get(symbol);
        if (v.state() == MarketState.OPEN && s != null && s.partial() != null
                && s.partial().start().equals(v.currentMinute())) {
            out.add(s.partial());
        }
        return tail(out, limit);
    }

    /** Daily candles: history, then today's (incomplete while the market is open). */
    public List<Candle> dailyCandles(String symbol, int limit) {
        SessionView v = view;
        List<Candle> out = new ArrayList<>();
        for (Bar b : sim.dailyBefore(symbol, v.date(), limit)) {
            out.add(new Candle(b.start(), b.open(), b.high(), b.low(), b.close(), b.volume(), true));
        }
        Snapshot s = snapshots.get(symbol);
        if (s != null && s.traded()) {
            Quote q = s.quote();
            out.add(new Candle(v.date().atStartOfDay(Model.IST).toInstant(), q.open(), q.high(), q.low(), q.last(),
                    q.volume(), v.state() == MarketState.CLOSED));
        }
        return tail(out, limit);
    }

    private static List<Candle> tail(List<Candle> list, int limit) {
        return list.size() <= limit ? list : List.copyOf(list.subList(list.size() - limit, list.size()));
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }
}
