package bisq.desktop.main.offer.createoffer;

import bisq.core.btc.model.XmrAddressEntry;
import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.locale.CryptoCurrency;
import bisq.core.locale.FiatCurrency;
import bisq.core.locale.GlobalSettings;
import bisq.core.locale.Res;
import bisq.core.offer.CreateOfferService;
import bisq.core.offer.OfferDirection;
import bisq.core.offer.OfferUtil;
import bisq.core.payment.ClearXchangeAccount;
import bisq.core.payment.PaymentAccount;
import bisq.core.payment.RevolutAccount;
import bisq.core.provider.price.PriceFeedService;
import bisq.core.trade.statistics.TradeStatisticsManager;
import bisq.core.user.Preferences;
import bisq.core.user.User;

import javafx.collections.FXCollections;

import java.util.HashSet;
import java.util.UUID;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class CreateOfferDataModelTest {

    private CreateOfferDataModel model;
    private User user;
    private Preferences preferences;
    private OfferUtil offerUtil;

    @Before
    public void setUp() {
        final CryptoCurrency xmr = new CryptoCurrency("XMR", "monero");
        GlobalSettings.setDefaultTradeCurrency(xmr);
        Res.setup();

        XmrAddressEntry addressEntry = mock(XmrAddressEntry.class);
        XmrWalletService xmrWalletService = mock(XmrWalletService.class);
        PriceFeedService priceFeedService = mock(PriceFeedService.class);
        CreateOfferService createOfferService = mock(CreateOfferService.class);
        preferences = mock(Preferences.class);
        offerUtil = mock(OfferUtil.class);
        user = mock(User.class);
        var tradeStats = mock(TradeStatisticsManager.class);

        when(xmrWalletService.getOrCreateAddressEntry(anyString(), any())).thenReturn(addressEntry);
        when(preferences.isUsePercentageBasedPrice()).thenReturn(true);
        when(preferences.getBuyerSecurityDepositAsPercent(null)).thenReturn(0.01);
        when(createOfferService.getRandomOfferId()).thenReturn(UUID.randomUUID().toString());
        when(tradeStats.getObservableTradeStatisticsSet()).thenReturn(FXCollections.observableSet());

        model = new CreateOfferDataModel(createOfferService,
                null,
                offerUtil,
                xmrWalletService,
                preferences,
                user,
                null,
                priceFeedService,
                null,
                null,
                tradeStats,
                null);
    }

    @Test
    public void testUseTradeCurrencySetInOfferViewWhenInPaymentAccountAvailable() {
        final HashSet<PaymentAccount> paymentAccounts = new HashSet<>();
        final ClearXchangeAccount zelleAccount = new ClearXchangeAccount();
        zelleAccount.setId("234");
        zelleAccount.setAccountName("zelleAccount");
        paymentAccounts.add(zelleAccount);
        final RevolutAccount revolutAccount = new RevolutAccount();
        revolutAccount.setId("123");
        revolutAccount.setAccountName("revolutAccount");
        revolutAccount.setSingleTradeCurrency(new FiatCurrency("EUR"));
        revolutAccount.addCurrency(new FiatCurrency("USD"));
        paymentAccounts.add(revolutAccount);

        when(user.getPaymentAccounts()).thenReturn(paymentAccounts);
        when(preferences.getSelectedPaymentAccountForCreateOffer()).thenReturn(revolutAccount);

        model.initWithData(OfferDirection.BUY, new FiatCurrency("USD"));
        assertEquals("USD", model.getTradeCurrencyCode().get());
    }

    @Test
    public void testUseTradeAccountThatMatchesTradeCurrencySetInOffer() {
        final HashSet<PaymentAccount> paymentAccounts = new HashSet<>();
        final ClearXchangeAccount zelleAccount = new ClearXchangeAccount();
        zelleAccount.setId("234");
        zelleAccount.setAccountName("zelleAccount");
        paymentAccounts.add(zelleAccount);
        final RevolutAccount revolutAccount = new RevolutAccount();
        revolutAccount.setId("123");
        revolutAccount.setAccountName("revolutAccount");
        revolutAccount.setSingleTradeCurrency(new FiatCurrency("EUR"));
        paymentAccounts.add(revolutAccount);

        when(user.getPaymentAccounts()).thenReturn(paymentAccounts);
        when(user.findFirstPaymentAccountWithCurrency(new FiatCurrency("USD"))).thenReturn(zelleAccount);
        when(preferences.getSelectedPaymentAccountForCreateOffer()).thenReturn(revolutAccount);

        model.initWithData(OfferDirection.BUY, new FiatCurrency("USD"));
        assertEquals("USD", model.getTradeCurrencyCode().get());
    }
}
