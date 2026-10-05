package app.sprout.oms;

import static org.assertj.core.api.Assertions.assertThat;

import app.sprout.oms.domain.Charges;
import app.sprout.oms.domain.Charges.Breakdown;
import app.sprout.oms.domain.Charges.Product;
import app.sprout.oms.domain.Charges.Side;
import org.junit.jupiter.api.Test;

/** The charges on worked examples (₹1,00,000 trades), checked by hand against a discount broker's calculator. */
class ChargesTest {

    static final long LAKH = 1_00_000_00L;

    @Test
    void deliveryBuyPaysSttStampAndFeesButNoBrokerage() {
        Breakdown c = Charges.of(Product.CNC, Side.BUY, LAKH, false);
        assertThat(c.brokerage()).isZero();
        assertThat(c.stt()).isEqualTo(100_00);        // 0.1%
        assertThat(c.exchange()).isEqualTo(2_97);     // 0.00297%
        assertThat(c.sebi()).isEqualTo(10);           // ₹10 a crore
        assertThat(c.stamp()).isEqualTo(15_00);       // 0.015%
        assertThat(c.gst()).isEqualTo(55);            // 18% of 3.07
        assertThat(c.total()).isEqualTo(118_62);
    }

    @Test
    void deliverySellPaysSttButNoStamp() {
        Breakdown c = Charges.of(Product.CNC, Side.SELL, LAKH, false);
        assertThat(c.stt()).isEqualTo(100_00);
        assertThat(c.stamp()).isZero();
        assertThat(c.total()).isEqualTo(103_62);
    }

    @Test
    void intradayPaysCappedBrokerageAndSttOnlyOnTheSell() {
        Breakdown buy = Charges.of(Product.MIS, Side.BUY, LAKH, false);
        assertThat(buy.brokerage()).isEqualTo(20_00);  // 0.03% would be ₹30: capped at ₹20
        assertThat(buy.stt()).isZero();
        assertThat(buy.stamp()).isEqualTo(3_00);      // 0.003%
        assertThat(buy.gst()).isEqualTo(4_15);        // 18% of 23.07
        Breakdown sell = Charges.of(Product.MIS, Side.SELL, LAKH, false);
        assertThat(sell.stt()).isEqualTo(25_00);      // 0.025%
        assertThat(sell.total()).isEqualTo(52_22);
    }

    @Test
    void smallIntradayTradesPayThePercentageAndSquareOffCostsFiftyPlusGst() {
        Breakdown small = Charges.of(Product.MIS, Side.BUY, 10_000_00L, false);   // ₹10,000
        assertThat(small.brokerage()).isEqualTo(3_00);
        Breakdown squared = Charges.of(Product.MIS, Side.SELL, 10_000_00L, true);
        assertThat(squared.brokerage()).isEqualTo(53_00);
        assertThat(squared.gst()).isEqualTo(Math.round((53_00 + squared.exchange() + squared.sebi()) * 0.18));
    }
}
