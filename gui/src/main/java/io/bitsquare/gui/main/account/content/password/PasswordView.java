/*
 * This file is part of Bitsquare.
 *
 * Bitsquare is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bitsquare is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bitsquare. If not, see <http://www.gnu.org/licenses/>.
 */

package io.bitsquare.gui.main.account.content.password;

import io.bitsquare.btc.TradeWalletService;
import io.bitsquare.btc.WalletService;
import io.bitsquare.common.util.Tuple2;
import io.bitsquare.common.util.Tuple3;
import io.bitsquare.crypto.ScryptUtil;
import io.bitsquare.gui.common.view.ActivatableView;
import io.bitsquare.gui.common.view.FxmlView;
import io.bitsquare.gui.components.PasswordTextField;
import io.bitsquare.gui.components.TitledGroupBg;
import io.bitsquare.gui.popups.Popup;
import io.bitsquare.gui.util.Layout;
import io.bitsquare.gui.util.validation.InputValidator;
import io.bitsquare.gui.util.validation.PasswordValidator;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.GridPane;
import org.bitcoinj.core.Wallet;
import org.bitcoinj.crypto.KeyCrypterScrypt;

import javax.inject.Inject;

import static io.bitsquare.gui.util.FormBuilder.*;

@FxmlView
public class PasswordView extends ActivatableView<GridPane, Void> {

    private final PasswordValidator passwordValidator;
    private final WalletService walletService;
    private final TradeWalletService tradeWalletService;

    private PasswordTextField passwordField;
    private PasswordTextField repeatedPasswordField;
    private Button pwButton;
    private TitledGroupBg headline;
    private int gridRow = 0;
    private Label repeatedPasswordLabel;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, lifecycle
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    private PasswordView(PasswordValidator passwordValidator, WalletService walletService, TradeWalletService tradeWalletService) {
        this.passwordValidator = passwordValidator;
        this.walletService = walletService;
        this.tradeWalletService = tradeWalletService;
    }

    @Override
    public void initialize() {
        headline = addTitledGroupBg(root, gridRow, 3, "");
        passwordField = addLabelPasswordTextField(root, gridRow, "Enter password:", Layout.FIRST_ROW_DISTANCE).second;
        passwordField.setValidator(passwordValidator);
        passwordField.textProperty().addListener((observable, oldValue, newValue) -> {
            validatePasswords();
        });

        Tuple2<Label, PasswordTextField> tuple2 = addLabelPasswordTextField(root, ++gridRow, "Repeat password:");
        repeatedPasswordLabel = tuple2.first;
        repeatedPasswordField = tuple2.second;
        repeatedPasswordField.setValidator(passwordValidator);
        repeatedPasswordField.textProperty().addListener((observable, oldValue, newValue) -> {
            validatePasswords();
        });

        Tuple3<Button, ProgressIndicator, Label> tuple = addButtonWithStatus(root, ++gridRow, "", 0);
        pwButton = tuple.first;
        ProgressIndicator progressIndicator = tuple.second;
        Label deriveStatusLabel = tuple.third;
        pwButton.setDisable(true);

        setText();

        pwButton.setOnAction(e -> {
            pwButton.setDisable(true);
            deriveStatusLabel.setText("Derive key from password");
            progressIndicator.setProgress(-1);
            progressIndicator.setVisible(true);

            KeyCrypterScrypt keyCrypterScrypt;
            Wallet wallet = walletService.getWallet();
            if (wallet.isEncrypted())
                keyCrypterScrypt = (KeyCrypterScrypt) wallet.getKeyCrypter();
            else
                keyCrypterScrypt = new KeyCrypterScrypt(ScryptUtil.SCRYPT_PARAMETERS);

            ScryptUtil.deriveKeyWithScrypt(keyCrypterScrypt, passwordField.getText(), aesKey -> {
                deriveStatusLabel.setText("");
                progressIndicator.setVisible(false);

                if (wallet.isEncrypted()) {
                    if (wallet.checkAESKey(aesKey)) {
                        wallet.decrypt(aesKey);
                        tradeWalletService.setAesKey(null);
                        new Popup()
                                .information("Wallet successfully decrypted and password protection removed.")
                                .show();
                        passwordField.setText("");
                        repeatedPasswordField.setText("");
                    } else {
                        new Popup()
                                .headLine("Wrong password")
                                .message("Please try entering your password again, carefully checking for typos or spelling errors.")
                                .show();
                    }
                } else {
                    wallet.encrypt(keyCrypterScrypt, aesKey);
                    // we save the key for the trade wallet as we don't require passwords here
                    tradeWalletService.setAesKey(aesKey);
                    new Popup()
                            .information("Wallet successfully encrypted and password protection enabled.")
                            .show();
                    passwordField.setText("");
                    repeatedPasswordField.setText("");
                }
                setText();
            });
        });

        addTitledGroupBg(root, ++gridRow, 1, "Information", Layout.GROUP_DISTANCE);
        addMultilineLabel(root, gridRow,
                "With password protection you need to enter your password when" +
                        " withdrawing Bitcoin out of your wallet or " +
                        "if you want to view or restore a wallet from seed words.\n" +
                        "For the transactions used in the trade process we don't support password protection as that would make automatic offer " +
                        "execution impossible.",
                Layout.FIRST_ROW_AND_GROUP_DISTANCE);
    }

    private void setText() {
        if (walletService.getWallet().isEncrypted()) {
            pwButton.setText("Remove password");
            headline.setText("Remove password protection for wallet");
            repeatedPasswordField.setVisible(false);
            repeatedPasswordField.setManaged(false);
            repeatedPasswordLabel.setVisible(false);
            repeatedPasswordLabel.setManaged(false);
        } else {
            pwButton.setText("Set password");
            headline.setText("Set password protection for wallet");
            repeatedPasswordField.setVisible(true);
            repeatedPasswordField.setManaged(true);
            repeatedPasswordLabel.setVisible(true);
            repeatedPasswordLabel.setManaged(true);
        }
    }

    @Override
    protected void activate() {
    }

    @Override
    protected void deactivate() {
    }

    private void validatePasswords() {
        passwordValidator.setExternalValidationResult(null);
        InputValidator.ValidationResult result = passwordValidator.validate(passwordField.getText());
        if (result.isValid) {
            if (walletService.getWallet().isEncrypted()) {
                pwButton.setDisable(false);
                return;
            } else {
                result = passwordValidator.validate(repeatedPasswordField.getText());

                if (result.isValid) {
                    if (passwordField.getText().equals(repeatedPasswordField.getText())) {
                        pwButton.setDisable(false);
                        return;
                    } else {
                        passwordValidator.setExternalValidationResult(new InputValidator.ValidationResult(false, "The 2 passwords do not match."));
                    }
                }
            }
        }
        pwButton.setDisable(true);
    }
}
