package app.sprout.oms.config;

import java.time.Duration;
import java.time.LocalTime;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings under {@code sprout.oms} in oms.yml. */
@ConfigurationProperties("sprout.oms")
public record OmsProperties(
        int leverage,
        int marketProtectionPercent,
        LocalTime squareOffAt,
        int riskLossPercent,
        int bandPercent,
        Duration rmsEvery,
        Duration reconcileAfter,
        Accounts accounts,
        Ledger ledger,
        Marketdata marketdata,
        Exchange exchange) {

    public record Accounts(String url, String serviceKey) {}

    public record Ledger(String url) {}

    public record Marketdata(String url) {}

    public record Exchange(String url, String memberKey, String webhookSecret) {}
}
