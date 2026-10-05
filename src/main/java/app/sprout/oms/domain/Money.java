package app.sprout.oms.domain;

import java.util.regex.Pattern;

/**
 * Rupee amounts as the APIs write them ({@code "1500.00"}) and as the code holds them (paise, a
 * {@code long}). No floating point ever touches money.
 */
public final class Money {

    private static final Pattern AMOUNT = Pattern.compile("^[0-9]{1,13}(\\.[0-9]{1,2})?$");

    private Money() {}

    /** Paise from a decimal rupee string; throws VALIDATION_FAILED for anything else. */
    public static long paise(String rupees) {
        if (rupees == null || !AMOUNT.matcher(rupees).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Amounts are rupees with at most two decimals, e.g. \"1500.00\".");
        }
        int dot = rupees.indexOf('.');
        if (dot < 0) {
            return Math.multiplyExact(Long.parseLong(rupees), 100L);
        }
        String fraction = (rupees.substring(dot + 1) + "00").substring(0, 2);
        return Math.addExact(Math.multiplyExact(Long.parseLong(rupees.substring(0, dot)), 100L), Long.parseLong(fraction));
    }

    /** {@code "1500.00"} from paise. */
    public static String rupees(long paise) {
        String sign = paise < 0 ? "-" : "";
        long abs = Math.abs(paise);
        return sign + (abs / 100) + "." + String.format("%02d", abs % 100);
    }
}
