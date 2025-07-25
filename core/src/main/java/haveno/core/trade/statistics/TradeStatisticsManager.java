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

package haveno.core.trade.statistics;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import haveno.common.config.Config;
import haveno.common.file.JsonFileManager;
import haveno.core.locale.CurrencyTuple;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.Trade;
import haveno.core.util.JsonUtil;
import haveno.network.p2p.P2PService;
import haveno.network.p2p.storage.P2PDataStorage;
import haveno.network.p2p.storage.persistence.AppendOnlyDataStoreService;
import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javafx.collections.FXCollections;
import javafx.collections.ObservableSet;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

@Singleton
@Slf4j
public class TradeStatisticsManager {
    private final P2PService p2PService;
    private final PriceFeedService priceFeedService;
    private final TradeStatistics3StorageService tradeStatistics3StorageService;
    private final File storageDir;
    private final boolean dumpStatistics;
    private final ObservableSet<TradeStatistics3> observableTradeStatisticsSet = FXCollections.observableSet();
    private JsonFileManager jsonFileManager;

    @Inject
    public TradeStatisticsManager(P2PService p2PService,
                                  PriceFeedService priceFeedService,
                                  TradeStatistics3StorageService tradeStatistics3StorageService,
                                  AppendOnlyDataStoreService appendOnlyDataStoreService,
                                  @Named(Config.STORAGE_DIR) File storageDir,
                                  @Named(Config.DUMP_STATISTICS) boolean dumpStatistics) {
        this.p2PService = p2PService;
        this.priceFeedService = priceFeedService;
        this.tradeStatistics3StorageService = tradeStatistics3StorageService;
        this.storageDir = storageDir;
        this.dumpStatistics = dumpStatistics;

        appendOnlyDataStoreService.addService(tradeStatistics3StorageService);
        HavenoUtils.tradeStatisticsManager = this;
    }

    public void shutDown() {
        if (jsonFileManager != null) {
            jsonFileManager.shutDown();
        }
    }

    public void onAllServicesInitialized() {
        p2PService.getP2PDataStorage().addAppendOnlyDataStoreListener(payload -> {
            if (payload instanceof TradeStatistics3) {
                TradeStatistics3 tradeStatistics = (TradeStatistics3) payload;
                if (!tradeStatistics.isValid()) {
                    return;
                }
                synchronized (observableTradeStatisticsSet) {
                    observableTradeStatisticsSet.add(tradeStatistics);
                    priceFeedService.applyLatestHavenoMarketPrice(observableTradeStatisticsSet);
                }
                maybeDumpStatistics();
            }
        });

        Set<TradeStatistics3> set = tradeStatistics3StorageService.getMapOfAllData().values().stream()
                .filter(e -> e instanceof TradeStatistics3)
                .map(e -> (TradeStatistics3) e)
                .filter(TradeStatistics3::isValid)
                .collect(Collectors.toSet());
        

        // remove duplicates in early trade stats due to bugs
        removeDuplicateStats(set);

        synchronized (observableTradeStatisticsSet) {
            observableTradeStatisticsSet.addAll(set);
            priceFeedService.applyLatestHavenoMarketPrice(observableTradeStatisticsSet);
        }
        maybeDumpStatistics();
    }

    private void removeDuplicateStats(Set<TradeStatistics3> tradeStats) {
        removeEarlyDuplicateStats(tradeStats);
        removeEarlyDuplicateStatsFuzzy(tradeStats);
    }

    private void removeEarlyDuplicateStats(Set<TradeStatistics3> tradeStats) {
       
        // collect trades before September 30, 2024
        Set<TradeStatistics3> earlyTrades = tradeStats.stream()
                .filter(e -> e.getDate().toInstant().isBefore(Instant.parse("2024-09-30T00:00:00Z")))
                .collect(Collectors.toSet());

        // collect stats with duplicated timestamp, currency, and payment method
        Set<TradeStatistics3> duplicates = new HashSet<>();
        Set<TradeStatistics3> deduplicates = new HashSet<>();
        for (TradeStatistics3 tradeStatistic : earlyTrades) {
            TradeStatistics3 duplicate = findDuplicate(tradeStatistic, deduplicates);
            if (duplicate == null) deduplicates.add(tradeStatistic);
            else duplicates.add(tradeStatistic);
        }

        // remove duplicated stats
        tradeStats.removeAll(duplicates);
    }

    private TradeStatistics3 findDuplicate(TradeStatistics3 tradeStatistics, Set<TradeStatistics3> set) {
        return set.stream().filter(e -> isDuplicate(tradeStatistics, e)).findFirst().orElse(null);
    }

    private boolean isDuplicate(TradeStatistics3 tradeStatistics1, TradeStatistics3 tradeStatistics2) {
        if (!tradeStatistics1.getPaymentMethodId().equals(tradeStatistics2.getPaymentMethodId())) return false;
        if (!tradeStatistics1.getCurrency().equals(tradeStatistics2.getCurrency())) return false;
        return tradeStatistics1.getDateAsLong() == tradeStatistics2.getDateAsLong();
    }

    private void removeEarlyDuplicateStatsFuzzy(Set<TradeStatistics3> tradeStats) {

        // collect trades before August 7, 2024
        Set<TradeStatistics3> earlyTrades = tradeStats.stream()
                .filter(e -> e.getDate().toInstant().isBefore(Instant.parse("2024-08-07T00:00:00Z")))
                .collect(Collectors.toSet());

        // collect duplicated trades
        Set<TradeStatistics3> duplicates = new HashSet<TradeStatistics3>();
        Set<TradeStatistics3> deduplicates = new HashSet<TradeStatistics3>();
        for (TradeStatistics3 tradeStatistic : earlyTrades) {
            TradeStatistics3 fuzzyDuplicate = findFuzzyDuplicate(tradeStatistic, deduplicates);
            if (fuzzyDuplicate == null) deduplicates.add(tradeStatistic);
            else duplicates.add(tradeStatistic);
        }

        // remove duplicated stats
        tradeStats.removeAll(duplicates);
    }

    private TradeStatistics3 findFuzzyDuplicate(TradeStatistics3 tradeStatistics, Set<TradeStatistics3> set) {
        return set.stream().filter(e -> isFuzzyDuplicate(tradeStatistics, e)).findFirst().orElse(null);
    }

    private boolean isFuzzyDuplicate(TradeStatistics3 tradeStatistics1, TradeStatistics3 tradeStatistics2) {
        if (!tradeStatistics1.getPaymentMethodId().equals(tradeStatistics2.getPaymentMethodId())) return false;
        if (!tradeStatistics1.getCurrency().equals(tradeStatistics2.getCurrency())) return false;
        if (tradeStatistics1.getNormalizedPrice() != tradeStatistics2.getNormalizedPrice()) return false;
        return isFuzzyDuplicateV1(tradeStatistics1, tradeStatistics2) || isFuzzyDuplicateV2(tradeStatistics1, tradeStatistics2);
    }

    // bug caused all peers to publish same trade with similar timestamps
    private boolean isFuzzyDuplicateV1(TradeStatistics3 tradeStatistics1, TradeStatistics3 tradeStatistics2) {
        boolean isWithin2Minutes = Math.abs(tradeStatistics1.getDate().getTime() - tradeStatistics2.getDate().getTime()) <= TimeUnit.MINUTES.toMillis(2);
        return isWithin2Minutes;
    }

    // bug caused sellers to re-publish their trades with randomized amounts
    private static final double FUZZ_AMOUNT_PCT = 0.05;
    private static final int FUZZ_DATE_HOURS = 24;
    private boolean isFuzzyDuplicateV2(TradeStatistics3 tradeStatistics1, TradeStatistics3 tradeStatistics2) {
        boolean isWithinFuzzedHours = Math.abs(tradeStatistics1.getDate().getTime() - tradeStatistics2.getDate().getTime()) <= TimeUnit.HOURS.toMillis(FUZZ_DATE_HOURS);
        boolean isWithinFuzzedAmount = Math.abs(tradeStatistics1.getAmount() - tradeStatistics2.getAmount()) <= FUZZ_AMOUNT_PCT * tradeStatistics1.getAmount();
        return isWithinFuzzedHours && isWithinFuzzedAmount;
    }

    public ObservableSet<TradeStatistics3> getObservableTradeStatisticsSet() {
        return observableTradeStatisticsSet;
    }

    private void maybeDumpStatistics() {
        if (!dumpStatistics) {
            return;
        }

        if (jsonFileManager == null) {
            jsonFileManager = new JsonFileManager(storageDir);

            // We only dump once the currencies as they do not change during runtime
            ArrayList<CurrencyTuple> traditionalCurrencyList = CurrencyUtil.getAllSortedTraditionalCurrencies().stream()
                    .map(e -> new CurrencyTuple(e.getCode(), e.getName(), 8))
                    .collect(Collectors.toCollection(ArrayList::new));
            jsonFileManager.writeToDiscThreaded(JsonUtil.objectToJson(traditionalCurrencyList), "traditional_currency_list");

            ArrayList<CurrencyTuple> cryptoCurrencyList = CurrencyUtil.getAllSortedCryptoCurrencies().stream()
                    .map(e -> new CurrencyTuple(e.getCode(), e.getName(), 8))
                    .collect(Collectors.toCollection(ArrayList::new));
            cryptoCurrencyList.add(0, new CurrencyTuple(Res.getBaseCurrencyCode(), Res.getBaseCurrencyName(), 8));
            jsonFileManager.writeToDiscThreaded(JsonUtil.objectToJson(cryptoCurrencyList), "crypto_currency_list");

            Instant yearAgo = Instant.ofEpochSecond(Instant.now().getEpochSecond() - TimeUnit.DAYS.toSeconds(365));
            Set<String> activeCurrencies = observableTradeStatisticsSet.stream()
                    .filter(e -> e.getDate().toInstant().isAfter(yearAgo))
                    .map(p -> p.getCurrency())
                    .collect(Collectors.toSet());

            ArrayList<CurrencyTuple> activeTraditionalCurrencyList = traditionalCurrencyList.stream()
                    .filter(e -> activeCurrencies.contains(e.code))
                    .map(e -> new CurrencyTuple(e.code, e.name, 8))
                    .collect(Collectors.toCollection(ArrayList::new));
            jsonFileManager.writeToDiscThreaded(JsonUtil.objectToJson(activeTraditionalCurrencyList), "active_traditional_currency_list");

            ArrayList<CurrencyTuple> activeCryptoCurrencyList = cryptoCurrencyList.stream()
                    .filter(e -> activeCurrencies.contains(e.code))
                    .map(e -> new CurrencyTuple(e.code, e.name, 8))
                    .collect(Collectors.toCollection(ArrayList::new));
            jsonFileManager.writeToDiscThreaded(JsonUtil.objectToJson(activeCryptoCurrencyList), "active_crypto_currency_list");
        }

        List<TradeStatisticsForJson> list = observableTradeStatisticsSet.stream()
                .map(TradeStatisticsForJson::new)
                .sorted((o1, o2) -> (Long.compare(o2.tradeDate, o1.tradeDate)))
                .collect(Collectors.toList());
        TradeStatisticsForJson[] array = new TradeStatisticsForJson[list.size()];
        list.toArray(array);
        jsonFileManager.writeToDiscThreaded(JsonUtil.objectToJson(array), "trade_statistics");
    }

    public void maybePublishTradeStatistics(Trade trade, @Nullable String referralId, boolean isTorNetworkNode) {
        Set<Trade> trades = new HashSet<>();
        trades.add(trade);
        maybePublishTradeStatistics(trades, referralId, isTorNetworkNode);
    }

   public void maybePublishTradeStatistics(Set<Trade> trades,
                                              @Nullable String referralId,
                                              boolean isTorNetworkNode) {
        long ts = System.currentTimeMillis();
        Set<P2PDataStorage.ByteArray> hashes = tradeStatistics3StorageService.getMapOfAllData().keySet();
        trades.forEach(trade -> {
            if (!trade.shouldPublishTradeStatistics()) {
                log.debug("Trade: {} should not publish trade statistics", trade.getShortId());
                return;
            }

            TradeStatistics3 tradeStatistics3V0 = null;
            try {
                tradeStatistics3V0 = TradeStatistics3.fromV0(trade, referralId, isTorNetworkNode);
            } catch (Exception e) {
                log.warn("Error getting trade statistic for {} {}: {}", trade.getClass().getName(), trade.getId(), e.getMessage());
                return;
            }

            TradeStatistics3 tradeStatistics3V1 = null;
            try {
                tradeStatistics3V1 = TradeStatistics3.fromV1(trade, referralId, isTorNetworkNode);
            } catch (Exception e) {
                log.warn("Error getting trade statistic for {} {}: {}", trade.getClass().getName(), trade.getId(), e.getMessage());
                return;
            }

            TradeStatistics3 tradeStatistics3V2 = null;
            try {
                tradeStatistics3V2 = TradeStatistics3.fromV2(trade, referralId, isTorNetworkNode);
            } catch (Exception e) {
                log.warn("Error getting trade statistic for {} {}: {}", trade.getClass().getName(), trade.getId(), e.getMessage());
                return;
            }

            boolean hasTradeStatistics3V0 = hashes.contains(new P2PDataStorage.ByteArray(tradeStatistics3V0.getHash()));
            boolean hasTradeStatistics3V1 = hashes.contains(new P2PDataStorage.ByteArray(tradeStatistics3V1.getHash()));
            boolean hasTradeStatistics3V2 = hashes.contains(new P2PDataStorage.ByteArray(tradeStatistics3V2.getHash()));
            if (hasTradeStatistics3V0 || hasTradeStatistics3V1 || hasTradeStatistics3V2) {
                log.debug("Trade: {}. We have already a tradeStatistics matching the hash of tradeStatistics3.",
                        trade.getShortId());
                return;
            }

            if (!tradeStatistics3V2.isValid()) {
                log.warn("Trade statistics are invalid for {} {}. We do not publish: {}", trade.getClass().getSimpleName(), trade.getShortId(), tradeStatistics3V1);
                return;
            }

            log.info("Publishing trade statistics for {} {}", trade.getClass().getSimpleName(), trade.getShortId());
            p2PService.addPersistableNetworkPayload(tradeStatistics3V2, true);
        });
        log.info("maybeRepublishTradeStatistics took {} ms. Number of tradeStatistics: {}. Number of own trades: {}",
                System.currentTimeMillis() - ts, hashes.size(), trades.size());
    }
}
