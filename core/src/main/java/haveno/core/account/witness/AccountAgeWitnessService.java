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

package haveno.core.account.witness;

import com.google.common.annotations.VisibleForTesting;
import static com.google.common.base.Preconditions.checkNotNull;
import com.google.inject.Inject;
import haveno.common.UserThread;
import haveno.common.crypto.CryptoException;
import haveno.common.crypto.Hash;
import haveno.common.crypto.KeyRing;
import haveno.common.crypto.PubKeyRing;
import haveno.common.crypto.Sig;
import haveno.common.handlers.ErrorMessageHandler;
import haveno.common.util.MathUtils;
import haveno.common.util.Tuple2;
import haveno.common.util.Utilities;
import haveno.core.account.sign.SignedWitness;
import haveno.core.account.sign.SignedWitnessService;
import haveno.core.filter.FilterManager;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferDirection;
import haveno.core.offer.OfferRestrictions;
import haveno.core.payment.ChargeBackRisk;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.TradeLimits;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.support.dispute.Dispute;
import haveno.core.support.dispute.DisputeResult;
import haveno.core.support.dispute.arbitration.TraderDataItem;
import haveno.core.trade.ArbitratorTrade;
import haveno.core.trade.Trade;
import haveno.core.trade.protocol.TradePeer;
import haveno.core.user.User;
import haveno.network.p2p.BootstrapListener;
import haveno.network.p2p.P2PService;
import haveno.network.p2p.storage.P2PDataStorage;
import haveno.network.p2p.storage.persistence.AppendOnlyDataStoreService;
import java.math.BigInteger;
import java.security.PublicKey;
import java.time.Clock;
import java.util.Arrays;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Utils;

@Slf4j
public class AccountAgeWitnessService {
    private static final Date RELEASE = Utilities.getUTCDate(2017, GregorianCalendar.NOVEMBER, 11);
    private static final long SAFE_ACCOUNT_AGE_DATE = Utilities.getUTCDate(2019, GregorianCalendar.MARCH, 1).getTime();

    public enum AccountAge {
        UNVERIFIED,
        LESS_ONE_MONTH,
        ONE_TO_TWO_MONTHS,
        TWO_MONTHS_OR_MORE
    }

    public enum SignState {
        UNSIGNED(Res.get("offerbook.timeSinceSigning.notSigned")),
        ARBITRATOR(Res.get("offerbook.timeSinceSigning.info.arbitrator")),
        PEER_INITIAL(Res.get("offerbook.timeSinceSigning.info.peer")),
        PEER_LIMIT_LIFTED(Res.get("offerbook.timeSinceSigning.info.peerLimitLifted")),
        PEER_SIGNER(Res.get("offerbook.timeSinceSigning.info.signer")),
        BANNED(Res.get("offerbook.timeSinceSigning.info.banned"));

        private String displayString;
        private String hash = "";
        private long daysUntilLimitLifted = 0;

        SignState(String displayString) {
            this.displayString = displayString;
        }

        public SignState addHash(String hash) {
            this.hash = hash;
            return this;
        }

        public SignState setDaysUntilLimitLifted(long days) {
            this.daysUntilLimitLifted = days;
            return this;
        }

        public String getDisplayString() {
            if (!hash.isEmpty()) { // Only showing in DEBUG mode
                return displayString + " " + hash;
            }
            return String.format(displayString, daysUntilLimitLifted);
        }

        public boolean isLimitLifted() {
            return this == PEER_LIMIT_LIFTED || this == PEER_SIGNER || this == ARBITRATOR;
        }

    }

    private final KeyRing keyRing;
    private final P2PService p2PService;
    private final User user;
    private final SignedWitnessService signedWitnessService;
    private final ChargeBackRisk chargeBackRisk;
    private final AccountAgeWitnessStorageService accountAgeWitnessStorageService;
    private final Clock clock;
    private final FilterManager filterManager;
    @Getter
    private final AccountAgeWitnessUtils accountAgeWitnessUtils;

    private final Map<P2PDataStorage.ByteArray, AccountAgeWitness> accountAgeWitnessMap = new HashMap<>();

    // The accountAgeWitnessMap is very large (70k items) and access is a bit expensive. We usually only access less
    // than 100 items, those who have offers online. So we use a cache for a fast lookup and only if
    // not found there we use the accountAgeWitnessMap and put then the new item into our cache.
    private final Map<P2PDataStorage.ByteArray, AccountAgeWitness> accountAgeWitnessCache = new ConcurrentHashMap<>();


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////


    @Inject
    public AccountAgeWitnessService(KeyRing keyRing,
                                    P2PService p2PService,
                                    User user,
                                    SignedWitnessService signedWitnessService,
                                    ChargeBackRisk chargeBackRisk,
                                    AccountAgeWitnessStorageService accountAgeWitnessStorageService,
                                    AppendOnlyDataStoreService appendOnlyDataStoreService,
                                    Clock clock,
                                    FilterManager filterManager) {
        this.keyRing = keyRing;
        this.p2PService = p2PService;
        this.user = user;
        this.signedWitnessService = signedWitnessService;
        this.chargeBackRisk = chargeBackRisk;
        this.accountAgeWitnessStorageService = accountAgeWitnessStorageService;
        this.clock = clock;
        this.filterManager = filterManager;

        accountAgeWitnessUtils = new AccountAgeWitnessUtils(
                this,
                signedWitnessService,
                keyRing);

        // We need to add that early (before onAllServicesInitialized) as it will be used at startup.
        appendOnlyDataStoreService.addService(accountAgeWitnessStorageService);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Lifecycle
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void onAllServicesInitialized() {
        p2PService.getP2PDataStorage().addAppendOnlyDataStoreListener(payload -> {
            if (payload instanceof AccountAgeWitness)
                addToMap((AccountAgeWitness) payload);
        });

        // At startup the P2PDataStorage initializes earlier, otherwise we get the listener called.
        accountAgeWitnessStorageService.getMapOfAllData().values().stream()
                .filter(e -> e instanceof AccountAgeWitness)
                .map(e -> (AccountAgeWitness) e)
                .forEach(this::addToMap);

        if (p2PService.isBootstrapped()) {
            onBootStrapped();
        } else {
            p2PService.addP2PServiceListener(new BootstrapListener() {
                @Override
                public void onDataReceived() {
                    onBootStrapped();
                }
            });
        }
    }

    private void onBootStrapped() {
        republishAllTraditionalAccounts();
        signAndPublishSameNameAccounts();
    }


    // At startup we re-publish the witness data of all traditional accounts to ensure we got our data well distributed.
    private void republishAllTraditionalAccounts() {
        if (user.getPaymentAccounts() != null)
            user.getPaymentAccounts().stream()
                    .filter(account -> account.getPaymentMethod().isTraditional())
                    .forEach(account -> {
                        AccountAgeWitness myWitness = getMyWitness(account.getPaymentAccountPayload());
                        // We only publish if the date of our witness is inside the date tolerance.
                        // It would be rejected otherwise from the peers.
                        if (myWitness.isDateInTolerance(clock)) {
                            // We delay with a random interval of 20-60 sec to ensure to be better connected and don't
                            // stress the P2P network with publishing all at once at startup time.
                            int delayInSec = 20 + new Random().nextInt(40);
                            UserThread.runAfter(() ->
                                    p2PService.addPersistableNetworkPayload(myWitness, true), delayInSec);
                        }
                    });
    }

    @VisibleForTesting
    public void addToMap(AccountAgeWitness accountAgeWitness) {
        synchronized (this) {
            accountAgeWitnessMap.putIfAbsent(accountAgeWitness.getHashAsByteArray(), accountAgeWitness);
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Generic
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void publishMyAccountAgeWitness(PaymentAccountPayload paymentAccountPayload) {
        synchronized (this) {
            AccountAgeWitness accountAgeWitness = getMyWitness(paymentAccountPayload);
            P2PDataStorage.ByteArray hash = accountAgeWitness.getHashAsByteArray();

            // We use first our fast lookup cache. If its in accountAgeWitnessCache it is also in accountAgeWitnessMap
            // and we do not publish.
            if (accountAgeWitnessCache.containsKey(hash)) {
                return;
            }

            if (!accountAgeWitnessMap.containsKey(hash)) {
                p2PService.addPersistableNetworkPayload(accountAgeWitness, false);
            }
        }
    }

    public byte[] getPeerAccountAgeWitnessHash(Trade trade) {
        return findTradePeerWitness(trade)
                .map(AccountAgeWitness::getHash)
                .orElse(null);
    }

    byte[] getAccountInputDataWithSalt(PaymentAccountPayload paymentAccountPayload) {
        return Utilities.concatenateByteArrays(paymentAccountPayload.getAgeWitnessInputData(),
                paymentAccountPayload.getSalt());
    }

    @VisibleForTesting
    public AccountAgeWitness getNewWitness(PaymentAccountPayload paymentAccountPayload, PubKeyRing pubKeyRing) {
        byte[] accountInputDataWithSalt = getAccountInputDataWithSalt(paymentAccountPayload);
        byte[] hash = Hash.getSha256Ripemd160hash(Utilities.concatenateByteArrays(accountInputDataWithSalt,
                pubKeyRing.getSignaturePubKeyBytes()));
        return new AccountAgeWitness(hash, new Date().getTime());
    }

    public Optional<AccountAgeWitness> findWitness(PaymentAccountPayload paymentAccountPayload,
                                            PubKeyRing pubKeyRing) {
        if (paymentAccountPayload == null) {
            return Optional.empty();
        }

        byte[] accountInputDataWithSalt = getAccountInputDataWithSalt(paymentAccountPayload);
        byte[] hash = Hash.getSha256Ripemd160hash(Utilities.concatenateByteArrays(accountInputDataWithSalt,
                pubKeyRing.getSignaturePubKeyBytes()));

        return getWitnessByHash(hash);
    }

    public Optional<AccountAgeWitness> findWitness(Offer offer) {
        final Optional<String> accountAgeWitnessHash = offer.getAccountAgeWitnessHashAsHex();
        return accountAgeWitnessHash.isPresent() ?
                getWitnessByHashAsHex(accountAgeWitnessHash.get()) :
                Optional.empty();
    }

    private Optional<AccountAgeWitness> findTradePeerWitness(Trade trade) {
        if (trade instanceof ArbitratorTrade) return Optional.empty();  // TODO (woodser): arbitrator trade has two peers
        TradePeer tradePeer = trade.getTradePeer();
        return (tradePeer == null ||
                tradePeer.getPaymentAccountPayload() == null ||
                tradePeer.getPubKeyRing() == null) ?
                Optional.empty() :
                findWitness(tradePeer.getPaymentAccountPayload(), tradePeer.getPubKeyRing());
    }

    private Optional<AccountAgeWitness> getWitnessByHash(byte[] hash) {
        P2PDataStorage.ByteArray hashAsByteArray = new P2PDataStorage.ByteArray(hash);
        synchronized (this) {

            // First we look up in our fast lookup cache
            if (accountAgeWitnessCache.containsKey(hashAsByteArray)) {
                return Optional.of(accountAgeWitnessCache.get(hashAsByteArray));
            }

            if (accountAgeWitnessMap.containsKey(hashAsByteArray)) {
                AccountAgeWitness accountAgeWitness = accountAgeWitnessMap.get(hashAsByteArray);

                // We add it to our fast lookup cache
                accountAgeWitnessCache.put(hashAsByteArray, accountAgeWitness);

                return Optional.of(accountAgeWitness);
            }

            return Optional.empty();
        }
    }

    private Optional<AccountAgeWitness> getWitnessByHashAsHex(String hashAsHex) {
        return getWitnessByHash(Utilities.decodeFromHex(hashAsHex));
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Witness age
    ///////////////////////////////////////////////////////////////////////////////////////////

    public long getAccountAge(AccountAgeWitness accountAgeWitness, Date now) {
        log.debug("getAccountAge now={}, accountAgeWitness.getDate()={}", now.getTime(), accountAgeWitness.getDate());
        return now.getTime() - accountAgeWitness.getDate();
    }

    // Return -1 if no witness found
    public long getAccountAge(PaymentAccountPayload paymentAccountPayload, PubKeyRing pubKeyRing) {
        return findWitness(paymentAccountPayload, pubKeyRing)
                .map(accountAgeWitness -> getAccountAge(accountAgeWitness, new Date()))
                .orElse(-1L);
    }

    public long getAccountAge(Offer offer) {
        return findWitness(offer)
                .map(accountAgeWitness -> getAccountAge(accountAgeWitness, new Date()))
                .orElse(-1L);
    }

    public long getAccountAge(Trade trade) {
        return findTradePeerWitness(trade)
                .map(accountAgeWitness -> getAccountAge(accountAgeWitness, new Date()))
                .orElse(-1L);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Signed age
    ///////////////////////////////////////////////////////////////////////////////////////////

    // Return -1 if not signed
    public long getWitnessSignAge(AccountAgeWitness accountAgeWitness, Date now) {
        List<Long> dates = signedWitnessService.getVerifiedWitnessDateList(accountAgeWitness);
        if (dates.isEmpty()) {
            return -1L;
        } else {
            return now.getTime() - dates.get(0);
        }
    }

    // Return -1 if not signed
    public long getWitnessSignAge(Offer offer, Date now) {
        return findWitness(offer)
                .map(witness -> getWitnessSignAge(witness, now))
                .orElse(-1L);
    }

    public long getWitnessSignAge(Trade trade, Date now) {
        return findTradePeerWitness(trade)
                .map(witness -> getWitnessSignAge(witness, now))
                .orElse(-1L);
    }

    public AccountAge getPeersAccountAgeCategory(long peersAccountAge) {
        return getAccountAgeCategory(peersAccountAge);
    }

    private AccountAge getAccountAgeCategory(long accountAge) {
        if (accountAge < 0) {
            return AccountAge.UNVERIFIED;
        } else if (accountAge < TimeUnit.DAYS.toMillis(30)) {
            return AccountAge.LESS_ONE_MONTH;
        } else if (accountAge < TimeUnit.DAYS.toMillis(60)) {
            return AccountAge.ONE_TO_TWO_MONTHS;
        } else {
            return AccountAge.TWO_MONTHS_OR_MORE;
        }
    }

    // Get trade limit based on a time schedule
    // Buying of BTC with a payment method that has chargeback risk will use a low trade limit schedule
    // All selling and all other fiat payment methods use the normal trade limit schedule
    // Non fiat always has max limit
    // Account types that can get signed will use time since signing, other methods use time since account age creation
    // when measuring account age
    private BigInteger getTradeLimit(BigInteger maxTradeLimit,
                               String currencyCode,
                               AccountAgeWitness accountAgeWitness,
                               AccountAge accountAgeCategory,
                               OfferDirection direction,
                               PaymentMethod paymentMethod) {
        if (CurrencyUtil.isCryptoCurrency(currencyCode) ||
                !PaymentMethod.hasChargebackRisk(paymentMethod, currencyCode) ||
                direction == OfferDirection.SELL) {
            return maxTradeLimit;
        }

        BigInteger limit = OfferRestrictions.TOLERATED_SMALL_TRADE_AMOUNT;
        var factor = signedBuyFactor(accountAgeCategory);
        if (factor > 0) {
            limit = BigInteger.valueOf(MathUtils.roundDoubleToLong(maxTradeLimit.longValueExact() * factor));
        }

        if (accountAgeWitness != null) {
            log.debug("limit={}, factor={}, accountAgeWitnessHash={}",
            limit,
            factor,
            Utilities.bytesAsHexString(accountAgeWitness.getHash()));
        }
        return limit;
    }

    private double signedBuyFactor(AccountAge accountAgeCategory) {
        switch (accountAgeCategory) {
            case TWO_MONTHS_OR_MORE:
                return 1;
            case ONE_TO_TWO_MONTHS:
                return 0.5;
            case LESS_ONE_MONTH:
            case UNVERIFIED:
            default:
                return 0;
        }
    }

    private double normalFactor() {
        return 1;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Trade limit exceptions
    ///////////////////////////////////////////////////////////////////////////////////////////

    private boolean isImmature(AccountAgeWitness accountAgeWitness) {
        return accountAgeWitness.getDate() > SAFE_ACCOUNT_AGE_DATE;
    }

    public boolean myHasTradeLimitException(PaymentAccount myPaymentAccount) {
        return hasTradeLimitException(getMyWitness(myPaymentAccount.getPaymentAccountPayload()));
    }

    // There are no trade limits on accounts that
    // - are mature
    // - were signed by an arbitrator
    private boolean hasTradeLimitException(AccountAgeWitness accountAgeWitness) {
        return !isImmature(accountAgeWitness) || signedWitnessService.isSignedByArbitrator(accountAgeWitness);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // My witness
    ///////////////////////////////////////////////////////////////////////////////////////////

    public AccountAgeWitness getMyWitness(PaymentAccountPayload paymentAccountPayload) {
        final Optional<AccountAgeWitness> accountAgeWitnessOptional =
                findWitness(paymentAccountPayload, keyRing.getPubKeyRing());
        return accountAgeWitnessOptional.orElseGet(() -> getNewWitness(paymentAccountPayload, keyRing.getPubKeyRing()));
    }

    private byte[] getMyWitnessHash(PaymentAccountPayload paymentAccountPayload) {
        return getMyWitness(paymentAccountPayload).getHash();
    }

    public String getMyWitnessHashAsHex(PaymentAccountPayload paymentAccountPayload) {
        return Utilities.bytesAsHexString(getMyWitnessHash(paymentAccountPayload));
    }

    public long getMyAccountAge(PaymentAccountPayload paymentAccountPayload) {
        return getAccountAge(getMyWitness(paymentAccountPayload), new Date());
    }

    public long getMyTradeLimit(PaymentAccount paymentAccount, String currencyCode, OfferDirection direction, boolean buyerAsTakerWithoutDeposit) {
        if (paymentAccount == null)
            return 0;

        if (buyerAsTakerWithoutDeposit) {
            TradeLimits tradeLimits = new TradeLimits();
            return tradeLimits.getMaxTradeLimitBuyerAsTakerWithoutDeposit().longValueExact();
        }

        AccountAgeWitness accountAgeWitness = getMyWitness(paymentAccount.getPaymentAccountPayload());
        BigInteger maxTradeLimit = paymentAccount.getPaymentMethod().getMaxTradeLimit(currencyCode);
        if (hasTradeLimitException(accountAgeWitness)) {
            return maxTradeLimit.longValueExact();
        }
        final long accountSignAge = getWitnessSignAge(accountAgeWitness, new Date());
        AccountAge accountAgeCategory = getAccountAgeCategory(accountSignAge);

        return getTradeLimit(maxTradeLimit,
                currencyCode,
                accountAgeWitness,
                accountAgeCategory,
                direction,
                paymentAccount.getPaymentMethod()).longValueExact();
    }

    public long getUnsignedTradeLimit(PaymentMethod paymentMethod, String currencyCode, OfferDirection direction) {
        return getTradeLimit(paymentMethod.getMaxTradeLimit(currencyCode),
                currencyCode,
                null,
                AccountAge.UNVERIFIED,
                direction,
                paymentMethod).longValueExact();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Verification
    ///////////////////////////////////////////////////////////////////////////////////////////

    public boolean verifyAccountAgeWitness(Trade trade,
                                           PaymentAccountPayload peersPaymentAccountPayload,
                                           PubKeyRing peersPubKeyRing,
                                           byte[] nonce,
                                           byte[] signature,
                                           ErrorMessageHandler errorMessageHandler) {

        log.info("Verifying account age witness for {} {}, payment account payload hash={}, nonce={}, signature={}",
                trade.getClass().getSimpleName(),
                trade.getId(),
                Utilities.bytesAsHexString(peersPaymentAccountPayload.getHash()),
                Utilities.bytesAsHexString(nonce),
                Utilities.bytesAsHexString(signature));

        final Optional<AccountAgeWitness> accountAgeWitnessOptional =
                findWitness(peersPaymentAccountPayload, peersPubKeyRing);
        // If we don't find a stored witness data we create a new dummy object which makes is easier to reuse the
        // below validation methods. This peersWitness object is not used beside for validation. Some of the
        // validation calls are pointless in the case we create a new Witness ourselves but the verifyPeersTradeLimit
        // need still be called, so we leave also the rest for sake of simplicity.
        AccountAgeWitness peersWitness;
        if (accountAgeWitnessOptional.isPresent()) {
            peersWitness = accountAgeWitnessOptional.get();
        } else {
            log.warn("We did not find the peers witness data. That is expected with peers using an older version.");
            peersWitness = getNewWitness(peersPaymentAccountPayload, peersPubKeyRing);
        }

        // Check if date in witness is not older than the release date of that feature (was added in v0.6)
        if (!isDateAfterReleaseDate(peersWitness.getDate(), RELEASE, errorMessageHandler))
            return false;

        final byte[] peersAccountInputDataWithSalt = Utilities.concatenateByteArrays(
                peersPaymentAccountPayload.getAgeWitnessInputData(), peersPaymentAccountPayload.getSalt());
        byte[] hash = Hash.getSha256Ripemd160hash(Utilities.concatenateByteArrays(peersAccountInputDataWithSalt,
                peersPubKeyRing.getSignaturePubKeyBytes()));

        // Check if the hash in the witness data matches the hash derived from the data provided by the peer
        final byte[] peersWitnessHash = peersWitness.getHash();
        if (!verifyWitnessHash(peersWitnessHash, hash, errorMessageHandler))
            return false;

        // Check if the peers trade limit is not less than the trade amount
        if (!verifyPeersTradeLimit(trade.getOffer(), trade.getAmount(), peersWitness, new Date(), errorMessageHandler)) {
            log.error("verifyPeersTradeLimit failed: peersPaymentAccountPayload {}", peersPaymentAccountPayload);
            return false;
        }
        // Check if the signature is correct
        return verifySignature(peersPubKeyRing.getSignaturePubKey(), nonce, signature, errorMessageHandler);
    }

    public boolean verifyPeersTradeAmount(Offer offer,
                                          BigInteger tradeAmount,
                                          ErrorMessageHandler errorMessageHandler) {
        checkNotNull(offer);

        // In case we don't find the witness we check if the trade amount is above the
        // TOLERATED_SMALL_TRADE_AMOUNT (0.01 BTC) and only in that case return false.
        return findWitness(offer)
                .map(witness -> verifyPeersTradeLimit(offer, tradeAmount, witness, new Date(), errorMessageHandler))
                .orElse(isToleratedSmalleAmount(tradeAmount));
    }

    private boolean isToleratedSmalleAmount(BigInteger tradeAmount) {
        return tradeAmount.longValueExact() <= OfferRestrictions.TOLERATED_SMALL_TRADE_AMOUNT.longValueExact();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Package scope verification subroutines
    ///////////////////////////////////////////////////////////////////////////////////////////

    boolean isDateAfterReleaseDate(long witnessDateAsLong,
                                   Date ageWitnessReleaseDate,
                                   ErrorMessageHandler errorMessageHandler) {
        // Release date minus 1 day as tolerance for not synced clocks
        Date releaseDateWithTolerance = new Date(ageWitnessReleaseDate.getTime() - TimeUnit.DAYS.toMillis(1));
        final Date witnessDate = new Date(witnessDateAsLong);
        final boolean result = witnessDate.after(releaseDateWithTolerance);
        if (!result) {
            final String msg = "Witness date is set earlier than release date of ageWitness feature. " +
                    "ageWitnessReleaseDate=" + ageWitnessReleaseDate + ", witnessDate=" + witnessDate;
            log.warn(msg);
            errorMessageHandler.handleErrorMessage(msg);
        }
        return result;
    }

    public boolean verifyPeersCurrentDate(Date peersCurrentDate) {
        boolean result = Math.abs(peersCurrentDate.getTime() - new Date().getTime()) <= TimeUnit.DAYS.toMillis(1);
        if (!result) {
            String msg = "Peers current date is further than 1 day off to our current date. " +
                    "PeersCurrentDate=" + peersCurrentDate + "; myCurrentDate=" + new Date();
            throw new RuntimeException(msg);
        }
        return result;
    }

    private boolean verifyWitnessHash(byte[] witnessHash,
                                      byte[] hash,
                                      ErrorMessageHandler errorMessageHandler) {
        final boolean result = Arrays.equals(witnessHash, hash);
        if (!result) {
            final String msg = "witnessHash is not matching peers hash. " +
                    "witnessHash=" + Utilities.bytesAsHexString(witnessHash) + ", hash=" + Utilities.bytesAsHexString(hash);
            log.warn(msg);
            errorMessageHandler.handleErrorMessage(msg);
        }
        return result;
    }

    private boolean verifyPeersTradeLimit(Offer offer,
                                          BigInteger tradeAmount,
                                          AccountAgeWitness peersWitness,
                                          Date peersCurrentDate,
                                          ErrorMessageHandler errorMessageHandler) {
        checkNotNull(offer);
        final String currencyCode = offer.getCounterCurrencyCode();
        final BigInteger defaultMaxTradeLimit = offer.getPaymentMethod().getMaxTradeLimit(currencyCode);
        BigInteger peersCurrentTradeLimit = defaultMaxTradeLimit;
        if (!hasTradeLimitException(peersWitness)) {
            final long accountSignAge = getWitnessSignAge(peersWitness, peersCurrentDate);
            AccountAge accountAgeCategory = getPeersAccountAgeCategory(accountSignAge);
            OfferDirection direction = offer.isMyOffer(keyRing) ?
                    offer.getMirroredDirection() : offer.getDirection();
            peersCurrentTradeLimit = getTradeLimit(defaultMaxTradeLimit, currencyCode, peersWitness,
                    accountAgeCategory, direction, offer.getPaymentMethod());
        }
        // Makers current trade limit cannot be smaller than that in the offer
        boolean result = tradeAmount.longValueExact() <= peersCurrentTradeLimit.longValueExact();
        if (!result) {
            String msg = "The peers trade limit is less than the traded amount.\n" +
                    "tradeAmount=" + tradeAmount +
                    "\nPeers trade limit=" + peersCurrentTradeLimit +
                    "\nOffer ID=" + offer.getShortId() +
                    "\nPaymentMethod=" + offer.getPaymentMethod().getId() +
                    "\nCurrencyCode=" + offer.getCounterCurrencyCode();
            log.warn(msg);
            errorMessageHandler.handleErrorMessage(msg);
        }
        return result;
    }

    boolean verifySignature(PublicKey peersPublicKey,
                            byte[] nonce,
                            byte[] signature,
                            ErrorMessageHandler errorMessageHandler) {
        boolean result;
        try {
            result = Sig.verify(peersPublicKey, nonce, signature);
        } catch (CryptoException e) {
            log.warn(e.toString());
            result = false;
        }
        if (!result) {
            final String msg = "Signature of nonce is not correct. " +
                    "peersPublicKey=" + peersPublicKey + ", nonce(hex)=" + Utilities.bytesAsHexString(nonce) +
                    ", signature=" + Utilities.bytesAsHexString(signature);
            log.warn(msg);
            errorMessageHandler.handleErrorMessage(msg);
        }
        return result;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Witness signing
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void arbitratorSignAccountAgeWitness(BigInteger tradeAmount,
                                                AccountAgeWitness accountAgeWitness,
                                                ECKey key,
                                                PublicKey peersPubKey) {
        signedWitnessService.signAndPublishAccountAgeWitness(tradeAmount, accountAgeWitness, key, peersPubKey);
    }

    public String arbitratorSignOrphanWitness(AccountAgeWitness accountAgeWitness,
                                              ECKey ecKey,
                                              long time) {
        // TODO Is not found signedWitness considered an error case?
        //  Previous code version was throwing an exception in case no signedWitness was found...

        // signAndPublishAccountAgeWitness returns an empty string in success case and error otherwise
        return signedWitnessService.getSignedWitnessSet(accountAgeWitness).stream()
                .findAny()
                .map(SignedWitness::getWitnessOwnerPubKey)
                .map(witnessOwnerPubKey ->
                        signedWitnessService.signAndPublishAccountAgeWitness(accountAgeWitness, ecKey,
                                witnessOwnerPubKey, time)
                )
                .orElse("No signedWitness found");
    }

    public String arbitratorSignOrphanPubKey(ECKey key,
                                             byte[] peersPubKey,
                                             long childSignTime) {
        return signedWitnessService.signTraderPubKey(key, peersPubKey, childSignTime);
    }

    public void arbitratorSignAccountAgeWitness(AccountAgeWitness accountAgeWitness,
                                                ECKey key,
                                                byte[] tradersPubKey,
                                                long time) {
        signedWitnessService.signAndPublishAccountAgeWitness(accountAgeWitness, key, tradersPubKey, time);
    }

    public Optional<SignedWitness> traderSignAndPublishPeersAccountAgeWitness(Trade trade) {
        checkNotNull(trade.getTradePeer().getPubKeyRing(), "Peer must have a keyring");
        PublicKey peersPubKey = trade.getTradePeer().getPubKeyRing().getSignaturePubKey();
        checkNotNull(peersPubKey, "Peers pub key must not be null");
        AccountAgeWitness peersWitness = findTradePeerWitness(trade).orElse(null);
        checkNotNull(peersWitness, "Not able to find peers witness, unable to sign for trade " + trade.toString());
        BigInteger tradeAmount = trade.getAmount();
        checkNotNull(tradeAmount, "Trade amount must not be null");

        try {
            return signedWitnessService.signAndPublishAccountAgeWitness(tradeAmount, peersWitness, peersPubKey);
        } catch (CryptoException e) {
            log.warn("Trader failed to sign witness, exception {}", e.toString());
        }
        return Optional.empty();
    }

    public boolean publishOwnSignedWitness(SignedWitness signedWitness) {
        return signedWitnessService.publishOwnSignedWitness(signedWitness);
    }

    // Arbitrator signing
    public List<TraderDataItem> getTraderPaymentAccounts(long safeDate,
                                                         PaymentMethod paymentMethod,
                                                         List<Dispute> disputes) {
        return disputes.stream()
                .filter(dispute -> dispute.getContract().getPaymentMethodId().equals(paymentMethod.getId()))
                .filter(this::isNotFiltered)
                .filter(this::hasChargebackRisk)
                .filter(this::isBuyerWinner)
                .flatMap(this::getTraderData)
                .filter(Objects::nonNull)
                .filter(traderDataItem ->
                        !signedWitnessService.isSignedAccountAgeWitness(traderDataItem.getAccountAgeWitness()))
                .filter(traderDataItem -> traderDataItem.getAccountAgeWitness().getDate() < safeDate)
                .distinct()
                .collect(Collectors.toList());
    }

    private boolean isNotFiltered(Dispute dispute) {
        boolean isFiltered = filterManager.isNodeAddressBanned(dispute.getContract().getBuyerNodeAddress()) ||
                filterManager.isNodeAddressBanned(dispute.getContract().getSellerNodeAddress()) ||
                filterManager.isCurrencyBanned(dispute.getContract().getOfferPayload().getCurrencyCode()) ||
                filterManager.isPaymentMethodBanned(PaymentMethod.getPaymentMethodOrNA(dispute.getContract().getPaymentMethodId())) ||
                filterManager.arePeersPaymentAccountDataBanned(dispute.getBuyerPaymentAccountPayload()) ||
                filterManager.arePeersPaymentAccountDataBanned(dispute.getSellerPaymentAccountPayload()) ||
                filterManager.isWitnessSignerPubKeyBanned(Utils.HEX.encode(dispute.getContract().getBuyerPubKeyRing().getSignaturePubKeyBytes())) ||
                filterManager.isWitnessSignerPubKeyBanned(Utils.HEX.encode(dispute.getContract().getSellerPubKeyRing().getSignaturePubKeyBytes()));
        return !isFiltered;
    }

    @VisibleForTesting
    public boolean hasChargebackRisk(Dispute dispute) {
        return chargeBackRisk.hasChargebackRisk(dispute.getContract().getPaymentMethodId(),
                dispute.getContract().getOfferPayload().getCurrencyCode());
    }

    private boolean isBuyerWinner(Dispute dispute) {
        if (!dispute.isClosed() || dispute.getDisputeResultProperty() == null)
            return false;
        return dispute.getDisputeResultProperty().get().getWinner() == DisputeResult.Winner.BUYER;
    }

    private Stream<TraderDataItem> getTraderData(Dispute dispute) {
        BigInteger tradeAmount = dispute.getContract().getTradeAmount();

        PubKeyRing buyerPubKeyRing = dispute.getContract().getBuyerPubKeyRing();
        PubKeyRing sellerPubKeyRing = dispute.getContract().getSellerPubKeyRing();

        PaymentAccountPayload buyerPaymentAccountPaload = dispute.getBuyerPaymentAccountPayload();
        PaymentAccountPayload sellerPaymentAccountPaload = dispute.getSellerPaymentAccountPayload();

        TraderDataItem buyerData = findWitness(buyerPaymentAccountPaload, buyerPubKeyRing)
                .map(witness -> new TraderDataItem(
                        buyerPaymentAccountPaload,
                        witness,
                        tradeAmount,
                        buyerPubKeyRing.getSignaturePubKey()))
                .orElse(null);
        TraderDataItem sellerData = findWitness(sellerPaymentAccountPaload, sellerPubKeyRing)
                .map(witness -> new TraderDataItem(
                        sellerPaymentAccountPaload,
                        witness,
                        tradeAmount,
                        sellerPubKeyRing.getSignaturePubKey()))
                .orElse(null);
        return Stream.of(buyerData, sellerData);
    }

    public boolean hasSignedWitness(Offer offer) {
        return findWitness(offer)
                .map(signedWitnessService::isSignedAccountAgeWitness)
                .orElse(false);
    }

    public boolean peerHasSignedWitness(Trade trade) {
        return findTradePeerWitness(trade)
                .map(signedWitnessService::isSignedAccountAgeWitness)
                .orElse(false);
    }

    public boolean accountIsSigner(AccountAgeWitness accountAgeWitness) {
        return signedWitnessService.isSignerAccountAgeWitness(accountAgeWitness);
    }

    public boolean tradeAmountIsSufficient(BigInteger tradeAmount) {
        return signedWitnessService.isSufficientTradeAmountForSigning(tradeAmount);
    }

    public SignState getSignState(Offer offer) {
        return findWitness(offer)
                .map(this::getSignState)
                .orElse(SignState.UNSIGNED);
    }

    public SignState getSignState(Trade trade) {
        if (trade instanceof ArbitratorTrade) return SignState.UNSIGNED;
        return findTradePeerWitness(trade)
                .map(this::getSignState)
                .orElse(SignState.UNSIGNED);
    }

    public SignState getSignState(AccountAgeWitness accountAgeWitness) {
        // Add hash to sign state info when running in debug mode
        String hash = log.isDebugEnabled() ? Utilities.bytesAsHexString(accountAgeWitness.getHash()) + "\n" +
                signedWitnessService.ownerPubKeyAsString(accountAgeWitness) : "";
        if (signedWitnessService.isFilteredWitness(accountAgeWitness)) {
            return SignState.BANNED.addHash(hash);
        }
        if (signedWitnessService.isSignedByArbitrator(accountAgeWitness)) {
            return SignState.ARBITRATOR.addHash(hash);
        } else {
            final long accountSignAge = getWitnessSignAge(accountAgeWitness, new Date());
            switch (getAccountAgeCategory(accountSignAge)) {
                case TWO_MONTHS_OR_MORE:
                case ONE_TO_TWO_MONTHS:
                    return SignState.PEER_SIGNER.addHash(hash);
                case LESS_ONE_MONTH:
                    return SignState.PEER_INITIAL.addHash(hash)
                            .setDaysUntilLimitLifted(30 - TimeUnit.MILLISECONDS.toDays(accountSignAge));
                case UNVERIFIED:
                default:
                    return SignState.UNSIGNED.addHash(hash);
            }
        }
    }

    public Set<AccountAgeWitness> getOrphanSignedWitnesses() {
        return signedWitnessService.getRootSignedWitnessSet(false).stream()
                .map(signedWitness -> getWitnessByHash(signedWitness.getAccountAgeWitnessHash()).orElse(null))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    public void signAndPublishSameNameAccounts() {
        // Collect accounts that have ownerId to sign unsigned accounts with the same ownderId
        var signerAccounts = Objects.requireNonNull(user.getPaymentAccounts()).stream()
                .filter(account -> account.getOwnerId() != null &&
                        accountIsSigner(getMyWitness(account.getPaymentAccountPayload())))
                .collect(Collectors.toSet());
        var unsignedAccounts = user.getPaymentAccounts().stream()
                .filter(account -> account.getOwnerId() != null &&
                        !signedWitnessService.isSignedAccountAgeWitness(
                                getMyWitness(account.getPaymentAccountPayload())))
                .collect(Collectors.toSet());

        signerAccounts.forEach(signer -> unsignedAccounts.forEach(unsigned -> {
            if (signer.getOwnerId().equals(unsigned.getOwnerId())) {
                try {
                    signedWitnessService.selfSignAndPublishAccountAgeWitness(
                            getMyWitness(unsigned.getPaymentAccountPayload()));
                } catch (CryptoException e) {
                    log.warn("Self signing failed, exception {}", e.toString());
                }
            }
        }));
    }

    public Set<SignedWitness> getUnsignedSignerPubKeys() {
        return signedWitnessService.getUnsignedSignerPubKeys();
    }

    public boolean isSignWitnessTrade(Trade trade) {
        checkNotNull(trade, "trade must not be null");
        checkNotNull(trade.getOffer(), "offer must not be null");
        PaymentAccountPayload sellerPaymentAccountPayload = trade.getSeller().getPaymentAccountPayload();
        AccountAgeWitness myWitness = getMyWitness(sellerPaymentAccountPayload);

        getAccountAgeWitnessUtils().witnessDebugLog(trade, myWitness);

        return accountIsSigner(myWitness) &&
                !peerHasSignedWitness(trade) &&
                tradeAmountIsSufficient(trade.getAmount());
    }

    public String getSignInfoFromAccount(PaymentAccount paymentAccount) {
        var pubKey = keyRing.getSignatureKeyPair().getPublic();
        var witness = getMyWitness(paymentAccount.getPaymentAccountPayload());
        return Utilities.bytesAsHexString(witness.getHash()) + "," + Utilities.bytesAsHexString(pubKey.getEncoded());
    }

    public Tuple2<AccountAgeWitness, byte[]> getSignInfoFromString(String signInfo) {
        var parts = signInfo.split(",");
        if (parts.length != 2) {
            return null;
        }
        byte[] pubKeyHash;
        Optional<AccountAgeWitness> accountAgeWitness;
        try {
            var accountAgeWitnessHash = Utilities.decodeFromHex(parts[0]);
            pubKeyHash = Utilities.decodeFromHex(parts[1]);
            accountAgeWitness = getWitnessByHash(accountAgeWitnessHash);
            return accountAgeWitness
                    .map(ageWitness -> new Tuple2<>(ageWitness, pubKeyHash))
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }
}
