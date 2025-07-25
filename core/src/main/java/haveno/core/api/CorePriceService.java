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

/*
 * This file is part of Haveno.
 *
 * Haveno is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Haveno is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Haveno. If not, see <http://www.gnu.org/licenses/>.
 */

package haveno.core.api;

import com.google.common.math.LongMath;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import haveno.core.api.model.MarketDepthInfo;
import haveno.core.api.model.MarketPriceInfo;
import haveno.core.locale.CurrencyUtil;
import haveno.core.monetary.Price;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferBookService;
import haveno.core.offer.OfferDirection;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.trade.HavenoUtils;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;


@Singleton
@Slf4j
class CorePriceService {

    private final PriceFeedService priceFeedService;
    private final OfferBookService offerBookService;

    @Inject
    public CorePriceService(PriceFeedService priceFeedService, OfferBookService offerBookService) {
        this.priceFeedService = priceFeedService;
        this.offerBookService = offerBookService;
    }

    /**
     * @return Price per 1 XMR in the given currency (traditional or crypto)
     */
    public double getMarketPrice(String currencyCode) throws ExecutionException, InterruptedException, TimeoutException, IllegalArgumentException {
        var marketPrice = priceFeedService.requestAllPrices().get(CurrencyUtil.getCurrencyCodeBase(currencyCode));
        if (marketPrice == null) {
            throw new IllegalArgumentException("Currency not found: " + currencyCode); // TODO: do not use IllegalArgumentException as message sent to client, return undefined?
        } else if (!marketPrice.isExternallyProvidedPrice()) {
            throw new IllegalArgumentException("Price is not available externally: " + currencyCode); // TODO: return more complex Price type including price double and isExternal boolean
        }
        return marketPrice.getPrice();
    }

    /**
     * @return Price per 1 XMR in all supported currencies (traditional & crypto)
     */
    public List<MarketPriceInfo> getMarketPrices() throws ExecutionException, InterruptedException, TimeoutException {
        return priceFeedService.requestAllPrices().values().stream()
                .map(marketPrice -> {
                    return new MarketPriceInfo(marketPrice.getCurrencyCode(), marketPrice.getPrice());
                })
                .collect(Collectors.toList());
    }

    /**
     * @return Data for market depth chart
     */
     public MarketDepthInfo getMarketDepth(String currencyCode) throws ExecutionException, InterruptedException, TimeoutException, IllegalArgumentException  {
        if (priceFeedService.requestAllPrices().get(currencyCode.toUpperCase()) == null) throw new IllegalArgumentException("Currency not found: " + currencyCode) ;

        // Offer price can be null (if price feed unavailable), thus a null-tolerant comparator is used.
        Comparator<Offer> offerPriceComparator = Comparator.comparing(Offer::getPrice, Comparator.nullsLast(Comparator.naturalOrder()));

        // TODO: remove this!!!
        // Trading xmr-traditional is considered as buying/selling XMR, but trading xmr-crypto is
        // considered as buying/selling crypto. Because of this, when viewing a xmr-crypto pair,
        // the buy column is actually the sell column and vice versa. To maintain the expected
        // ordering, we have to reverse the price comparator.
        //boolean isCrypto = CurrencyUtil.isCryptoCurrency(currencyCode);
        //if (isCrypto) offerPriceComparator = offerPriceComparator.reversed();

        // Offer amounts are used for the secondary sort. They are sorted from high to low.
        Comparator<Offer> offerAmountComparator = Comparator.comparing(Offer::getAmount).reversed();

        var buyOfferSortComparator =
                offerPriceComparator.reversed() // Buy offers, as opposed to sell offers, are primarily sorted from high price to low.
                        .thenComparing(offerAmountComparator);
        var sellOfferSortComparator =
                offerPriceComparator
                        .thenComparing(offerAmountComparator);
        List<Offer> buyOffers = offerBookService.getOffersByCurrency(OfferDirection.BUY.name(), currencyCode).stream().sorted(buyOfferSortComparator).collect(Collectors.toList());
        List<Offer> sellOffers = offerBookService.getOffersByCurrency(OfferDirection.SELL.name(), currencyCode).stream().sorted(sellOfferSortComparator).collect(Collectors.toList());

        // Create buyer hashmap {key:price, value:count}, uses LinkedHashMap to maintain insertion order
        double accumulatedAmount = 0;
        LinkedHashMap<Double,Double> buyTM = new LinkedHashMap<Double,Double>();
        for(Offer offer: buyOffers) {
            Price price = offer.getPrice();
            if (price != null) {
                double amount = (double) offer.getAmount().longValueExact() / LongMath.pow(10, HavenoUtils.XMR_SMALLEST_UNIT_EXPONENT);
                accumulatedAmount += amount;
                double priceAsDouble = (double) price.getValue() / LongMath.pow(10, price.smallestUnitExponent());
                buyTM.put(priceAsDouble, accumulatedAmount);
            }
        };

        // Create seller hashmap {key:price, value:count}, uses TreeMap to sort by key (asc)
        accumulatedAmount = 0;
        LinkedHashMap<Double,Double> sellTM = new LinkedHashMap<Double,Double>();
        for(Offer offer: sellOffers){
            Price price = offer.getPrice();
            if (price != null) {
                double amount = (double) offer.getAmount().longValueExact() / LongMath.pow(10, HavenoUtils.XMR_SMALLEST_UNIT_EXPONENT);
                accumulatedAmount += amount;
                double priceAsDouble = (double) price.getValue() / LongMath.pow(10, price.smallestUnitExponent());
                sellTM.put(priceAsDouble, accumulatedAmount);
            }
        };

        // Make array of buyPrices and buyDepth
        Double[] buyDepth = buyTM.values().toArray(new Double[buyTM.size()]);
        Double[] buyPrices = buyTM.keySet().toArray(new Double[buyTM.size()]);

        // Make array of sellPrices and sellDepth
        Double[] sellDepth = sellTM.values().toArray(new Double[sellTM.size()]);
        Double[] sellPrices = sellTM.keySet().toArray(new Double[sellTM.size()]);

        return new MarketDepthInfo(currencyCode, buyPrices, buyDepth, sellPrices, sellDepth);
    }
}

