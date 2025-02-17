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

package bisq.core.support.dispute.refund;

import bisq.core.support.dispute.DisputeListService;

import bisq.common.persistence.PersistenceManager;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public final class RefundDisputeListService extends DisputeListService<RefundDisputeList> {

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public RefundDisputeListService(PersistenceManager<RefundDisputeList> persistenceManager) {
        super(persistenceManager);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Implement template methods
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    protected RefundDisputeList getConcreteDisputeList() {
        return new RefundDisputeList();
    }
}
