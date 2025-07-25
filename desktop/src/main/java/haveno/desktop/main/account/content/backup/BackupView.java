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

package haveno.desktop.main.account.content.backup;

import com.google.inject.Inject;
import haveno.common.UserThread;
import haveno.common.config.Config;
import haveno.common.file.FileUtil;
import haveno.common.persistence.PersistenceManager;
import haveno.common.util.Tuple2;
import haveno.common.util.Utilities;
import haveno.core.api.XmrLocalNode;
import haveno.core.locale.Res;
import haveno.core.user.Preferences;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.desktop.app.HavenoApp;
import haveno.desktop.common.view.ActivatableView;
import haveno.desktop.common.view.FxmlView;
import haveno.desktop.main.overlays.popups.Popup;
import static haveno.desktop.util.FormBuilder.add2Buttons;
import static haveno.desktop.util.FormBuilder.add2ButtonsAfterGroup;
import static haveno.desktop.util.FormBuilder.addInputTextField;
import static haveno.desktop.util.FormBuilder.addTitledGroupBg;
import haveno.desktop.util.Layout;
import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import javafx.beans.value.ChangeListener;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.stage.DirectoryChooser;
import javax.annotation.Nullable;

@FxmlView
public class BackupView extends ActivatableView<GridPane, Void> {
    private final File dataDir, logFile;
    private int gridRow = 0;
    private final Preferences preferences;
    private Button selectBackupDir, backupNow;
    private TextField backUpLocationTextField;
    private Button openDataDirButton, openLogsButton;
    private ChangeListener<Boolean> backUpLocationTextFieldFocusListener;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, lifecycle
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    private BackupView(Preferences preferences, Config config) {
        super();
        this.preferences = preferences;
        dataDir = new File(config.appDataDir.getPath());
        logFile = new File(Paths.get(dataDir.getPath(), "haveno.log").toString());
    }


    @Override
    public void initialize() {
        addTitledGroupBg(root, gridRow, 2, Res.get("account.backup.title"));
        backUpLocationTextField = addInputTextField(root, gridRow, Res.get("account.backup.location"), Layout.FIRST_ROW_DISTANCE);
        String backupDirectory = preferences.getBackupDirectory();
        if (backupDirectory != null)
            backUpLocationTextField.setText(backupDirectory);

        backUpLocationTextFieldFocusListener = (observable, oldValue, newValue) -> {
            if (oldValue && !newValue)
                applyBackupDirectory(backUpLocationTextField.getText());
        };

        Tuple2<Button, Button> tuple2 = add2ButtonsAfterGroup(root, ++gridRow,
                Res.get("account.backup.selectLocation"), Res.get("account.backup.backupNow"));
        selectBackupDir = tuple2.first;
        selectBackupDir.setId("buy-button-big");
        backupNow = tuple2.second;
        updateButtons();

        addTitledGroupBg(root, ++gridRow, 2, Res.get("account.backup.appDir"), Layout.GROUP_DISTANCE);

        final Tuple2<Button, Button> applicationDataDirTuple2 = add2Buttons(root, gridRow, Res.get("account.backup.openDirectory"),
                Res.get("account.backup.openLogFile"), Layout.TWICE_FIRST_ROW_AND_GROUP_DISTANCE, false);

        openDataDirButton = applicationDataDirTuple2.first;
        openLogsButton = applicationDataDirTuple2.second;
    }

    @Override
    protected void activate() {
        backUpLocationTextField.focusedProperty().addListener(backUpLocationTextFieldFocusListener);
        selectBackupDir.setOnAction(e -> {
            String path = preferences.getDirectoryChooserPath();
            if (!Utilities.isDirectory(path)) {
                path = Utilities.getSystemHomeDirectory();
                backUpLocationTextField.setText(path);
            }
            DirectoryChooser directoryChooser = new DirectoryChooser();
            directoryChooser.setInitialDirectory(new File(path));
            directoryChooser.setTitle(Res.get("account.backup.selectLocation"));
            try {
                File dir = directoryChooser.showDialog(root.getScene().getWindow());
                if (dir != null) {
                    applyBackupDirectory(dir.getAbsolutePath());
                }
            } catch (Throwable t) {
                showWrongPathWarningAndReset(t);
            }

        });
        openFileOrShowWarning(openDataDirButton, dataDir);
        openFileOrShowWarning(openLogsButton, logFile);

        backupNow.setOnAction(event -> {

            // windows requires closing wallets for read access
            if (Utilities.isWindows()) {
                new Popup().information(Res.get("settings.net.needRestart"))
                    .actionButtonText(Res.get("shared.applyAndShutDown"))
                    .onAction(() -> {
                        UserThread.runAfter(() -> {
                            HavenoApp.setOnGracefulShutDownHandler(() -> doBackup());
                            HavenoApp.getShutDownHandler().run();
                        }, 500, TimeUnit.MILLISECONDS);
                    })
                    .closeButtonText(Res.get("shared.cancel"))
                    .onClose(() -> {
                        // nothing to do
                    })
                    .show();
            } else {
                doBackup();
            }
        });
    }

    private void doBackup() {
        log.info("Backing up data directory");
        String backupDirectory = preferences.getBackupDirectory();
        if (backupDirectory != null && backupDirectory.length() > 0) {  // We need to flush data to disk
            PersistenceManager.flushAllDataToDiskAtBackup(() -> {
                try {

                    // copy data directory to backup directory
                    String dateString = new SimpleDateFormat("yyyy-MM-dd-HHmmss").format(new Date());
                    String destination = Paths.get(backupDirectory, "haveno_backup_" + dateString).toString();
                    File destinationFile = new File(destination);
                    FileUtil.copyDirectory(dataDir, new File(destination));

                    // delete monerod and monero-wallet-rpc binaries from backup so they're reinstalled with permissions
                    File monerod = new File(destinationFile, XmrLocalNode.MONEROD_NAME);
                    if (monerod.exists()) monerod.delete();
                    File moneroWalletRpc = new File(destinationFile, XmrWalletService.MONERO_WALLET_RPC_NAME);
                    if (moneroWalletRpc.exists()) moneroWalletRpc.delete();
                    new Popup().feedback(Res.get("account.backup.success", destination)).show();
                } catch (IOException e) {
                    e.printStackTrace();
                    log.error(e.getMessage());
                    showWrongPathWarningAndReset(e);
                }
            });
        }
    }

    private void openFileOrShowWarning(Button button, File dataDir) {
        button.setOnAction(event -> {
            try {
                Utilities.openFile(dataDir);
            } catch (IOException e) {
                e.printStackTrace();
                log.error(e.getMessage());
                showWrongPathWarningAndReset(e);
            }
        });
    }

    @Override
    protected void deactivate() {
        backUpLocationTextField.focusedProperty().removeListener(backUpLocationTextFieldFocusListener);
        selectBackupDir.setOnAction(null);
        openDataDirButton.setOnAction(null);
        openLogsButton.setOnAction(null);
        backupNow.setOnAction(null);
    }

    private void updateButtons() {
        boolean noBackupSet = backUpLocationTextField.getText() == null || backUpLocationTextField.getText().length() == 0;
        selectBackupDir.setDefaultButton(noBackupSet);
        backupNow.setDefaultButton(!noBackupSet);
        backupNow.setDisable(noBackupSet);
    }

    private void showWrongPathWarningAndReset(@Nullable Throwable t) {
        String error = t != null ? Res.get("shared.errorMessageInline", t.getMessage()) : "";
        new Popup().warning(Res.get("account.backup.directoryNotAccessible", error)).show();
        applyBackupDirectory(Utilities.getSystemHomeDirectory());
    }

    private void applyBackupDirectory(String path) {
        if (isPathValid(path)) {
            preferences.setDirectoryChooserPath(path);
            backUpLocationTextField.setText(path);
            preferences.setBackupDirectory(path);
            updateButtons();
        } else {
            showWrongPathWarningAndReset(null);
        }
    }

    private boolean isPathValid(String path) {
        return path == null || path.isEmpty() || Utilities.isDirectory(path);
    }
}

