package app.sprout.marketdata.domain;

import app.sprout.marketdata.domain.Model.Bar;
import app.sprout.marketdata.domain.Model.Instrument;
import app.sprout.marketdata.domain.Model.InstrumentType;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * A made-up market of twenty fictional companies and an index, realistic enough to build a brokerage
 * against and fully deterministic: the same seed and date always give the same prices.
 *
 * <p>How a day's prices are made:
 * <ul>
 *   <li>Each weekday has a <b>scenario</b> (mostly normal; sometimes trending, volatile, a crash or a
 *       rally), picked from the seed or forced by configuration.</li>
 *   <li>Every stock's move is <b>market + sector + its own</b>: a market factor scaled by the stock's
 *       beta, a factor shared with its sector, and noise of its own. So banks move together, and
 *       everything falls on a crash day.</li>
 *   <li>Part of the day's move happens <b>overnight</b> (the opening gap); the rest is a minute-by-minute
 *       random walk pinned to the day's close, busier at the open and close than at lunch.</li>
 *   <li>Now and then a stock <b>jumps</b> 2-6% on news at a random minute.</li>
 *   <li>Volume is U-shaped through the day and rises with big moves.</li>
 * </ul>
 * Days follow each other from a fixed origin, so each day opens from the previous day's close and the
 * daily history is exactly the aggregate of the minutes. Weekends are closed; there are no holidays.
 */
public final class MarketSimulator {

    public static final LocalDate ORIGIN = LocalDate.of(2024, 1, 1);
    public static final String INDEX = "SPROUT20";
    private static final double INDEX_BASE = 10_000.0;
    private static final double EQUITY_TICK = 0.05;
    private static final double INDEX_TICK = 0.01;
    private static final int T = Model.MINUTES_PER_SESSION;

    public enum Scenario { NORMAL, TREND_UP, TREND_DOWN, VOLATILE, CRASH, RALLY }

    /** A fictional listed company and how it behaves. */
    record Stock(String symbol, String name, int sector, double startPrice, double annualVol, double beta,
                 double dailyVolume) {}

    private static final String[] SECTORS = {"Financial Services", "Information Technology", "Consumer Goods",
            "Energy & Power", "Industrials & Materials"};

    private static final List<Stock> STOCKS = List.of(
            new Stock("SAPLING", "Sapling Finance Ltd.", 0, 1450, 0.24, 1.10, 2.4e6),
            new Stock("HARBOR", "Harbor Bank Ltd.", 0, 820, 0.22, 1.15, 6.5e6),
            new Stock("LEDGERCO", "Ledger & Co. Insurance Ltd.", 0, 640, 0.20, 0.90, 1.8e6),
            new Stock("KOSHA", "Kosha Capital Ltd.", 0, 215, 0.38, 1.30, 9.0e6),
            new Stock("INKWELL", "Inkwell Systems Ltd.", 1, 3650, 0.22, 0.85, 1.1e6),
            new Stock("BYTEFARM", "Bytefarm Technologies Ltd.", 1, 1480, 0.26, 0.90, 2.2e6),
            new Stock("NEURONET", "Neuronet Labs Ltd.", 1, 590, 0.42, 1.25, 4.8e6),
            new Stock("CLOUDLEAF", "Cloudleaf Software Ltd.", 1, 2240, 0.30, 1.00, 1.4e6),
            new Stock("MARIGOLD", "Marigold Foods Ltd.", 2, 2480, 0.17, 0.60, 0.9e6),
            new Stock("CHAIWALA", "Chaiwala Beverages Ltd.", 2, 96.40, 0.35, 0.80, 2.1e7),
            new Stock("THREADS", "Monsoon Threads Ltd.", 2, 410, 0.33, 1.05, 3.3e6),
            new Stock("NIGHTOWL", "Night Owl Media Ltd.", 2, 158, 0.45, 1.20, 1.2e7),
            new Stock("TEALPWR", "Teal Power Ltd.", 3, 345, 0.27, 0.95, 7.7e6),
            new Stock("SUNROOT", "Sunroot Renewables Ltd.", 3, 72.85, 0.50, 1.35, 3.1e7),
            new Stock("DEEPWELL", "Deepwell Oil & Gas Ltd.", 3, 1290, 0.25, 1.00, 3.0e6),
            new Stock("GRIDLINE", "Gridline Transmission Ltd.", 3, 298, 0.21, 0.75, 5.2e6),
            new Stock("CHALK", "Chalk Cement Ltd.", 4, 11250, 0.21, 0.90, 2.0e5),
            new Stock("IRONLEAF", "Ironleaf Steel Ltd.", 4, 152, 0.36, 1.30, 1.6e7),
            new Stock("RAILYARD", "Railyard Logistics Ltd.", 4, 760, 0.29, 1.10, 2.6e6),
            new Stock("AEROMINT", "Aeromint Defence Ltd.", 4, 4120, 0.34, 1.15, 7.0e5));

    private final long seed;
    private final Map<LocalDate, Scenario> forced;
    private final List<Instrument> instruments;
    /** Daily bars by trading day, in order from {@link #ORIGIN}; extended on demand. */
    private final List<LocalDate> days = new ArrayList<>();
    private final List<Map<String, Bar>> daily = new ArrayList<>();

    public MarketSimulator(long seed, Map<LocalDate, Scenario> forcedScenarios) {
        this.seed = seed;
        this.forced = forcedScenarios == null ? Map.of() : Map.copyOf(forcedScenarios);
        List<Instrument> list = new ArrayList<>();
        for (Stock s : STOCKS) {
            list.add(new Instrument(s.symbol(), s.name(), SECTORS[s.sector()], InstrumentType.EQUITY, EQUITY_TICK));
        }
        list.add(new Instrument(INDEX, "Sprout 20 Index", "Index", InstrumentType.INDEX, INDEX_TICK));
        this.instruments = List.copyOf(list);
    }

    public List<Instrument> instruments() {
        return instruments;
    }

    public String source() {
        return "Simulated market of 20 fictional companies (seed " + seed + "). Not real prices.";
    }

    public static boolean isTradingDay(LocalDate d) {
        return d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY;
    }

    public static LocalDate nextTradingDay(LocalDate d) {
        LocalDate n = d.plusDays(1);
        while (!isTradingDay(n)) {
            n = n.plusDays(1);
        }
        return n;
    }

    public static LocalDate onOrAfter(LocalDate d) {
        return isTradingDay(d) ? d : nextTradingDay(d);
    }

    public Scenario scenario(LocalDate day) {
        Scenario f = forced.get(day);
        if (f != null) {
            return f;
        }
        double u = rng(day.toEpochDay(), 1).nextDouble();
        if (u < 0.006) return Scenario.CRASH;
        if (u < 0.012) return Scenario.RALLY;
        if (u < 0.10) return Scenario.VOLATILE;
        if (u < 0.175) return Scenario.TREND_UP;
        if (u < 0.25) return Scenario.TREND_DOWN;
        return Scenario.NORMAL;
    }

    /** One-minute bars for a trading day, per symbol, oldest first. */
    public synchronized Map<String, List<Bar>> minuteBars(LocalDate day) {
        requireTradingDay(day);
        return generate(day, previousCloses(day));
    }

    /** Closing prices of the trading day before {@code day}: the reference for "change today". */
    public synchronized Map<String, Double> previousCloses(LocalDate day) {
        requireTradingDay(day);
        LocalDate prevDay = previousTradingDay(day);
        if (prevDay.isBefore(onOrAfter(ORIGIN))) {
            return startPrices();
        }
        extendTo(prevDay);
        return closesOf(daily.get(indexOf(prevDay)));
    }

    /** Daily bars strictly before {@code day}, oldest first, at most {@code limit}. */
    public synchronized List<Bar> dailyBefore(String symbol, LocalDate day, int limit) {
        if (!day.isAfter(ORIGIN)) {
            return List.of();
        }
        LocalDate last = previousTradingDay(day);
        extendTo(last);
        int end = indexOf(last) + 1;
        List<Bar> out = new ArrayList<>();
        for (int i = Math.max(0, end - limit); i < end; i++) {
            Bar b = daily.get(i).get(symbol);
            if (b != null) {
                out.add(b);
            }
        }
        return Collections.unmodifiableList(out);
    }

    // ── generation ───────────────────────────────────────────────────────────

    private void extendTo(LocalDate last) {
        LocalDate next = days.isEmpty() ? onOrAfter(ORIGIN) : nextTradingDay(days.get(days.size() - 1));
        while (!next.isAfter(last)) {
            Map<String, Double> prev = days.isEmpty() ? startPrices() : closesOf(daily.get(daily.size() - 1));
            Map<String, List<Bar>> minutes = generate(next, prev);
            Map<String, Bar> agg = new LinkedHashMap<>();
            for (Map.Entry<String, List<Bar>> e : minutes.entrySet()) {
                agg.put(e.getKey(), aggregate(next, e.getValue()));
            }
            days.add(next);
            daily.add(agg);
            next = nextTradingDay(next);
        }
    }

    private int indexOf(LocalDate day) {
        return Collections.binarySearch(days, day);
    }

    private static Map<String, Double> startPrices() {
        Map<String, Double> start = new LinkedHashMap<>();
        STOCKS.forEach(s -> start.put(s.symbol(), s.startPrice()));
        start.put(INDEX, INDEX_BASE);
        return start;
    }

    private static LocalDate previousTradingDay(LocalDate d) {
        LocalDate p = d.minusDays(1);
        while (!isTradingDay(p)) {
            p = p.minusDays(1);
        }
        return p;
    }

    private static Map<String, Double> closesOf(Map<String, Bar> bars) {
        Map<String, Double> m = new LinkedHashMap<>();
        bars.forEach((sym, bar) -> m.put(sym, bar.close()));
        return m;
    }

    private static Bar aggregate(LocalDate day, List<Bar> minutes) {
        double high = Double.NEGATIVE_INFINITY;
        double low = Double.POSITIVE_INFINITY;
        long volume = 0;
        for (Bar b : minutes) {
            high = Math.max(high, b.high());
            low = Math.min(low, b.low());
            volume += b.volume();
        }
        return new Bar(day.atStartOfDay(Model.IST).toInstant(), minutes.get(0).open(), high, low,
                minutes.get(minutes.size() - 1).close(), volume);
    }

    private Map<String, List<Bar>> generate(LocalDate day, Map<String, Double> prevClose) {
        long d = day.toEpochDay();
        Scenario scenario = scenario(day);
        SplittableRandom dayRng = rng(d, 2);

        double marketVol = 0.009 * (scenario == Scenario.VOLATILE ? 2.0 : scenario == Scenario.CRASH ? 2.5 : 1.0);
        double marketDrift = switch (scenario) {
            case TREND_UP -> 0.008;
            case TREND_DOWN -> -0.008;
            case CRASH -> -0.045 - 0.015 * dayRng.nextDouble();
            case RALLY -> 0.03 + 0.01 * dayRng.nextDouble();
            default -> 0.0003;
        };
        double marketReturn = marketDrift + marketVol * gaussian(dayRng);
        double[] sectorReturn = new double[SECTORS.length];
        for (int s = 0; s < SECTORS.length; s++) {
            sectorReturn[s] = 0.006 * gaussian(rng(d, 100 + s));
        }

        // shared intraday shocks: one series for the market, one per sector
        double[] marketShocks = shocks(rng(d, 3));
        double[][] sectorShocks = new double[SECTORS.length][];
        for (int s = 0; s < SECTORS.length; s++) {
            sectorShocks[s] = shocks(rng(d, 200 + s));
        }

        Instant open = Model.at(day, Model.OPEN);
        Map<String, List<Bar>> out = new LinkedHashMap<>();
        for (int i = 0; i < STOCKS.size(); i++) {
            Stock st = STOCKS.get(i);
            SplittableRandom r = rng(d, 1000 + i);
            double dailyVol = st.annualVol() / Math.sqrt(252) * (scenario == Scenario.VOLATILE ? 1.6 : 1.0);
            double idioVol = Math.sqrt(Math.max(dailyVol * dailyVol - square(st.beta() * 0.009) - square(0.006), square(0.004)));
            double total = st.beta() * marketReturn + sectorReturn[st.sector()] + idioVol * gaussian(r);

            int jumpMinute = -1;
            double jump = 0;
            if (r.nextDouble() < 0.03) {
                jumpMinute = 15 + r.nextInt(T - 30);
                jump = (r.nextBoolean() ? 1 : -1) * (0.02 + 0.04 * r.nextDouble());
                total += jump;
            }
            double gap = 0.35 * (total - jump) + dailyVol * 0.1 * gaussian(r);
            double prev = prevClose.getOrDefault(st.symbol(), st.startPrice());
            double openPrice = round(prev * Math.exp(gap), EQUITY_TICK);
            double targetClose = prev * Math.exp(total);

            double minuteVol = dailyVol / Math.sqrt(T);
            double[] idio = shocks(r);
            double[] path = new double[T + 1];
            path[0] = 0;
            for (int t = 0; t < T; t++) {
                double inc = intraday(t) * minuteVol
                        * (0.55 * st.beta() * marketShocks[t] + 0.35 * sectorShocks[st.sector()][t] + 0.75 * idio[t]);
                if (t == jumpMinute) {
                    inc += jump;
                }
                path[t + 1] = path[t] + inc;
            }
            // pin the walk to the day's close (a Brownian bridge), keeping its shape
            double target = Math.log(targetClose / openPrice);
            for (int t = 1; t <= T; t++) {
                path[t] -= (double) t / T * (path[T] - target);
            }
            List<Bar> bars = new ArrayList<>(T);
            double baseVolume = st.dailyVolume() * (1 + Math.abs(total) * 25);
            for (int t = 0; t < T; t++) {
                double o = t == 0 ? openPrice : round(openPrice * Math.exp(path[t]), EQUITY_TICK);
                double c = round(openPrice * Math.exp(path[t + 1]), EQUITY_TICK);
                double wick = minuteVol * intraday(t) * 0.6;
                double h = round(Math.max(o, c) * Math.exp(Math.abs(gaussian(r)) * wick), EQUITY_TICK);
                double l = round(Math.min(o, c) * Math.exp(-Math.abs(gaussian(r)) * wick), EQUITY_TICK);
                h = Math.max(h, Math.max(o, c));
                l = Math.max(EQUITY_TICK, Math.min(l, Math.min(o, c)));
                long v = Math.max(1, Math.round(baseVolume * volumeShape(t) * Math.exp(0.4 * gaussian(r))
                        * (1 + 40 * Math.abs(c / o - 1))));
                bars.add(new Bar(open.plusSeconds(60L * t), o, h, l, c, v));
            }
            out.put(st.symbol(), List.copyOf(bars));
        }
        out.put(INDEX, indexBars(open, prevClose, out));
        return out;
    }

    /** Equal-weighted index of all twenty stocks, chained from its previous close. */
    private static List<Bar> indexBars(Instant open, Map<String, Double> prevClose, Map<String, List<Bar>> stocks) {
        double base = prevClose.getOrDefault(INDEX, INDEX_BASE);
        List<Bar> bars = new ArrayList<>(T);
        for (int t = 0; t < T; t++) {
            double o = 0, c = 0, h = 0, l = 0;
            for (Stock s : STOCKS) {
                Bar b = stocks.get(s.symbol()).get(t);
                double p = prevClose.getOrDefault(s.symbol(), s.startPrice());
                o += b.open() / p;
                c += b.close() / p;
                h += b.high() / p;
                l += b.low() / p;
            }
            int n = STOCKS.size();
            double io = round(base * o / n, INDEX_TICK);
            double ic = round(base * c / n, INDEX_TICK);
            // averaging highs overstates the index's own high; keep it close to the open-close range
            double ih = round(Math.max(Math.max(io, ic), base * (0.3 * h / n + 0.7 * Math.max(o, c) / n)), INDEX_TICK);
            double il = round(Math.min(Math.min(io, ic), base * (0.3 * l / n + 0.7 * Math.min(o, c) / n)), INDEX_TICK);
            bars.add(new Bar(open.plusSeconds(60L * t), io, ih, il, ic, 0));
        }
        return List.copyOf(bars);
    }

    /** Volatility through the day: high at the open, quiet at lunch, picking up into the close. */
    static double intraday(int minute) {
        return 0.8 + 0.8 * Math.exp(-minute / 30.0) + 0.5 * Math.exp(-(T - minute) / 40.0);
    }

    /** Share of the day's volume in each minute: U-shaped, summing to about 1. */
    static double volumeShape(int minute) {
        return (0.6 + 2.2 * Math.exp(-minute / 25.0) + 1.6 * Math.exp(-(T - minute) / 30.0)) / (0.6 * T + 55 + 48);
    }

    private static double[] shocks(SplittableRandom r) {
        double[] z = new double[T];
        for (int t = 0; t < T; t++) {
            z[t] = gaussian(r);
        }
        return z;
    }

    private SplittableRandom rng(long day, int stream) {
        long x = seed * 0x9E3779B97F4A7C15L + day * 0xBF58476D1CE4E5B9L + stream * 0x94D049BB133111EBL;
        return new SplittableRandom(x);
    }

    /** Standard normal, Box-Muller. */
    static double gaussian(SplittableRandom r) {
        double u1 = 1.0 - r.nextDouble();
        double u2 = r.nextDouble();
        return Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
    }

    /** To the nearest tick, as a clean two-decimal number, never below one tick. */
    static double round(double price, double tick) {
        double onTick = Math.round(price / tick) * tick;
        return Math.max(tick, Math.round(onTick * 100) / 100.0);
    }

    private static double square(double x) {
        return x * x;
    }

    private static void requireTradingDay(LocalDate day) {
        if (!isTradingDay(day)) {
            throw new IllegalArgumentException(day + " is not a trading day");
        }
    }
}
