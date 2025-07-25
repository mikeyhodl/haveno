/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package haveno.core.provider.price;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.inject.Inject;
import haveno.common.Timer;
import haveno.common.UserThread;
import haveno.common.handlers.FaultHandler;
import haveno.common.util.MathUtils;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.TradeCurrency;
import haveno.core.monetary.CryptoMoney;
import haveno.core.monetary.Price;
import haveno.core.monetary.TraditionalMoney;
import haveno.core.provider.PriceHttpClient;
import haveno.core.provider.ProvidersRepository;
import haveno.core.trade.statistics.TradeStatistics3;
import haveno.core.user.Preferences;
import haveno.network.http.HttpClient;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ChangeListener;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkNotNull;

@Slf4j
public class PriceFeedService {
    private final HttpClient httpClient;
    private final ProvidersRepository providersRepository;
    private final Preferences preferences;

    private static final long PERIOD_SEC = 60;

    private final Map<String, MarketPrice> cache = new HashMap<>();
    private PriceProvider priceProvider;
    @Nullable
    private Consumer<Double> priceConsumer;
    @Nullable
    private FaultHandler faultHandler;
    private String currencyCode;
    private final StringProperty currencyCodeProperty = new SimpleStringProperty();
    private final IntegerProperty updateCounter = new SimpleIntegerProperty(0);
    private long epochInMillisAtLastRequest;
    private long retryDelay = 0;
    private long requestTs;
    private long lastLoopTs = System.currentTimeMillis();
    @Nullable
    private String baseUrlOfRespondingProvider;
    @Nullable
    private Timer requestTimer;
    @Nullable
    private PriceRequest priceRequest;
    private String requestAllPricesError = null;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public PriceFeedService(PriceHttpClient httpClient,
                            @SuppressWarnings("SameParameterValue") ProvidersRepository providersRepository,
                            @SuppressWarnings("SameParameterValue") Preferences preferences) {
        this.httpClient = httpClient;
        this.providersRepository = providersRepository;
        this.preferences = preferences;

        // Do not use Guice for PriceProvider as we might create multiple instances
        this.priceProvider = new PriceProvider(httpClient, providersRepository.getBaseUrl());
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void shutDown() {
        log.info("Shutting down {}", getClass().getSimpleName());
        if (requestTimer != null) {
            requestTimer.stop();
            requestTimer = null;
        }
        if (priceRequest != null) {
            priceRequest.shutDown();
        }
    }

    public void setCurrencyCodeOnInit() {
        if (getCurrencyCode() == null) {
            TradeCurrency preferredTradeCurrency = preferences.getPreferredTradeCurrency();
            String code = preferredTradeCurrency != null ? preferredTradeCurrency.getCode() : "USD";
            setCurrencyCode(code);
        }
    }

    public void requestPrices() {
        request(false);
    }

    /**
     * Awaits prices to be available, but does not request them.
     */
    public void awaitExternalPrices() {
        CountDownLatch latch = new CountDownLatch(1);
        ChangeListener<? super Number> listener = (observable, oldValue, newValue) -> { 
            if (hasExternalPrices()) UserThread.execute(() -> latch.countDown());
        };
        UserThread.execute(() -> updateCounter.addListener(listener));
        if (hasExternalPrices()) UserThread.execute(() -> latch.countDown());
        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            UserThread.execute(() -> updateCounter.removeListener(listener));
        }
    }

    public boolean hasExternalPrices() {
        synchronized (cache) {
            return cache.values().stream().anyMatch(MarketPrice::isExternallyProvidedPrice);
        }
    }

    public void startRequestingPrices() {
        if (requestTimer == null) request(true); // ignore if already repeat requesting
    }

    public void startRequestingPrices(Consumer<Double> resultHandler, FaultHandler faultHandler) {
        this.priceConsumer = resultHandler;
        this.faultHandler = faultHandler;
        startRequestingPrices();
    }

    public String getProviderNodeAddress() {
        return httpClient.getBaseUrl();
    }

    private void request(boolean repeatRequests) {
        if (requestTs == 0)
            log.debug("request from provider {}",
                    providersRepository.getBaseUrl());
        else
            log.debug("request from provider {} {} sec. after last request",
                    providersRepository.getBaseUrl(),
                    (System.currentTimeMillis() - requestTs) / 1000d);

        requestTs = System.currentTimeMillis();

        baseUrlOfRespondingProvider = null;

        requestAllPrices(priceProvider, () -> {
            baseUrlOfRespondingProvider = priceProvider.getBaseUrl();

            // At applyPriceToConsumer we also check if price is not exceeding max. age for price data.
            boolean success = applyPriceToConsumer();
            if (success) {
                MarketPrice marketPrice = cache.get(currencyCode);
                if (marketPrice != null)
                    log.debug("Received new {} from provider {} after {} sec.",
                            marketPrice,
                            baseUrlOfRespondingProvider,
                            (System.currentTimeMillis() - requestTs) / 1000d);
                else
                    log.debug("Received new data from provider {} after {} sec. " +
                                    "Requested market price for currency {} was not provided. " +
                                    "That is expected if currency is not listed at provider.",
                            baseUrlOfRespondingProvider,
                            (System.currentTimeMillis() - requestTs) / 1000d,
                            currencyCode);
            } else {
                log.warn("applyPriceToConsumer was not successful. We retry with a new provider.");
                retryWithNewProvider();
            }
        }, (errorMessage, throwable) -> {
            if (throwable instanceof PriceRequestException) {
                String baseUrlOfFaultyRequest = ((PriceRequestException) throwable).priceProviderBaseUrl;
                String baseUrlOfCurrentRequest = priceProvider.getBaseUrl();
                if (baseUrlOfCurrentRequest.equals(baseUrlOfFaultyRequest)) {
                    log.info("We received an error requesting prices: baseUrlOfFaultyRequest={}, error={}",
                            baseUrlOfFaultyRequest, throwable.toString());
                    retryWithNewProvider();
                } else {
                    log.debug("We received an error from an earlier request. We have started a new request already so we ignore that error. " +
                                    "baseUrlOfCurrentRequest={}, baseUrlOfFaultyRequest={}",
                            baseUrlOfCurrentRequest, baseUrlOfFaultyRequest);
                }
            } else {
                log.warn("We received an error with throwable={}", throwable.toString());
                retryWithNewProvider();
            }

            if (faultHandler != null)
                faultHandler.handleFault(errorMessage, throwable);
        });

        if (repeatRequests) {
            if (requestTimer != null)
                requestTimer.stop();

            long delay = PERIOD_SEC + new Random().nextInt(5);
            requestTimer = UserThread.runAfter(() -> {
                // If we have not received a result from the last request. We try a new provider.
                if (baseUrlOfRespondingProvider == null) {
                    final String oldBaseUrl = priceProvider.getBaseUrl();
                    setNewPriceProvider();
                    log.warn("We did not receive a response from provider {}. " +
                            "We select the new provider {} and use that for a new request.", oldBaseUrl, priceProvider.getBaseUrl());
                }
                request(true);
            }, delay);
        }
    }

    private void retryWithNewProvider() {
        long thisRetryDelay = 0;
        String oldBaseUrl = priceProvider.getBaseUrl();
        boolean looped = setNewPriceProvider();
        if (looped) {
            log.warn("Exhausted price provider list, looping to beginning");
            if (System.currentTimeMillis() - lastLoopTs < PERIOD_SEC * 1000) {
                retryDelay = Math.min(retryDelay + 5, PERIOD_SEC);
            } else {
                retryDelay = 0;
            }
            lastLoopTs = System.currentTimeMillis();
            thisRetryDelay = retryDelay;
        }
        log.info("We received an error at the request from provider {}. " +
                "We select the new provider {} and use that for a new request in {} sec.", oldBaseUrl, priceProvider.getBaseUrl(), thisRetryDelay);
        if (thisRetryDelay > 0) {
            UserThread.runAfter(() -> {
                request(true);
            }, thisRetryDelay);
        } else {
            request(true);
        }
    }

    // returns true if provider selection loops back to beginning
    private boolean setNewPriceProvider() {
        httpClient.cancelPendingRequest();
        boolean looped = providersRepository.selectNextProviderBaseUrl();
        if (!providersRepository.getBaseUrl().isEmpty()) {
            priceProvider = new PriceProvider(httpClient, providersRepository.getBaseUrl());
        } else {
            log.warn("We cannot create a new priceProvider because new base url is empty.");
        }
        return looped;
    }

    @Nullable
    public MarketPrice getMarketPrice(String currencyCode) {
        synchronized (cache) {
            return cache.getOrDefault(CurrencyUtil.getCurrencyCodeBase(currencyCode), null);
        }
    }

    private void setHavenoMarketPrice(String counterCurrencyCode, Price price) {
        UserThread.execute(() -> {
            String counterCurrencyCodeBase = CurrencyUtil.getCurrencyCodeBase(counterCurrencyCode);
            synchronized (cache) {
                if (!cache.containsKey(counterCurrencyCodeBase) || !cache.get(counterCurrencyCodeBase).isExternallyProvidedPrice()) {
                    cache.put(counterCurrencyCodeBase, new MarketPrice(counterCurrencyCodeBase,
                            MathUtils.scaleDownByPowerOf10(price.getValue(), CurrencyUtil.isCryptoCurrency(counterCurrencyCode) ? CryptoMoney.SMALLEST_UNIT_EXPONENT : TraditionalMoney.SMALLEST_UNIT_EXPONENT),
                            0,
                            false));
                }
                updateCounter.set(updateCounter.get() + 1);
            }
        });
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Setter
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void setCurrencyCode(String currencyCode) {
        UserThread.await(() -> {
            if (this.currencyCode == null || !this.currencyCode.equals(currencyCode)) {
                this.currencyCode = currencyCode;
                currencyCodeProperty.set(currencyCode);
                if (priceConsumer != null) applyPriceToConsumer();
            }
        });
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Getter
    ///////////////////////////////////////////////////////////////////////////////////////////

    public String getCurrencyCode() {
        return currencyCode;
    }

    public StringProperty currencyCodeProperty() {
        return currencyCodeProperty;
    }

    public ReadOnlyIntegerProperty updateCounterProperty() {
        return updateCounter;
    }

    public Date getLastRequestTimeStamp() {
        return new Date(epochInMillisAtLastRequest);
    }

    public void applyLatestHavenoMarketPrice(Set<TradeStatistics3> tradeStatisticsSet) {
        // takes about 10 ms for 5000 items
        Map<String, List<TradeStatistics3>> mapByCurrencyCode = new HashMap<>();
        tradeStatisticsSet.forEach(e -> {
            List<TradeStatistics3> list;
            String currencyCode = e.getCurrency();
            if (mapByCurrencyCode.containsKey(currencyCode)) {
                list = mapByCurrencyCode.get(currencyCode);
            } else {
                list = new ArrayList<>();
                mapByCurrencyCode.put(currencyCode, list);
            }
            list.add(e);
        });

        mapByCurrencyCode.values().stream()
                .filter(list -> !list.isEmpty())
                .forEach(list -> {
                    list.sort(Comparator.comparing(TradeStatistics3::getDate));
                    TradeStatistics3 tradeStatistics = list.get(list.size() - 1);
                    setHavenoMarketPrice(tradeStatistics.getCurrency(), tradeStatistics.getTradePrice());
                });
    }

    /**
     * Returns prices for all available currencies. The base currency is always XMR.
     *
     * TODO: instrument requestPrices() result and fault handlers instead of using CountDownLatch and timeout
     */
    public synchronized Map<String, MarketPrice> requestAllPrices() throws ExecutionException, InterruptedException, TimeoutException, CancellationException {
        CountDownLatch latch = new CountDownLatch(1);
        ChangeListener<? super Number> listener = (observable, oldValue, newValue) -> latch.countDown();
        UserThread.execute(() -> updateCounter.addListener(listener));
        requestAllPricesError = null;
        requestPrices();
        UserThread.runAfter(() -> {
            if (latch.getCount() > 0) requestAllPricesError = "Timeout fetching market prices within 20 seconds";
            UserThread.execute(() -> latch.countDown());
        }, 20);
        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            UserThread.execute(() -> updateCounter.removeListener(listener));
        }
        if (requestAllPricesError != null) throw new RuntimeException(requestAllPricesError);
        return cache;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    private boolean applyPriceToConsumer() {
        boolean result = false;
        String errorMessage = null;
        if (currencyCode != null) {
            String baseUrl = priceProvider.getBaseUrl();
            httpClient.setBaseUrl(baseUrl);
            if (cache.containsKey(currencyCode)) {
                try {
                    MarketPrice marketPrice = cache.get(currencyCode);
                    if (marketPrice.isExternallyProvidedPrice()) {
                        if (marketPrice.isRecentPriceAvailable()) {
                            if (priceConsumer != null)
                                priceConsumer.accept(marketPrice.getPrice());
                            result = true;
                        } else {
                            errorMessage = "Price for currency " + currencyCode + " is outdated by " +
                                    (Instant.now().getEpochSecond() - marketPrice.getTimestampSec()) / 60 + " minutes. " +
                                    "Max. allowed age of price is " + MarketPrice.MARKET_PRICE_MAX_AGE_SEC / 60 + " minutes. " +
                                    "priceProvider=" + baseUrl + ". " +
                                    "marketPrice= " + marketPrice;
                        }
                    } else {
                        if (baseUrlOfRespondingProvider == null)
                            log.debug("Market price for currency " + currencyCode + " was not delivered by provider " +
                                    baseUrl + ". That is expected at startup.");
                        else
                            log.debug("Market price for currency " + currencyCode + " is not provided by the provider " +
                                    baseUrl + ". That is expected for currencies not listed at providers.");
                        result = true;
                    }
                } catch (Throwable t) {
                    errorMessage = "Exception at applyPriceToConsumer for currency " + currencyCode +
                            ". priceProvider=" + baseUrl + ". Exception=" + t;
                }
            } else {
                log.debug("We don't have a price for currency " + currencyCode + ". priceProvider=" + baseUrl +
                        ". That is expected for currencies not listed at providers.");
                result = true;
            }
        } else {
            errorMessage = "We don't have a currency yet set. That should never happen";
        }

        if (errorMessage != null) {
            log.warn(errorMessage);
            if (faultHandler != null)
                faultHandler.handleFault(errorMessage, new PriceRequestException(errorMessage));
        }

        UserThread.execute(() -> updateCounter.set(updateCounter.get() + 1));

        return result;
    }

    private void requestAllPrices(PriceProvider provider, Runnable resultHandler, FaultHandler faultHandler) {
        if (httpClient.hasPendingRequest()) {
            log.warn("We have a pending request open. We ignore that request. httpClient {}", httpClient);
            return;
        }

        priceRequest = new PriceRequest();
        SettableFuture<Map<String, MarketPrice>> future = priceRequest.requestAllPrices(provider);
        Futures.addCallback(future, new FutureCallback<>() {
            @Override
            public void onSuccess(@Nullable Map<String, MarketPrice> result) {
                UserThread.execute(() -> {
                    checkNotNull(result, "Result must not be null at requestAllPrices");
                    // Each currency rate has a different timestamp, depending on when
                    // the priceNode aggregate rate was calculated
                    // However, the request timestamp is when the pricenode was queried
                    epochInMillisAtLastRequest = System.currentTimeMillis();

                    Map<String, MarketPrice> priceMap = result;

                    synchronized (cache) {
                        cache.putAll(priceMap);
                    }

                    resultHandler.run();
                });
            }

            @Override
            public void onFailure(@NotNull Throwable throwable) {
                UserThread.execute(() -> faultHandler.handleFault("Could not load marketPrices", throwable));
            }
        }, MoreExecutors.directExecutor());
    }
}
