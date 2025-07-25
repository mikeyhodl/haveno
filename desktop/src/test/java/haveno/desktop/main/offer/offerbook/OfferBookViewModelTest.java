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

package haveno.desktop.main.offer.offerbook;

import com.natpryce.makeiteasy.Maker;
import haveno.common.config.Config;
import haveno.core.locale.Country;
import haveno.core.locale.CryptoCurrency;
import haveno.core.locale.TraditionalCurrency;
import haveno.core.locale.GlobalSettings;
import haveno.core.locale.Res;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferPayload;
import haveno.core.offer.OpenOfferManager;
import haveno.core.payment.AliPayAccount;
import haveno.core.payment.CountryBasedPaymentAccount;
import haveno.core.payment.CryptoCurrencyAccount;
import haveno.core.payment.NationalBankAccount;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.PaymentAccountUtil;
import haveno.core.payment.SameBankAccount;
import haveno.core.payment.SepaAccount;
import haveno.core.payment.SpecificBanksAccount;
import haveno.core.payment.payload.NationalBankAccountPayload;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.payment.payload.SameBankAccountPayload;
import haveno.core.payment.payload.SepaAccountPayload;
import haveno.core.payment.payload.SpecificBanksAccountPayload;
import haveno.core.provider.price.MarketPrice;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.trade.statistics.TradeStatisticsManager;
import haveno.core.user.User;
import haveno.core.util.PriceUtil;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.util.coin.ImmutableCoinFormatter;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;

import static com.natpryce.makeiteasy.MakeItEasy.make;
import static com.natpryce.makeiteasy.MakeItEasy.with;
import static haveno.desktop.main.offer.offerbook.OfferBookListItemMaker.amount;
import static haveno.desktop.main.offer.offerbook.OfferBookListItemMaker.marketPriceMargin;
import static haveno.desktop.main.offer.offerbook.OfferBookListItemMaker.minAmount;
import static haveno.desktop.main.offer.offerbook.OfferBookListItemMaker.price;
import static haveno.desktop.main.offer.offerbook.OfferBookListItemMaker.useMarketBasedPrice;
import static haveno.desktop.main.offer.offerbook.OfferBookListItemMaker.xmrBuyItem;
import static haveno.desktop.main.offer.offerbook.OfferBookListItemMaker.xmrItemWithRange;
import static haveno.desktop.maker.PreferenceMakers.empty;
import static haveno.desktop.maker.TradeCurrencyMakers.usd;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class OfferBookViewModelTest {
    private final CoinFormatter coinFormatter = new ImmutableCoinFormatter(Config.baseCurrencyNetworkParameters().getMonetaryFormat());
    private static final Logger log = LoggerFactory.getLogger(OfferBookViewModelTest.class);
    private User user;

    @BeforeEach
    public void setUp() {
        GlobalSettings.setDefaultTradeCurrency(usd);
        Res.setBaseCurrencyCode(usd.getCode());
        Res.setBaseCurrencyName(usd.getName());
        user = mock(User.class);
        when(user.hasPaymentAccountForCurrency(any())).thenReturn(true);
    }

    private PriceUtil getPriceUtil() {
        PriceFeedService priceFeedService = mock(PriceFeedService.class);
        TradeStatisticsManager tradeStatisticsManager = mock(TradeStatisticsManager.class);
        when(tradeStatisticsManager.getObservableTradeStatisticsSet()).thenReturn(FXCollections.observableSet());
        return new PriceUtil(priceFeedService, tradeStatisticsManager, empty);
    }

    @Disabled("PaymentAccountPayload needs to be set (has been changed with PB changes)")
    public void testIsAnyPaymentAccountValidForOffer() {
        Collection<PaymentAccount> paymentAccounts;
        paymentAccounts = new ArrayList<>(FXCollections.singletonObservableList(getSepaAccount("EUR", "DE", "1212324",
                new ArrayList<>(Arrays.asList("AT", "DE")))));
        assertTrue(PaymentAccountUtil.isAnyPaymentAccountValidForOffer(
                getSEPAPaymentMethod("EUR", "AT", new ArrayList<>(Arrays.asList("AT", "DE")), "PSK"), paymentAccounts));


        // empty paymentAccounts
        paymentAccounts = new ArrayList<>();
        assertFalse(PaymentAccountUtil.isAnyPaymentAccountValidForOffer(getSEPAPaymentMethod("EUR", "AT",
                new ArrayList<>(Arrays.asList("AT", "DE")), "PSK"), paymentAccounts));

        // simple cases: same payment methods

        // offer: alipay paymentAccount: alipay - same country, same currency
        paymentAccounts = new ArrayList<>(FXCollections.singletonObservableList(getAliPayAccount("CNY")));
        assertTrue(PaymentAccountUtil.isAnyPaymentAccountValidForOffer(
                getAliPayPaymentMethod("EUR"), paymentAccounts));

        // offer: ether paymentAccount: ether - same country, same currency
        paymentAccounts = new ArrayList<>(FXCollections.singletonObservableList(getCryptoAccount("ETH")));
        assertTrue(PaymentAccountUtil.isAnyPaymentAccountValidForOffer(
                getBlockChainsPaymentMethod("ETH"), paymentAccounts));

        // offer: sepa paymentAccount: sepa - same country, same currency
        paymentAccounts = new ArrayList<>(FXCollections.singletonObservableList(getSepaAccount("EUR", "AT", "1212324",
                new ArrayList<>(Arrays.asList("AT", "DE")))));
        assertTrue(PaymentAccountUtil.isAnyPaymentAccountValidForOffer(
                getSEPAPaymentMethod("EUR", "AT", new ArrayList<>(Arrays.asList("AT", "DE")), "PSK"), paymentAccounts));

        // offer: nationalBank paymentAccount: nationalBank - same country, same currency
        paymentAccounts = new ArrayList<>(FXCollections.singletonObservableList(getNationalBankAccount("EUR", "AT", "PSK")));
        assertTrue(PaymentAccountUtil.isAnyPaymentAccountValidForOffer(
                getNationalBankPaymentMethod("EUR", "AT", "PSK"), paymentAccounts));

        // offer: SameBank paymentAccount: SameBank - same country, same currency
        paymentAccounts = new ArrayList<>(FXCollections.singletonObservableList(getSameBankAccount("EUR", "AT", "PSK")));
        assertTrue(PaymentAccountUtil.isAnyPaymentAccountValidForOffer(
                getSameBankPaymentMethod("EUR", "AT", "PSK"), paymentAccounts));

        // offer: sepa paymentAccount: sepa - diff. country, same currency
        paymentAccounts = new ArrayList<>(FXCollections.singletonObservableList(getSepaAccount("EUR", "DE", "1212324", new ArrayList<>(Arrays.asList("AT", "DE")))));
        assertTrue(PaymentAccountUtil.isAnyPaymentAccountValidForOffer(
                getSEPAPaymentMethod("EUR", "AT", new ArrayList<>(Arrays.asList("AT", "DE")), "PSK"), paymentAccounts));


        //////

        // offer: sepa paymentAccount: sepa - same country, same currency
        paymentAccounts = new ArrayList<>(FXCollections.singletonObservableList(getSepaAccount("EUR", "AT", "1212324", new ArrayList<>(Arrays.asList("AT", "DE")))));
        assertTrue(PaymentAccountUtil.isAnyPaymentAccountValidForOffer(
                getSEPAPaymentMethod("EUR", "AT", new ArrayList<>(Arrays.asList("AT", "DE")), "PSK"), paymentAccounts));


        // offer: sepa paymentAccount: nationalBank - same country, same currency
        // wrong method
        paymentAccounts = new ArrayList<>(FXCollections.singletonObservableList(getNationalBankAccount("EUR", "AT", "PSK")));
        assertFalse(PaymentAccountUtil.isAnyPaymentAccountValidForOffer(
                getSEPAPaymentMethod("EUR", "AT", new ArrayList<>(Arrays.asList("AT", "DE")), "PSK"), paymentAccounts));

        // wrong currency
        paymentAccounts = new ArrayList<>(FXCollections.singletonObservableList(getNationalBankAccount("USD", "US", "XXX")));
        assertFalse(PaymentAccountUtil.isAnyPaymentAccountValidForOffer(
                getNationalBankPaymentMethod("EUR", "AT", "PSK"), paymentAccounts));

        // wrong country
        paymentAccounts = new ArrayList<>(FXCollections.singletonObservableList(getNationalBankAccount("EUR", "FR", "PSK")));
        assertFalse(PaymentAccountUtil.isAnyPaymentAccountValidForOffer(
                getNationalBankPaymentMethod("EUR", "AT", "PSK"), paymentAccounts));

        // sepa wrong country
        paymentAccounts = new ArrayList<>(FXCollections.singletonObservableList(getNationalBankAccount("EUR", "CH", "PSK")));
        assertFalse(PaymentAccountUtil.isAnyPaymentAccountValidForOffer(
                getSEPAPaymentMethod("EUR", "AT", new ArrayList<>(Arrays.asList("AT", "DE")), "PSK"), paymentAccounts));

        // sepa wrong currency
        paymentAccounts = new ArrayList<>(FXCollections.singletonObservableList(getNationalBankAccount("CHF", "DE", "PSK")));
        assertFalse(PaymentAccountUtil.isAnyPaymentAccountValidForOffer(
                getSEPAPaymentMethod("EUR", "AT", new ArrayList<>(Arrays.asList("AT", "DE")), "PSK"), paymentAccounts));


        // same bank
        paymentAccounts = new ArrayList<>(FXCollections.singletonObservableList(getSameBankAccount("EUR", "AT", "PSK")));
        assertTrue(PaymentAccountUtil.isAnyPaymentAccountValidForOffer(
                getNationalBankPaymentMethod("EUR", "AT", "PSK"), paymentAccounts));

        // not same bank
        paymentAccounts = new ArrayList<>(FXCollections.singletonObservableList(getSameBankAccount("EUR", "AT", "Raika")));
        assertFalse(PaymentAccountUtil.isAnyPaymentAccountValidForOffer(
                getNationalBankPaymentMethod("EUR", "AT", "PSK"), paymentAccounts));

        // same bank, wrong country
        paymentAccounts = new ArrayList<>(FXCollections.singletonObservableList(getSameBankAccount("EUR", "DE", "PSK")));
        assertFalse(PaymentAccountUtil.isAnyPaymentAccountValidForOffer(
                getNationalBankPaymentMethod("EUR", "AT", "PSK"), paymentAccounts));

        // same bank, wrong currency
        paymentAccounts = new ArrayList<>(FXCollections.singletonObservableList(getSameBankAccount("USD", "AT", "PSK")));
        assertFalse(PaymentAccountUtil.isAnyPaymentAccountValidForOffer(
                getNationalBankPaymentMethod("EUR", "AT", "PSK"), paymentAccounts));

        // spec. bank
        paymentAccounts = new ArrayList<>(FXCollections.singletonObservableList(getSpecificBanksAccount("EUR", "AT", "PSK",
                new ArrayList<>(Arrays.asList("PSK", "Raika")))));
        assertTrue(PaymentAccountUtil.isAnyPaymentAccountValidForOffer(
                getNationalBankPaymentMethod("EUR", "AT", "PSK"), paymentAccounts));

        // spec. bank, missing bank
        paymentAccounts = new ArrayList<>(FXCollections.singletonObservableList(getSpecificBanksAccount("EUR", "AT", "PSK",
                new ArrayList<>(FXCollections.singletonObservableList("Raika")))));
        assertFalse(PaymentAccountUtil.isAnyPaymentAccountValidForOffer(
                getNationalBankPaymentMethod("EUR", "AT", "PSK"), paymentAccounts));

        // spec. bank, wrong country
        paymentAccounts = new ArrayList<>(FXCollections.singletonObservableList(getSpecificBanksAccount("EUR", "FR", "PSK",
                new ArrayList<>(Arrays.asList("PSK", "Raika")))));
        assertFalse(PaymentAccountUtil.isAnyPaymentAccountValidForOffer(
                getNationalBankPaymentMethod("EUR", "AT", "PSK"), paymentAccounts));

        // spec. bank, wrong currency
        paymentAccounts = new ArrayList<>(FXCollections.singletonObservableList(getSpecificBanksAccount("USD", "AT", "PSK",
                new ArrayList<>(Arrays.asList("PSK", "Raika")))));
        assertFalse(PaymentAccountUtil.isAnyPaymentAccountValidForOffer(
                getNationalBankPaymentMethod("EUR", "AT", "PSK"), paymentAccounts));

        //TODO add more tests

    }

    @Test
    public void testMaxCharactersForAmountWithNoOffers() {
        OfferBook offerBook = mock(OfferBook.class);
        final ObservableList<OfferBookListItem> offerBookListItems = FXCollections.observableArrayList();

        when(offerBook.getOfferBookListItems()).thenReturn(offerBookListItems);

        final OfferBookViewModel model = new FiatOfferBookViewModel(null, null, offerBook, empty, null, null, null,
                null, null, null, getPriceUtil(), null, coinFormatter, null);
        assertEquals(0, model.maxPlacesForAmount.intValue());
    }

    @Test
    public void testMaxCharactersForAmount() {
        OfferBook offerBook = mock(OfferBook.class);
        OpenOfferManager openOfferManager = mock(OpenOfferManager.class);
        final ObservableList<OfferBookListItem> offerBookListItems = FXCollections.observableArrayList();
        offerBookListItems.addAll(make(OfferBookListItemMaker.xmrBuyItem));

        when(offerBook.getOfferBookListItems()).thenReturn(offerBookListItems);

        final OfferBookViewModel model = new FiatOfferBookViewModel(user, openOfferManager, offerBook, empty, null, null, null,
                null, null, null, getPriceUtil(), null, coinFormatter, null);
        model.activate();

        assertEquals(6, model.maxPlacesForAmount.intValue());
        offerBookListItems.addAll(make(xmrBuyItem.but(with(amount, 20000000000000L))));
        assertEquals(7, model.maxPlacesForAmount.intValue());
    }

    @Test
    public void testMaxCharactersForAmountRange() {
        OfferBook offerBook = mock(OfferBook.class);
        OpenOfferManager openOfferManager = mock(OpenOfferManager.class);
        final ObservableList<OfferBookListItem> offerBookListItems = FXCollections.observableArrayList();
        offerBookListItems.addAll(make(OfferBookListItemMaker.xmrItemWithRange));

        when(offerBook.getOfferBookListItems()).thenReturn(offerBookListItems);

        final OfferBookViewModel model = new FiatOfferBookViewModel(user, openOfferManager, offerBook, empty, null, null, null,
                null, null, null, getPriceUtil(), null, coinFormatter, null);
        model.activate();

        assertEquals(15, model.maxPlacesForAmount.intValue());
        offerBookListItems.addAll(make(xmrItemWithRange.but(with(amount, 20000000000000L))));
        assertEquals(16, model.maxPlacesForAmount.intValue());
        offerBookListItems.addAll(make(xmrItemWithRange.but(with(minAmount, 300000000000000L),
                with(amount, 300000000000000L))));
        assertEquals(19, model.maxPlacesForAmount.intValue());
    }

    @Test
    public void testMaxCharactersForVolumeWithNoOffers() {
        OfferBook offerBook = mock(OfferBook.class);
        final ObservableList<OfferBookListItem> offerBookListItems = FXCollections.observableArrayList();

        when(offerBook.getOfferBookListItems()).thenReturn(offerBookListItems);

        final OfferBookViewModel model = new FiatOfferBookViewModel(null, null, offerBook, empty, null, null, null,
                null, null, null, getPriceUtil(), null, coinFormatter, null);
        assertEquals(0, model.maxPlacesForVolume.intValue());
    }

    @Test
    public void testMaxCharactersForVolume() {
        OfferBook offerBook = mock(OfferBook.class);
        OpenOfferManager openOfferManager = mock(OpenOfferManager.class);
        final ObservableList<OfferBookListItem> offerBookListItems = FXCollections.observableArrayList();
        offerBookListItems.addAll(make(OfferBookListItemMaker.xmrBuyItem));

        when(offerBook.getOfferBookListItems()).thenReturn(offerBookListItems);

        final OfferBookViewModel model = new FiatOfferBookViewModel(user, openOfferManager, offerBook, empty, null, null, null,
                null, null, null, getPriceUtil(), null, coinFormatter, null);
        model.activate();

        assertEquals(7, model.maxPlacesForVolume.intValue());
        offerBookListItems.addAll(make(xmrBuyItem.but(with(amount, 20000000000000L))));
        assertEquals(9, model.maxPlacesForVolume.intValue());
    }

    @Test
    public void testMaxCharactersForVolumeRange() {
        OfferBook offerBook = mock(OfferBook.class);
        OpenOfferManager openOfferManager = mock(OpenOfferManager.class);
        final ObservableList<OfferBookListItem> offerBookListItems = FXCollections.observableArrayList();
        offerBookListItems.addAll(make(OfferBookListItemMaker.xmrItemWithRange));

        when(offerBook.getOfferBookListItems()).thenReturn(offerBookListItems);

        final OfferBookViewModel model = new FiatOfferBookViewModel(user, openOfferManager, offerBook, empty, null, null, null,
                null, null, null, getPriceUtil(), null, coinFormatter, null);
        model.activate();

        assertEquals(9, model.maxPlacesForVolume.intValue());
        offerBookListItems.addAll(make(xmrItemWithRange.but(with(amount, 200000000000000000L))));
        assertEquals(11, model.maxPlacesForVolume.intValue());
        offerBookListItems.addAll(make(xmrItemWithRange.but(with(minAmount, 3000000000000000000L), with(amount, 3000000000000000000L))));
        assertEquals(19, model.maxPlacesForVolume.intValue());
    }

    @Test
    public void testMaxCharactersForPriceWithNoOffers() {
        OfferBook offerBook = mock(OfferBook.class);
        final ObservableList<OfferBookListItem> offerBookListItems = FXCollections.observableArrayList();

        when(offerBook.getOfferBookListItems()).thenReturn(offerBookListItems);

        final OfferBookViewModel model = new FiatOfferBookViewModel(null, null, offerBook, empty, null, null, null,
                null, null, null, getPriceUtil(), null, coinFormatter, null);
        assertEquals(0, model.maxPlacesForPrice.intValue());
    }

    @Test
    public void testMaxCharactersForPrice() {
        OfferBook offerBook = mock(OfferBook.class);
        OpenOfferManager openOfferManager = mock(OpenOfferManager.class);
        final ObservableList<OfferBookListItem> offerBookListItems = FXCollections.observableArrayList();
        offerBookListItems.addAll(make(OfferBookListItemMaker.xmrBuyItem));

        when(offerBook.getOfferBookListItems()).thenReturn(offerBookListItems);

        final OfferBookViewModel model = new FiatOfferBookViewModel(user, openOfferManager, offerBook, empty, null, null, null,
                null, null, null, getPriceUtil(), null, coinFormatter, null);
        model.activate();

        assertEquals(9, model.maxPlacesForPrice.intValue());
        offerBookListItems.addAll(make(xmrBuyItem.but(with(price, 1495582400000L)))); //149558240
        assertEquals(10, model.maxPlacesForPrice.intValue());
        offerBookListItems.addAll(make(xmrBuyItem.but(with(price, 149558240000L)))); //149558240
        assertEquals(10, model.maxPlacesForPrice.intValue());
    }

    @Test
    public void testMaxCharactersForPriceDistanceWithNoOffers() {
        OfferBook offerBook = mock(OfferBook.class);
        final ObservableList<OfferBookListItem> offerBookListItems = FXCollections.observableArrayList();

        when(offerBook.getOfferBookListItems()).thenReturn(offerBookListItems);

        final OfferBookViewModel model = new FiatOfferBookViewModel(null, null, offerBook, empty, null, null, null,
                null, null, null, getPriceUtil(), null, coinFormatter, null);
        assertEquals(0, model.maxPlacesForMarketPriceMargin.intValue());
    }

    @Test
    public void testMaxCharactersForPriceDistance() {
        OfferBook offerBook = mock(OfferBook.class);
        OpenOfferManager openOfferManager = mock(OpenOfferManager.class);
        PriceFeedService priceFeedService = mock(PriceFeedService.class);

        final ObservableList<OfferBookListItem> offerBookListItems = FXCollections.observableArrayList();
        final Maker<OfferBookListItem> item = xmrBuyItem.but(with(useMarketBasedPrice, true));

        when(offerBook.getOfferBookListItems()).thenReturn(offerBookListItems);
        when(priceFeedService.getMarketPrice(anyString())).thenReturn(null);
        when(priceFeedService.updateCounterProperty()).thenReturn(new SimpleIntegerProperty());

        final OfferBookListItem item1 = make(item);
        assertNotNull(item1.getHashOfPayload());
        item1.getOffer().setPriceFeedService(priceFeedService);

        final OfferBookListItem item2 = make(item.but(with(marketPriceMargin, 0.0197)));
        assertNotNull(item2.getHashOfPayload());
        item2.getOffer().setPriceFeedService(priceFeedService);

        final OfferBookListItem item3 = make(item.but(with(marketPriceMargin, 0.1)));
        assertNotNull(item3.getHashOfPayload());
        item3.getOffer().setPriceFeedService(priceFeedService);

        final OfferBookListItem item4 = make(item.but(with(marketPriceMargin, -0.1)));
        assertNotNull(item4.getHashOfPayload());
        item4.getOffer().setPriceFeedService(priceFeedService);
        offerBookListItems.addAll(item1, item2);

        final OfferBookViewModel model = new FiatOfferBookViewModel(user, openOfferManager, offerBook, empty, null, null, priceFeedService,
                null, null, null, getPriceUtil(), null, coinFormatter, null);
        model.activate();

        assertEquals(8, model.maxPlacesForMarketPriceMargin.intValue()); //" (1.97%)"
        offerBookListItems.addAll(item3);
        assertEquals(9, model.maxPlacesForMarketPriceMargin.intValue()); //" (10.00%)"
        offerBookListItems.addAll(item4);
        assertEquals(10, model.maxPlacesForMarketPriceMargin.intValue()); //" (-10.00%)"
    }

    @Test
    public void testGetPrice() {
        OfferBook offerBook = mock(OfferBook.class);
        OpenOfferManager openOfferManager = mock(OpenOfferManager.class);
        PriceFeedService priceFeedService = mock(PriceFeedService.class);

        final ObservableList<OfferBookListItem> offerBookListItems = FXCollections.observableArrayList();
        when(offerBook.getOfferBookListItems()).thenReturn(offerBookListItems);
        when(priceFeedService.getMarketPrice(anyString())).thenReturn(new MarketPrice("USD", 12684.0450, Instant.now().getEpochSecond(), true));

        final OfferBookViewModel model = new FiatOfferBookViewModel(user, openOfferManager, offerBook, empty, null, null, null,
                null, null, null, getPriceUtil(), null, coinFormatter, null);

        final OfferBookListItem item = make(xmrBuyItem.but(
                with(useMarketBasedPrice, true),
                with(marketPriceMargin, -0.12)));
        assertNotNull(item.getHashOfPayload());

        final OfferBookListItem lowItem = make(xmrBuyItem.but(
                with(useMarketBasedPrice, true),
                with(marketPriceMargin, 0.01)));
        assertNotNull(lowItem.getHashOfPayload());

        final OfferBookListItem fixedItem = make(xmrBuyItem);
        assertNotNull(fixedItem.getHashOfPayload());

        item.getOffer().setPriceFeedService(priceFeedService);
        lowItem.getOffer().setPriceFeedService(priceFeedService);
        offerBookListItems.addAll(lowItem, fixedItem);
        model.activate();

        assertEquals("12557.2046", model.getPrice(lowItem));
        assertEquals("(1.00%)", model.getPriceAsPercentage(lowItem));
        assertEquals("1000.0000", model.getPrice(fixedItem));
        offerBookListItems.addAll(item);
        assertEquals("14206.1304", model.getPrice(item));
        assertEquals("(-12.00%)", model.getPriceAsPercentage(item));
        assertEquals("12557.2046", model.getPrice(lowItem));
        assertEquals("(1.00%)", model.getPriceAsPercentage(lowItem));
    }

    private PaymentAccount getAliPayAccount(String currencyCode) {
        PaymentAccount paymentAccount = new AliPayAccount();
        paymentAccount.setSelectedTradeCurrency(new TraditionalCurrency(currencyCode));
        return paymentAccount;
    }

    private PaymentAccount getCryptoAccount(String currencyCode) {
        PaymentAccount paymentAccount = new CryptoCurrencyAccount();
        paymentAccount.addCurrency(new CryptoCurrency(currencyCode, null));
        return paymentAccount;
    }

    private PaymentAccount getSepaAccount(String currencyCode,
                                          String countryCode,
                                          String bic,
                                          ArrayList<String> countryCodes) {
        CountryBasedPaymentAccount paymentAccount = new SepaAccount();
        paymentAccount.setSingleTradeCurrency(new TraditionalCurrency(currencyCode));
        paymentAccount.setCountry(new Country(countryCode, null, null));
        ((SepaAccountPayload) paymentAccount.getPaymentAccountPayload()).setBic(bic);
        countryCodes.forEach(((SepaAccountPayload) paymentAccount.getPaymentAccountPayload())::addAcceptedCountry);
        return paymentAccount;
    }

    private PaymentAccount getNationalBankAccount(String currencyCode, String countryCode, String bankId) {
        CountryBasedPaymentAccount paymentAccount = new NationalBankAccount();
        paymentAccount.setSingleTradeCurrency(new TraditionalCurrency(currencyCode));
        paymentAccount.setCountry(new Country(countryCode, null, null));
        ((NationalBankAccountPayload) paymentAccount.getPaymentAccountPayload()).setBankId(bankId);
        return paymentAccount;
    }

    private PaymentAccount getSameBankAccount(String currencyCode, String countryCode, String bankId) {
        SameBankAccount paymentAccount = new SameBankAccount();
        paymentAccount.setSingleTradeCurrency(new TraditionalCurrency(currencyCode));
        paymentAccount.setCountry(new Country(countryCode, null, null));
        ((SameBankAccountPayload) paymentAccount.getPaymentAccountPayload()).setBankId(bankId);
        return paymentAccount;
    }

    private PaymentAccount getSpecificBanksAccount(String currencyCode,
                                                   String countryCode,
                                                   String bankId,
                                                   ArrayList<String> bankIds) {
        SpecificBanksAccount paymentAccount = new SpecificBanksAccount();
        paymentAccount.setSingleTradeCurrency(new TraditionalCurrency(currencyCode));
        paymentAccount.setCountry(new Country(countryCode, null, null));
        ((SpecificBanksAccountPayload) paymentAccount.getPaymentAccountPayload()).setBankId(bankId);
        bankIds.forEach(((SpecificBanksAccountPayload) paymentAccount.getPaymentAccountPayload())::addAcceptedBank);
        return paymentAccount;
    }


    private Offer getBlockChainsPaymentMethod(String currencyCode) {
        return getOffer(currencyCode,
                PaymentMethod.BLOCK_CHAINS_ID,
                null,
                null,
                null,
                null);
    }

    private Offer getAliPayPaymentMethod(String currencyCode) {
        return getOffer(currencyCode,
                PaymentMethod.ALI_PAY_ID,
                null,
                null,
                null,
                null);
    }

    private Offer getSEPAPaymentMethod(String currencyCode,
                                       String countryCode,
                                       ArrayList<String> countryCodes,
                                       String bankId) {
        return getPaymentMethod(currencyCode,
                PaymentMethod.SEPA_ID,
                countryCode,
                countryCodes,
                bankId,
                null);
    }

    private Offer getNationalBankPaymentMethod(String currencyCode, String countryCode, String bankId) {
        return getPaymentMethod(currencyCode,
                PaymentMethod.NATIONAL_BANK_ID,
                countryCode,
                new ArrayList<>(FXCollections.singletonObservableList(countryCode)),
                bankId,
                null);
    }

    private Offer getSameBankPaymentMethod(String currencyCode, String countryCode, String bankId) {
        return getPaymentMethod(currencyCode,
                PaymentMethod.SAME_BANK_ID,
                countryCode,
                new ArrayList<>(FXCollections.singletonObservableList(countryCode)),
                bankId,
                new ArrayList<>(FXCollections.singletonObservableList(bankId)));
    }

    private Offer getSpecificBanksPaymentMethod(String currencyCode,
                                                String countryCode,
                                                String bankId,
                                                ArrayList<String> bankIds) {
        return getPaymentMethod(currencyCode,
                PaymentMethod.SPECIFIC_BANKS_ID,
                countryCode,
                new ArrayList<>(FXCollections.singletonObservableList(countryCode)),
                bankId,
                bankIds);
    }

    private Offer getPaymentMethod(String currencyCode,
                                   String paymentMethodId,
                                   String countryCode,
                                   ArrayList<String> countryCodes,
                                   String bankId,
                                   ArrayList<String> bankIds) {
        return getOffer(currencyCode,
                paymentMethodId,
                countryCode,
                countryCodes,
                bankId,
                bankIds);
    }


    private Offer getOffer(String tradeCurrencyCode,
                           String paymentMethodId,
                           String countryCode,
                           ArrayList<String> acceptedCountryCodes,
                           String bankId,
                           ArrayList<String> acceptedBanks) {
        return new Offer(new OfferPayload(null,
                0,
                null,
                null,
                null,
                0,
                0,
                false,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                "BTC",
                tradeCurrencyCode,
                paymentMethodId,
                null,
                countryCode,
                acceptedCountryCodes,
                bankId,
                acceptedBanks,
                null,
                0,
                0,
                0,
                false,
                false,
                0,
                0,
                false,
                null,
                null,
                0,
                null,
                null,
                null,
                null));
    }
}

