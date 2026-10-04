package app.sprout.marketdata.domain;

import app.sprout.marketdata.domain.Model.Bar;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Fills a one-minute bar with individual trades. The trades are invented, but they always add up to
 * the bar: the first trade is at the open, the last at the close, the high and the low each trade at
 * least once, nothing trades outside them, and the sizes sum to the bar's volume. So a candle built
 * from the ticks is identical to the bar it came from.
 *
 * <p>Deterministic: the same symbol and bar always give the same ticks.
 */
public final class TickSynthesizer {

    /** A trade at {@code offsetMillis} into the minute. */
    public record SynthTick(int offsetMillis, double price, long size) {}

    private TickSynthesizer() {}

    public static List<SynthTick> ticks(String symbol, Bar bar, double tickSize, int count) {
        int k = Math.max(4, count);
        SplittableRandom r = new SplittableRandom(symbol.hashCode() * 0x9E3779B97F4A7C15L ^ bar.start().getEpochSecond());

        int[] times = new int[k];
        times[0] = r.nextInt(250);
        times[k - 1] = 58_500 + r.nextInt(1_400);
        for (int i = 1; i < k - 1; i++) {
            times[i] = 300 + r.nextInt(58_100);
        }
        Arrays.sort(times, 1, k - 1);
        for (int i = 1; i < k; i++) {
            times[i] = Math.max(times[i], times[i - 1] + 1);
        }

        // waypoints: open, then the extreme the price reaches first, then the other, then the close
        int first = 1 + r.nextInt(k - 2);
        int second = 1 + r.nextInt(k - 2);
        while (second == first) {
            second = 1 + r.nextInt(k - 2);
        }
        int a = Math.min(first, second);
        int b = Math.max(first, second);
        boolean up = bar.close() >= bar.open();
        double[] prices = new double[k];
        int[] idx = {0, a, b, k - 1};
        double[] val = {bar.open(), up ? bar.low() : bar.high(), up ? bar.high() : bar.low(), bar.close()};
        double range = bar.high() - bar.low();
        for (int w = 0; w < 3; w++) {
            int from = idx[w];
            int to = idx[w + 1];
            for (int i = from; i <= to; i++) {
                double t = to == from ? 0 : (double) (i - from) / (to - from);
                double bridge = Math.sqrt(t * (1 - t)) * range * 0.25 * MarketSimulator.gaussian(r);
                prices[i] = val[w] + (val[w + 1] - val[w]) * t + bridge;
            }
        }
        for (int i = 0; i < k; i++) {
            double p = MarketSimulator.round(prices[i], tickSize);
            prices[i] = Math.min(bar.high(), Math.max(bar.low(), p));
        }
        for (int w = 0; w < 4; w++) {
            prices[idx[w]] = val[w];
        }

        long[] sizes = new long[k];
        if (bar.volume() > 0) {
            double[] weight = new double[k];
            double sum = 0;
            for (int i = 0; i < k; i++) {
                weight[i] = -Math.log(1.0 - r.nextDouble()) + 0.05;
                sum += weight[i];
            }
            long given = 0;
            for (int i = 0; i < k - 1; i++) {
                sizes[i] = (long) Math.floor(bar.volume() * weight[i] / sum);
                given += sizes[i];
            }
            sizes[k - 1] = bar.volume() - given;
        }

        List<SynthTick> out = new ArrayList<>(k);
        for (int i = 0; i < k; i++) {
            out.add(new SynthTick(times[i], prices[i], sizes[i]));
        }
        return out;
    }
}
