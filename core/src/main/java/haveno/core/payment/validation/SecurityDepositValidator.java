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

package haveno.core.payment.validation;

import com.google.inject.Inject;
import haveno.core.locale.Res;
import haveno.core.payment.PaymentAccount;
import haveno.core.util.FormattingUtils;
import haveno.core.util.ParsingUtils;
import haveno.core.util.validation.NumberValidator;
import haveno.core.xmr.wallet.Restrictions;

public class SecurityDepositValidator extends NumberValidator {

    private PaymentAccount paymentAccount;

    @Inject
    public SecurityDepositValidator() {
    }

    public void setPaymentAccount(PaymentAccount paymentAccount) {
        this.paymentAccount = paymentAccount;
    }

    @Override
    public ValidationResult validate(String input) {
        ValidationResult result = validateIfNotEmpty(input);
        if (result.isValid) {
            input = cleanInput(input);
            result = validateIfNumber(input);
        }

        if (result.isValid) {
            result = result.andValidation(input,
                    this::validateIfNotZero,
                    this::validateIfNotNegative,
                    this::validateIfNotTooLowPercentageValue,
                    this::validateIfNotTooHighPercentageValue);
        }
        return result;
    }


    private ValidationResult validateIfNotTooLowPercentageValue(String input) {
        try {
            double percentage = ParsingUtils.parsePercentStringToDouble(input);
            double minPercentage = Restrictions.getMinSecurityDepositPct();
            if (percentage < minPercentage)
                return new ValidationResult(false,
                        Res.get("validation.inputTooSmall", FormattingUtils.formatToPercentWithSymbol(minPercentage)));
            else
                return new ValidationResult(true);
        } catch (Throwable t) {
            return new ValidationResult(false, Res.get("validation.invalidInput", t.getMessage()));
        }
    }

    private ValidationResult validateIfNotTooHighPercentageValue(String input) {
        try {
            double percentage = ParsingUtils.parsePercentStringToDouble(input);
            double maxPercentage = Restrictions.getMaxSecurityDepositPct();
            if (percentage > maxPercentage)
                return new ValidationResult(false,
                        Res.get("validation.inputTooLarge", FormattingUtils.formatToPercentWithSymbol(maxPercentage)));
            else
                return new ValidationResult(true);
        } catch (Throwable t) {
            return new ValidationResult(false, Res.get("validation.invalidInput", t.getMessage()));
        }
    }
}
