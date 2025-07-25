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

package haveno.core.offer;

import haveno.common.ThreadUtils;
import haveno.common.crypto.KeyRing;
import haveno.common.crypto.PubKeyRing;
import haveno.common.handlers.ErrorMessageHandler;
import haveno.common.handlers.ResultHandler;
import haveno.common.proto.network.NetworkPayload;
import haveno.common.proto.persistable.PersistablePayload;
import haveno.common.util.JsonExclude;
import haveno.common.util.MathUtils;
import haveno.common.util.Utilities;
import haveno.core.exceptions.TradePriceOutOfToleranceException;
import haveno.core.locale.CurrencyUtil;
import haveno.core.monetary.CryptoMoney;
import haveno.core.monetary.Price;
import haveno.core.monetary.TraditionalMoney;
import haveno.core.monetary.Volume;
import haveno.core.offer.availability.OfferAvailabilityModel;
import haveno.core.offer.availability.OfferAvailabilityProtocol;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.provider.price.MarketPrice;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.trade.HavenoUtils;
import haveno.core.util.PriceUtil;
import haveno.core.util.VolumeUtil;
import haveno.core.util.coin.CoinUtil;
import haveno.network.p2p.NodeAddress;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import java.math.BigInteger;
import java.security.PublicKey;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

@Slf4j
public class Offer implements NetworkPayload, PersistablePayload {

    // We allow max. 1 % difference between own offerPayload price calculation and takers calculation.
    // Market price might be different at maker's and takers side so we need a bit of tolerance.
    // The tolerance will get smaller once we have multiple price feeds avoiding fast price fluctuations
    // from one provider.
    private final static double PRICE_TOLERANCE = 0.01;

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Enums
    ///////////////////////////////////////////////////////////////////////////////////////////

    public enum State {
        UNKNOWN,
        OFFER_FEE_RESERVED,
        AVAILABLE,
        NOT_AVAILABLE,
        REMOVED,
        MAKER_OFFLINE,
        INVALID
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Instance fields
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Getter
    private final OfferPayload offerPayload;
    @JsonExclude
    @Getter
    final transient private ObjectProperty<Offer.State> stateProperty = new SimpleObjectProperty<>(Offer.State.UNKNOWN);
    @JsonExclude
    @Nullable
    transient private OfferAvailabilityProtocol availabilityProtocol;
    @JsonExclude
    @Getter
    final transient private StringProperty errorMessageProperty = new SimpleStringProperty();
    @JsonExclude
    @Nullable
    @Setter
    transient private PriceFeedService priceFeedService;

    // Used only as cache
    @Nullable
    @JsonExclude
    transient private String currencyCode;

    @JsonExclude
    @Getter
    @Setter
    transient private boolean isReservedFundsSpent;

    @JsonExclude
    @Getter
    @Setter
    @Nullable
    transient private String challenge;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public Offer(OfferPayload offerPayload) {
        this.offerPayload = offerPayload;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public protobuf.Offer toProtoMessage() {
        return protobuf.Offer.newBuilder().setOfferPayload(offerPayload.toProtoMessage().getOfferPayload()).build();
    }

    public static Offer fromProto(protobuf.Offer proto) {
        return new Offer(OfferPayload.fromProto(proto.getOfferPayload()));
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Availability
    ///////////////////////////////////////////////////////////////////////////////////////////

    public synchronized void checkOfferAvailability(OfferAvailabilityModel model, ResultHandler resultHandler,
                                       ErrorMessageHandler errorMessageHandler) {
        availabilityProtocol = new OfferAvailabilityProtocol(model,
                () -> {
                    cancelAvailabilityRequest();
                    new Thread(() -> resultHandler.handleResult()).start();
                },
                (errorMessage) -> {
                    if (availabilityProtocol != null)
                        availabilityProtocol.cancel();
                    log.error(errorMessage);
                    errorMessageHandler.handleErrorMessage(errorMessage);
                });
        ThreadUtils.submitToPool((() -> {
            availabilityProtocol.sendOfferAvailabilityRequest();
        }));
    }

    public void cancelAvailabilityRequest() {
        if (availabilityProtocol != null)
            availabilityProtocol.cancel();
    }

    @Nullable
    public Price getPrice() {
        String counterCurrencyCode = getCounterCurrencyCode();
        if (!offerPayload.isUseMarketBasedPrice()) {
            return Price.valueOf(counterCurrencyCode, isInverted() ? PriceUtil.invertLongPrice(offerPayload.getPrice(), counterCurrencyCode) : offerPayload.getPrice());
        }

        checkNotNull(priceFeedService, "priceFeed must not be null");
        MarketPrice marketPrice = priceFeedService.getMarketPrice(counterCurrencyCode);
        if (marketPrice != null && marketPrice.isRecentExternalPriceAvailable()) {
            double factor;
            double marketPriceMargin = offerPayload.getMarketPriceMarginPct();
            factor = getDirection() == OfferDirection.BUY ?
                    1 - marketPriceMargin : 1 + marketPriceMargin;
            double marketPriceAsDouble = marketPrice.getPrice();
            double targetPriceAsDouble = marketPriceAsDouble * factor;
            try {
                int precision = CurrencyUtil.isTraditionalCurrency(counterCurrencyCode) ?
                        TraditionalMoney.SMALLEST_UNIT_EXPONENT :
                        CryptoMoney.SMALLEST_UNIT_EXPONENT;
                double scaled = MathUtils.scaleUpByPowerOf10(targetPriceAsDouble, precision);
                final long roundedToLong = MathUtils.roundDoubleToLong(scaled);
                return Price.valueOf(counterCurrencyCode, roundedToLong);
            } catch (Exception e) {
                log.error("Exception at getPrice / parseToFiat: " + e + "\n" +
                        "That case should never happen.");
                return null;
            }
        } else {
            log.trace("We don't have a market price. " +
                    "That case could only happen if you don't have a price feed.");
            return null;
        }
    }

    public long getFixedPrice() {
        return offerPayload.getPrice();
    }

    public void verifyTradePrice(long price) throws TradePriceOutOfToleranceException,
            MarketPriceNotAvailableException, IllegalArgumentException {
        if (!isUseMarketBasedPrice()) {
            checkArgument(price == getFixedPrice(),
                    "Takers price does not match offer price. " +
                            "Takers price=" + price + "; offer price=" + getFixedPrice());
            return;
        }

        Price tradePrice = Price.valueOf(getCounterCurrencyCode(), price);
        Price offerPrice = getPrice();
        if (offerPrice == null)
            throw new MarketPriceNotAvailableException("Market price required for calculating trade price is not available.");

        checkArgument(price > 0, "takersTradePrice must be positive");

        double relation = (double) price / (double) offerPrice.getValue();
        // We allow max. 2 % difference between own offerPayload price calculation and takers calculation.
        // Market price might be different at maker's and takers side so we need a bit of tolerance.
        // The tolerance will get smaller once we have multiple price feeds avoiding fast price fluctuations
        // from one provider.

        double deviation = Math.abs(1 - relation);
        log.info("Price at take-offer time: id={}, currency={}, takersPrice={}, makersPrice={}, deviation={}",
                getShortId(), getCounterCurrencyCode(), price, offerPrice.getValue(),
                deviation * 100 + "%");
        if (deviation > PRICE_TOLERANCE) {
            String msg = "Taker's trade price is too far away from our calculated price based on the market price.\n" +
                    "takersPrice=" + tradePrice.getValue() + "\n" +
                    "makersPrice=" + offerPrice.getValue();
            log.warn(msg);
            throw new TradePriceOutOfToleranceException(msg);
        }
    }

    @Nullable
    public Volume getVolumeByAmount(BigInteger amount, BigInteger minAmount, BigInteger maxAmount) {
        Price price = getPrice();
        if (price == null || amount == null) {
            return null;
        }
        BigInteger adjustedAmount = CoinUtil.getRoundedAmount(amount, price, minAmount, maxAmount, getCounterCurrencyCode(), getPaymentMethodId());
        Volume volumeByAmount = price.getVolumeByAmount(adjustedAmount);
        volumeByAmount = VolumeUtil.getAdjustedVolume(volumeByAmount, getPaymentMethod().getId());

        return volumeByAmount;
    }

    public void resetState() {
        setState(Offer.State.UNKNOWN);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Setter
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void setState(Offer.State state) {
        stateProperty.set(state);
    }

    public ObjectProperty<Offer.State> stateProperty() {
        return stateProperty;
    }

    public void setErrorMessage(String errorMessage) {
        errorMessageProperty.set(errorMessage);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Getter
    ///////////////////////////////////////////////////////////////////////////////////////////

    // amount needed for the maker to reserve the offer
    public BigInteger getAmountNeeded() {
        BigInteger amountNeeded = getDirection() == OfferDirection.BUY ? getMaxBuyerSecurityDeposit() : getMaxSellerSecurityDeposit();
        if (getDirection() == OfferDirection.SELL) amountNeeded = amountNeeded.add(getAmount());
        amountNeeded = amountNeeded.add(getMaxMakerFee());
        return amountNeeded;
    }

    // amount reserved for offer
    public BigInteger getReservedAmount() {
        if (offerPayload.getReserveTxKeyImages() == null) return null;
        return HavenoUtils.xmrWalletService.getOutputsAmount(offerPayload.getReserveTxKeyImages());
    }

    public BigInteger getMaxMakerFee() {
        return offerPayload.getMaxMakerFee();
    }

    public BigInteger getMaxBuyerSecurityDeposit() {
        return offerPayload.getMaxBuyerSecurityDeposit();
    }

    public BigInteger getMaxSellerSecurityDeposit() {
        return offerPayload.getMaxSellerSecurityDeposit();
    }

    public double getMakerFeePct() {
        return offerPayload.getMakerFeePct();
    }

    public double getTakerFeePct() {
        return offerPayload.getTakerFeePct();
    }

    public double getPenaltyFeePct() {
        return offerPayload.getPenaltyFeePct();
    }

    public BigInteger getMakerFee(BigInteger tradeAmount) {
        return HavenoUtils.multiply(tradeAmount, getMakerFeePct());
    }

    public BigInteger getTakerFee(BigInteger tradeAmount) {
        return HavenoUtils.multiply(tradeAmount, getTakerFeePct());
    }

    public double getBuyerSecurityDepositPct() {
        return offerPayload.getBuyerSecurityDepositPct();
    }

    public double getSellerSecurityDepositPct() {
        return offerPayload.getSellerSecurityDepositPct();
    }

    public boolean isPrivateOffer() {
        return offerPayload.isPrivateOffer();
    }

    public String getChallengeHash() {
        return offerPayload.getChallengeHash();
    }

    public boolean hasBuyerAsTakerWithoutDeposit() {
        return getDirection() == OfferDirection.SELL && getBuyerSecurityDepositPct() == 0;
    }

    public BigInteger getMaxTradeLimit() {
        return BigInteger.valueOf(offerPayload.getMaxTradeLimit());
    }

    public BigInteger getAmount() {
        return BigInteger.valueOf(offerPayload.getAmount());
    }

    public BigInteger getMinAmount() {
        return BigInteger.valueOf(offerPayload.getMinAmount());
    }

    public boolean isRange() {
        return offerPayload.getAmount() != offerPayload.getMinAmount();
    }

    public Date getDate() {
        return new Date(offerPayload.getDate());
    }

    public PaymentMethod getPaymentMethod() {
        return PaymentMethod.getPaymentMethodOrNA(offerPayload.getPaymentMethodId());
    }

    // utils
    public String getShortId() {
        return Utilities.getShortId(offerPayload.getId());
    }

    @Nullable
    public Volume getVolume() {
        return getVolumeByAmount(getAmount(), getMinAmount(), getAmount());
    }

    @Nullable
    public Volume getMinVolume() {
        return getVolumeByAmount(getMinAmount(), getMinAmount(), getAmount());
    }

    public boolean isBuyOffer() {
        return getDirection() == OfferDirection.BUY;
    }

    public OfferDirection getMirroredDirection() {
        return getDirection() == OfferDirection.BUY ? OfferDirection.SELL : OfferDirection.BUY;
    }

    public boolean isMyOffer(KeyRing keyRing) {
        return getPubKeyRing().equals(keyRing.getPubKeyRing());
    }

    public Optional<String> getAccountAgeWitnessHashAsHex() {
        Map<String, String> extraDataMap = getExtraDataMap();
        if (extraDataMap != null && extraDataMap.containsKey(OfferPayload.ACCOUNT_AGE_WITNESS_HASH))
            return Optional.of(extraDataMap.get(OfferPayload.ACCOUNT_AGE_WITNESS_HASH));
        else
            return Optional.empty();
    }

    public String getF2FCity() {
        if (getExtraDataMap() != null && getExtraDataMap().containsKey(OfferPayload.F2F_CITY))
            return getExtraDataMap().get(OfferPayload.F2F_CITY);
        else
            return "";
    }

    public String getCombinedExtraInfo() {
        StringBuilder sb = new StringBuilder();
        if (getOfferExtraInfo() != null && !getOfferExtraInfo().isEmpty()) {
            sb.append(getOfferExtraInfo());
        }
        if (getPaymentAccountExtraInfo() != null && !getPaymentAccountExtraInfo().isEmpty()) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(getPaymentAccountExtraInfo());
        }
        return sb.toString();
    }

    public String getOfferExtraInfo() {
        return offerPayload.getExtraInfo();
    }

    public String getPaymentAccountExtraInfo() {
        if (getExtraDataMap() != null && getExtraDataMap().containsKey(OfferPayload.F2F_EXTRA_INFO))
            return getExtraDataMap().get(OfferPayload.F2F_EXTRA_INFO);
        else if (getExtraDataMap() != null && getExtraDataMap().containsKey(OfferPayload.PAY_BY_MAIL_EXTRA_INFO))
            return getExtraDataMap().get(OfferPayload.PAY_BY_MAIL_EXTRA_INFO);
        else if (getExtraDataMap() != null && getExtraDataMap().containsKey(OfferPayload.AUSTRALIA_PAYID_EXTRA_INFO))
            return getExtraDataMap().get(OfferPayload.AUSTRALIA_PAYID_EXTRA_INFO);
        else if (getExtraDataMap() != null && getExtraDataMap().containsKey(OfferPayload.PAYPAL_EXTRA_INFO))
            return getExtraDataMap().get(OfferPayload.PAYPAL_EXTRA_INFO);
        else if (getExtraDataMap() != null && getExtraDataMap().containsKey(OfferPayload.CASHAPP_EXTRA_INFO))
            return getExtraDataMap().get(OfferPayload.CASHAPP_EXTRA_INFO);
        else if (getExtraDataMap() != null && getExtraDataMap().containsKey(OfferPayload.CASH_AT_ATM_EXTRA_INFO))
            return getExtraDataMap().get(OfferPayload.CASH_AT_ATM_EXTRA_INFO);
        else
            return "";
    }

    public String getPaymentMethodNameWithCountryCode() {
        String method = this.getPaymentMethod().getShortName();
        String methodCountryCode = this.getCountryCode();
        if (methodCountryCode != null)
            method = method + " (" + methodCountryCode + ")";
        return method;
    }

    // domain properties
    public Offer.State getState() {
        return stateProperty.get();
    }

    public ReadOnlyStringProperty errorMessageProperty() {
        return errorMessageProperty;
    }

    public String getErrorMessage() {
        return errorMessageProperty.get();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Delegate Getter (boilerplate code generated via IntelliJ generate delegate feature)
    ///////////////////////////////////////////////////////////////////////////////////////////

    public OfferDirection getDirection() {
        return offerPayload.getDirection();
    }

    public String getId() {
        return offerPayload.getId();
    }

    @Nullable
    public List<String> getAcceptedBankIds() {
        return offerPayload.getAcceptedBankIds();
    }

    @Nullable
    public String getBankId() {
        return offerPayload.getBankId();
    }

    @Nullable
    public List<String> getAcceptedCountryCodes() {
        return offerPayload.getAcceptedCountryCodes();
    }

    @Nullable
    public String getCountryCode() {
        return offerPayload.getCountryCode();
    }

    public String getBaseCurrencyCode() {
        return isInverted() ? offerPayload.getCounterCurrencyCode() : offerPayload.getBaseCurrencyCode(); // legacy offers inverted crypto
    }

    public String getCounterCurrencyCode() {
        if (currencyCode != null) return currencyCode;
        currencyCode = isInverted() ? offerPayload.getBaseCurrencyCode() : offerPayload.getCounterCurrencyCode(); // legacy offers inverted crypto
        return currencyCode;
    }

    public boolean isInverted() {
        return !offerPayload.getBaseCurrencyCode().equals("XMR");
    }

    public String getPaymentMethodId() {
        return offerPayload.getPaymentMethodId();
    }

    public long getProtocolVersion() {
        return offerPayload.getProtocolVersion();
    }

    public boolean isUseMarketBasedPrice() {
        return offerPayload.isUseMarketBasedPrice();
    }

    public double getMarketPriceMarginPct() {
        return offerPayload.getMarketPriceMarginPct();
    }

    public NodeAddress getMakerNodeAddress() {
        return offerPayload.getOwnerNodeAddress();
    }

    public PubKeyRing getPubKeyRing() {
        return offerPayload.getPubKeyRing();
    }

    public String getMakerPaymentAccountId() {
        return offerPayload.getMakerPaymentAccountId();
    }

    public String getVersionNr() {
        return offerPayload.getVersionNr();
    }

    public long getMaxTradePeriod() {
        return offerPayload.getMaxTradePeriod();
    }

    public NodeAddress getOwnerNodeAddress() {
        return offerPayload.getOwnerNodeAddress();
    }

    // Yet unused
    public PublicKey getOwnerPubKey() {
        return offerPayload.getOwnerPubKey();
    }

    @Nullable
    public Map<String, String> getExtraDataMap() {
        return offerPayload.getExtraDataMap();
    }

    public boolean isUseAutoClose() {
        return offerPayload.isUseAutoClose();
    }

    public boolean isUseReOpenAfterAutoClose() {
        return offerPayload.isUseReOpenAfterAutoClose();
    }

    public boolean isTraditionalOffer() {
        return CurrencyUtil.isTraditionalCurrency(currencyCode);
    }

    public boolean isFiatOffer() {
        return CurrencyUtil.isFiatCurrency(currencyCode);
    }

    public byte[] getOfferPayloadHash() {
        return offerPayload.getHash();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Offer offer = (Offer) o;

        if (offerPayload != null ? !offerPayload.equals(offer.offerPayload) : offer.offerPayload != null)
            return false;
        //noinspection SimplifiableIfStatement
        if (getState() != offer.getState()) return false;
        return !(getErrorMessage() != null ? !getErrorMessage().equals(offer.getErrorMessage()) : offer.getErrorMessage() != null);

    }

    @Override
    public int hashCode() {
        int result = offerPayload != null ? offerPayload.hashCode() : 0;
        result = 31 * result + (getState() != null ? getState().hashCode() : 0);
        result = 31 * result + (getErrorMessage() != null ? getErrorMessage().hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "Offer{" +
                "getErrorMessage()='" + getErrorMessage() + '\'' +
                ", state=" + getState() +
                ", offerPayload=" + offerPayload +
                '}';
    }
}
