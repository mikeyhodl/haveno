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

package bisq.desktop.main.portfolio.openoffer;

import bisq.core.offer.Offer;
import bisq.core.offer.OpenOffer;

import lombok.Getter;

/**
 * We could remove that wrapper if it is not needed for additional UI only fields.
 */
class OpenOfferListItem {
    @Getter
    private final OpenOffer openOffer;

    OpenOfferListItem(OpenOffer openOffer) {
        this.openOffer = openOffer;
    }

    OpenOfferListItem() {
        openOffer = null;
    }

    public Offer getOffer() {
        return openOffer.getOffer();
    }
}
