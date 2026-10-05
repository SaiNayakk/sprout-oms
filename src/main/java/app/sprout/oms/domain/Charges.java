package app.sprout.oms.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * What an executed equity order costs beyond the shares, as a discount broker in India charges it.
 * All in paise, rounded half up; STT to the nearest rupee, as the exchanges compute it.
 *
 * <ul>
 *   <li>Brokerage: delivery free; intraday ₹20 or 0.03% of the value, whichever is lower.</li>
 *   <li>STT: delivery 0.1% on buys and sells; intraday 0.025% on sells.</li>
 *   <li>Exchange transaction charges: 0.00297% of the value.</li>
 *   <li>SEBI fees: ₹10 per crore of value.</li>
 *   <li>Stamp duty, on buys only: delivery 0.015%, intraday 0.003%.</li>
 *   <li>GST: 18% of brokerage, exchange charges and SEBI fees.</li>
 *   <li>Auto square-off: ₹50 more brokerage when Sprout has to close an intraday position.</li>
 * </ul>
 */
public final class Charges {

    public enum Product { CNC, MIS }

    public enum Side { BUY, SELL }

    public record Breakdown(long brokerage, long stt, long exchange, long sebi, long stamp, long gst) {
        public long total() {
            return brokerage + stt + exchange + sebi + stamp + gst;
        }
    }

    static final long INTRADAY_BROKERAGE_CAP = 20_00;
    static final long AUTO_SQUARE_OFF_FEE = 50_00;

    private Charges() {}

    public static Breakdown of(Product product, Side side, long value, boolean autoSquareOff) {
        boolean delivery = product == Product.CNC;
        boolean buy = side == Side.BUY;
        long brokerage = delivery ? 0 : Math.min(INTRADAY_BROKERAGE_CAP, part(value, "0.0003"));
        if (autoSquareOff) {
            brokerage += AUTO_SQUARE_OFF_FEE;
        }
        long stt = delivery ? rupees(part(value, "0.001")) : (buy ? 0 : rupees(part(value, "0.00025")));
        long exchange = part(value, "0.0000297");
        long sebi = part(value, "0.000001");
        long stamp = buy ? part(value, delivery ? "0.00015" : "0.00003") : 0;
        long gst = part(brokerage + exchange + sebi, "0.18");
        return new Breakdown(brokerage, stt, exchange, sebi, stamp, gst);
    }

    private static long part(long paise, String rate) {
        return BigDecimal.valueOf(paise).multiply(new BigDecimal(rate)).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private static long rupees(long paise) {
        return BigDecimal.valueOf(paise).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP).longValueExact() * 100;
    }
}
