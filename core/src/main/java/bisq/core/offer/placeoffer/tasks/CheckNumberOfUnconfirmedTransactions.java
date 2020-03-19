package bisq.core.offer.placeoffer.tasks;

import bisq.core.offer.placeoffer.PlaceOfferModel;

import bisq.common.taskrunner.Task;
import bisq.common.taskrunner.TaskRunner;

public class CheckNumberOfUnconfirmedTransactions extends Task<PlaceOfferModel> {
    public CheckNumberOfUnconfirmedTransactions(TaskRunner taskHandler, PlaceOfferModel model) {
        super(taskHandler, model);
    }

    @Override
    protected void run() {
        if (model.getWalletService().isUnconfirmedTransactionsLimitHit())
            failed("There are too many unconfirmed transactions at the moment. Please try again later.");
        complete();
    }
}
