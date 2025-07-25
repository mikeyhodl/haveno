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

package haveno.core.util.coin;

import haveno.core.monetary.Price;
import haveno.core.trade.HavenoUtils;
import haveno.core.xmr.wallet.Restrictions;
import org.bitcoinj.core.Coin;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

public class CoinUtilTest {

    @Test
    public void testGetPercentOfAmount() {
        BigInteger bi = new BigInteger("703100000000");
        assertEquals(new BigInteger("105465000000"), HavenoUtils.multiply(bi, .15));
    }

    @Test
    public void testGetFeePerXmr() {
        assertEquals(HavenoUtils.xmrToAtomicUnits(1), HavenoUtils.multiply(HavenoUtils.xmrToAtomicUnits(1), 1.0));
        assertEquals(HavenoUtils.xmrToAtomicUnits(0.1), HavenoUtils.multiply(HavenoUtils.xmrToAtomicUnits(0.1), 1.0));
        assertEquals(HavenoUtils.xmrToAtomicUnits(0.01), HavenoUtils.multiply(HavenoUtils.xmrToAtomicUnits(0.1), 0.1));
        assertEquals(HavenoUtils.xmrToAtomicUnits(0.015), HavenoUtils.multiply(HavenoUtils.xmrToAtomicUnits(0.3), 0.05));
    }

    @Test
    public void testParseXmr() {
        String xmrStr = "0.266394780889";
        BigInteger au = HavenoUtils.parseXmr(xmrStr);
        assertEquals(new BigInteger("266394780889"), au);
        assertEquals(xmrStr, "" + HavenoUtils.atomicUnitsToXmr(au));
        assertEquals(xmrStr, HavenoUtils.formatXmr(au, false));
    }

    @Test
    public void testMinCoin() {
        assertEquals(Coin.parseCoin("1"), CoinUtil.minCoin(Coin.parseCoin("1"), Coin.parseCoin("1")));
        assertEquals(Coin.parseCoin("0.1"), CoinUtil.minCoin(Coin.parseCoin("0.1"), Coin.parseCoin("1")));
        assertEquals(Coin.parseCoin("0.01"), CoinUtil.minCoin(Coin.parseCoin("0.1"), Coin.parseCoin("0.01")));
        assertEquals(Coin.parseCoin("0"), CoinUtil.minCoin(Coin.parseCoin("0"), Coin.parseCoin("0.05")));
        assertEquals(Coin.parseCoin("0"), CoinUtil.minCoin(Coin.parseCoin("0.05"), Coin.parseCoin("0")));
    }

    @Test
    public void testMaxCoin() {
        assertEquals(Coin.parseCoin("1"), CoinUtil.maxCoin(Coin.parseCoin("1"), Coin.parseCoin("1")));
        assertEquals(Coin.parseCoin("1"), CoinUtil.maxCoin(Coin.parseCoin("0.1"), Coin.parseCoin("1")));
        assertEquals(Coin.parseCoin("0.1"), CoinUtil.maxCoin(Coin.parseCoin("0.1"), Coin.parseCoin("0.01")));
        assertEquals(Coin.parseCoin("0.05"), CoinUtil.maxCoin(Coin.parseCoin("0"), Coin.parseCoin("0.05")));
        assertEquals(Coin.parseCoin("0.05"), CoinUtil.maxCoin(Coin.parseCoin("0.05"), Coin.parseCoin("0")));
    }

    @Test
    public void testGetAdjustedAmount() {
        BigInteger result = CoinUtil.getAdjustedAmount(
                HavenoUtils.xmrToAtomicUnits(0.1),
                Price.valueOf("USD", 1000_0000),
                Restrictions.getMinTradeAmount(),
                HavenoUtils.xmrToAtomicUnits(0.2),
                1);
        assertEquals(
                HavenoUtils.formatXmr(Restrictions.getMinTradeAmount(), true),
                HavenoUtils.formatXmr(result, true),
                "Minimum trade amount allowed should be adjusted to the smallest trade allowed."
        );

        try {
            CoinUtil.getAdjustedAmount(
                    BigInteger.ZERO,
                    Price.valueOf("USD", 1000_0000),
                    HavenoUtils.xmrToAtomicUnits(0.1),
                    HavenoUtils.xmrToAtomicUnits(0.2),
                    1);
            fail("Expected IllegalArgumentException to be thrown when amount is too low.");
        } catch (IllegalArgumentException iae) {
            assertEquals(
                    "amount must be above minimum of 0.05 xmr but was 0.0 xmr",
                    iae.getMessage(),
                    "Unexpected exception message."
            );
        }

        result = CoinUtil.getAdjustedAmount(
                HavenoUtils.xmrToAtomicUnits(0.1),
                Price.valueOf("USD", 1000_0000),
                Restrictions.getMinTradeAmount(),
                HavenoUtils.xmrToAtomicUnits(0.2),
                1);
        assertEquals(
                "0.05 XMR",
                HavenoUtils.formatXmr(result, true),
                "Minimum allowed trade amount should not be adjusted."
        );

        result = CoinUtil.getAdjustedAmount(
                HavenoUtils.xmrToAtomicUnits(0.1),
                Price.valueOf("USD", 1000_0000),
                Restrictions.getMinTradeAmount(),
                HavenoUtils.xmrToAtomicUnits(0.25),
                1);
        assertEquals(
                "0.05 XMR",
                HavenoUtils.formatXmr(result, true),
                "Minimum trade amount allowed should respect maxTradeLimit and factor, if possible."
        );

        result = CoinUtil.getAdjustedAmount(
                HavenoUtils.xmrToAtomicUnits(0.1),
                Price.valueOf("USD", 1000_0000),
                HavenoUtils.xmrToAtomicUnits(0.1),
                HavenoUtils.xmrToAtomicUnits(0.5),
                1);
        assertEquals(
                "0.10 XMR",
                HavenoUtils.formatXmr(result, true),
                "Minimum trade amount allowed with low maxTradeLimit should still respect that limit, even if result does not respect the factor specified."
        );
    }
}
