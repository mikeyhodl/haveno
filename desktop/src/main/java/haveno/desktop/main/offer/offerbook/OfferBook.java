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

import com.google.inject.Inject;
import com.google.inject.Singleton;

import haveno.common.UserThread;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferBookService;
import static haveno.core.offer.OfferDirection.BUY;
import haveno.network.p2p.storage.P2PDataStorage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import lombok.extern.slf4j.Slf4j;

/**
 * Holds and manages the unsorted and unfiltered offerbook list (except for banned offers) of both buy and sell offers.
 * It is handled as singleton by Guice and is used by 2 instances of OfferBookDataModel (one for Buy one for Sell).
 * As it is used only by the Buy and Sell UIs we treat it as local UI model.
 * It also use OfferRepository.Listener as the lists items class and we don't want to get any dependency out of the
 * package for that.
 */
@Singleton
@Slf4j
public class OfferBook {
    private final OfferBookService offerBookService;
    private final ObservableList<OfferBookListItem> offerBookListItems = FXCollections.observableArrayList();
    private final Map<String, Integer> buyOfferCountMap = new HashMap<>();
    private final Map<String, Integer> sellOfferCountMap = new HashMap<>();


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    OfferBook(OfferBookService offerBookService) {
        this.offerBookService = offerBookService;

        offerBookService.addOfferBookChangedListener(new OfferBookService.OfferBookChangedListener() {
            @Override
            public void onAdded(Offer offer) {
                UserThread.execute(() -> {
                    printOfferBookListItems("Before onAdded");

                    // Use offer.equals(offer) to see if the OfferBook list contains an exact
                    // match -- offer.equals(offer) includes comparisons of payload, state
                    // and errorMessage.
                    synchronized (offerBookListItems) {
                        boolean hasSameOffer = offerBookListItems.stream().anyMatch(item -> item.getOffer().equals(offer));
                        if (!hasSameOffer) {
                            OfferBookListItem newOfferBookListItem = new OfferBookListItem(offer);
                            removeDuplicateItem(newOfferBookListItem);
                            offerBookListItems.add(newOfferBookListItem);  // Add replacement.
                            if (log.isDebugEnabled()) {  // TODO delete debug stmt in future PR.
                                log.debug("onAdded: Added new offer {}\n"
                                                + "\twith newItem.payloadHash: {}",
                                        offer.getId(),
                                        newOfferBookListItem.hashOfPayload.getHex());
                            }
                        } else {
                            log.debug("We have the exact same offer already in our list and ignore the onAdded call. ID={}", offer.getId());
                        }
                        printOfferBookListItems("After onAdded");
                    }
                });
            }

            @Override
            public void onRemoved(Offer offer) {
                UserThread.execute(() -> {
                    synchronized (offerBookListItems) {
                        printOfferBookListItems("Before onRemoved");
                        removeOffer(offer);
                        printOfferBookListItems("After onRemoved");
                    }
                });
            }
        });
    }

    private void removeDuplicateItem(OfferBookListItem newOfferBookListItem) {
        synchronized (offerBookListItems) {
            String offerId = newOfferBookListItem.getOffer().getId();

            // We need to remove any view items with a matching offerId before
            // a newOfferBookListItem is added to the view.
            List<OfferBookListItem> duplicateItems = offerBookListItems.stream()
                    .filter(item -> item.getOffer().getId().equals(offerId))
                    .collect(Collectors.toList());
            duplicateItems.forEach(oldOfferItem -> {
                offerBookListItems.remove(oldOfferItem);
                if (log.isDebugEnabled()) {  // TODO delete debug stmt in future PR.
                    log.debug("onAdded: Removed old offer {}\n"
                                    + "\twith payload hash {} from list.\n"
                                    + "\tThis may make a subsequent onRemoved( {} ) call redundant.",
                            offerId,
                            oldOfferItem.getHashOfPayload().getHex(),
                            oldOfferItem.getOffer().getId());
                }
            });
        }
    }

    public void removeOffer(Offer offer) {
        synchronized (offerBookListItems) {

            // Update state in case that that offer is used in the take offer screen, so it gets updated correctly
            offer.setState(Offer.State.REMOVED);
            offer.cancelAvailabilityRequest();

            P2PDataStorage.ByteArray hashOfPayload = new P2PDataStorage.ByteArray(offer.getOfferPayload().getHash());

            if (log.isDebugEnabled()) {  // TODO delete debug stmt in future PR.
                log.debug("onRemoved: id = {}\n"
                                + "\twith payload-hash = {}",
                        offer.getId(),
                        hashOfPayload.getHex());
            }

            // Find the removal candidate in the OfferBook list with matching offerId and payload-hash.
            Optional<OfferBookListItem> candidateWithMatchingPayloadHash = offerBookListItems.stream()
                    .filter(item -> item.getOffer().getId().equals(offer.getId())
                            && item.hashOfPayload.equals(hashOfPayload))
                    .findAny();

            if (!candidateWithMatchingPayloadHash.isPresent()) {
                if (log.isDebugEnabled()) {  // TODO delete debug stmt in future PR.
                    log.debug("UI view list does not contain offer with id {} and payload-hash {}",
                            offer.getId(),
                            hashOfPayload.getHex());
                }
                return;
            }

            OfferBookListItem candidate = candidateWithMatchingPayloadHash.get();
            // Remove the candidate only if the candidate's offer payload the hash matches the
            // onRemoved hashOfPayload parameter.  We may receive add/remove messages out of
            // order from the API's 'editoffer' method, and use the offer payload hash to
            // ensure we do not remove an edited offer immediately after it was added.
            if (candidate.getHashOfPayload().equals(hashOfPayload)) {
                // The payload-hash test passed, remove the candidate and print reason.
                offerBookListItems.remove(candidate);

                if (log.isDebugEnabled()) {  // TODO delete debug stmt in future PR.
                    log.debug("Candidate.payload-hash: {} == onRemoved.payload-hash: {} ?"
                                    + " Yes, removed old offer",
                            candidate.hashOfPayload.getHex(),
                            hashOfPayload.getHex());
                }
            } else {
                if (log.isDebugEnabled()) {  // TODO delete debug stmt in future PR.
                    // Candidate's payload-hash test failed:  payload-hash != onRemoved.payload-hash.
                    // Print reason for not removing candidate.
                    log.debug("Candidate.payload-hash: {} == onRemoved.payload-hash: {} ?"
                                    + " No, old offer not removed",
                            candidate.hashOfPayload.getHex(),
                            hashOfPayload.getHex());
                }
            }
        }
    }

    public ObservableList<OfferBookListItem> getOfferBookListItems() {
        return offerBookListItems;
    }

    public void fillOfferBookListItems() {
        synchronized (offerBookListItems) {
            try {
                // setAll causes sometimes an UnsupportedOperationException
                // Investigate why....
                offerBookListItems.clear();
                offerBookListItems.addAll(offerBookService.getOffers().stream()
                        .map(OfferBookListItem::new)
                        .collect(Collectors.toList()));

                log.debug("offerBookListItems.size {}", offerBookListItems.size());
                fillOfferCountMaps();
            } catch (Throwable t) {
                log.error("Error at fillOfferBookListItems: " + t);
            }
        }
    }

    public void printOfferBookListItems(String msg) {
        synchronized (offerBookListItems) {
            if (log.isDebugEnabled()) {
                if (offerBookListItems.size() == 0) {
                    log.debug("{} -> OfferBookListItems:  none", msg);
                    return;
                }

                StringBuilder stringBuilder = new StringBuilder(msg + " -> ").append("OfferBookListItems:").append("\n");
                offerBookListItems.forEach(i -> stringBuilder.append("\t").append(i.toString()).append("\n"));
                stringBuilder.deleteCharAt(stringBuilder.length() - 1);
                log.debug(stringBuilder.toString());
            }
        }
    }

    public Map<String, Integer> getBuyOfferCountMap() {
        return buyOfferCountMap;
    }

    public Map<String, Integer> getSellOfferCountMap() {
        return sellOfferCountMap;
    }

    private void fillOfferCountMaps() {
        buyOfferCountMap.clear();
        sellOfferCountMap.clear();
        final String[] ccyCode = new String[1];
        final int[] offerCount = new int[1];
        offerBookListItems.forEach(o -> {
            ccyCode[0] = o.getOffer().getCounterCurrencyCode();
            if (o.getOffer().getDirection() == BUY) {
                offerCount[0] = buyOfferCountMap.getOrDefault(ccyCode[0], 0) + 1;
                buyOfferCountMap.put(ccyCode[0], offerCount[0]);
            } else {
                offerCount[0] = sellOfferCountMap.getOrDefault(ccyCode[0], 0) + 1;
                sellOfferCountMap.put(ccyCode[0], offerCount[0]);
            }
        });
        log.debug("buyOfferCountMap.size {}   sellOfferCountMap.size {}",
                buyOfferCountMap.size(), sellOfferCountMap.size());
    }
}
