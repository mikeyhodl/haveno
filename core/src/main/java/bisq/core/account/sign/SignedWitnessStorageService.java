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

package bisq.core.account.sign;

import bisq.network.p2p.storage.P2PDataStorage;
import bisq.network.p2p.storage.payload.PersistableNetworkPayload;
import bisq.network.p2p.storage.persistence.MapStoreService;

import bisq.common.config.Config;
import bisq.common.persistence.PersistenceManager;

import javax.inject.Inject;
import javax.inject.Named;

import java.io.File;

import java.util.Map;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SignedWitnessStorageService extends MapStoreService<SignedWitnessStore, PersistableNetworkPayload> {
    private static final String FILE_NAME = "SignedWitnessStore";


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public SignedWitnessStorageService(@Named(Config.STORAGE_DIR) File storageDir,
                                       PersistenceManager<SignedWitnessStore> persistenceManager) {
        super(storageDir, persistenceManager);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    protected void initializePersistenceManager() {
        persistenceManager.initialize(store, PersistenceManager.Source.NETWORK);
    }

    @Override
    public String getFileName() {
        return FILE_NAME;
    }

    @Override
    public Map<P2PDataStorage.ByteArray, PersistableNetworkPayload> getMap() {
        return store.getMap();
    }

    @Override
    public boolean canHandle(PersistableNetworkPayload payload) {
        return payload instanceof SignedWitness;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Protected
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    protected SignedWitnessStore createStore() {
        return new SignedWitnessStore();
    }
}
