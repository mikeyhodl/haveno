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

package bisq.core.trade.protocol.tasks;

import bisq.common.taskrunner.TaskRunner;
import bisq.core.btc.model.XmrAddressEntry;
import bisq.core.offer.OfferDirection;
import bisq.core.trade.HavenoUtils;
import bisq.core.trade.Trade;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import org.bitcoinj.core.Coin;

import monero.daemon.model.MoneroOutput;
import monero.wallet.model.MoneroTxWallet;

public class TakerReserveTradeFunds extends TradeTask {

    public TakerReserveTradeFunds(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();

            // create reserve tx
            BigInteger takerFee = HavenoUtils.coinToAtomicUnits(trade.getTakerFee());
            BigInteger sendAmount = HavenoUtils.coinToAtomicUnits(trade.getOffer().getDirection() == OfferDirection.BUY ? trade.getOffer().getAmount() : Coin.ZERO);
            BigInteger securityDeposit = HavenoUtils.coinToAtomicUnits(trade.getOffer().getDirection() == OfferDirection.BUY ? trade.getOffer().getSellerSecurityDeposit() : trade.getOffer().getBuyerSecurityDeposit());
            String returnAddress = model.getXmrWalletService().getOrCreateAddressEntry(trade.getOffer().getId(), XmrAddressEntry.Context.TRADE_PAYOUT).getAddressString();
            MoneroTxWallet reserveTx = model.getXmrWalletService().createReserveTx(takerFee, sendAmount, securityDeposit, returnAddress);

            // collect reserved key images
            List<String> reservedKeyImages = new ArrayList<String>();
            for (MoneroOutput input : reserveTx.getInputs()) reservedKeyImages.add(input.getKeyImage().getHex());

            // save process state
            processModel.setReserveTx(reserveTx);
            processModel.getTaker().setReserveTxKeyImages(reservedKeyImages);
            processModel.getTradeManager().requestPersistence();
            complete();
        } catch (Throwable t) {
            trade.setErrorMessage("An error occurred.\n" +
                "Error message:\n"
                + t.getMessage());
            failed(t);
        }
    }
}
