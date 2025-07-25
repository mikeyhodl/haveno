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

package haveno.desktop.main;

import com.google.inject.Inject;
import com.jfoenix.controls.JFXBadge;
import com.jfoenix.controls.JFXComboBox;
import haveno.common.app.Version;
import haveno.common.HavenoException;
import haveno.common.Timer;
import haveno.common.UserThread;
import haveno.common.config.BaseCurrencyNetwork;
import haveno.common.config.Config;
import haveno.common.util.Tuple2;
import haveno.common.util.Utilities;
import haveno.core.locale.GlobalSettings;
import haveno.core.locale.LanguageUtil;
import haveno.core.locale.Res;
import haveno.core.provider.price.MarketPrice;
import haveno.core.user.Preferences;
import haveno.desktop.Navigation;
import haveno.desktop.common.view.CachingViewLoader;
import haveno.desktop.common.view.FxmlView;
import haveno.desktop.common.view.InitializableView;
import haveno.desktop.common.view.View;
import haveno.desktop.common.view.ViewLoader;
import haveno.desktop.components.AutoTooltipButton;
import haveno.desktop.components.AutoTooltipLabel;
import haveno.desktop.components.AutoTooltipToggleButton;
import haveno.desktop.components.BusyAnimation;
import haveno.desktop.main.account.AccountView;
import haveno.desktop.main.funds.FundsView;
import haveno.desktop.main.market.MarketView;
import haveno.desktop.main.market.offerbook.OfferBookChartView;
import haveno.desktop.main.offer.BuyOfferView;
import haveno.desktop.main.offer.SellOfferView;
import haveno.desktop.main.overlays.popups.Popup;
import haveno.desktop.main.overlays.windows.TorNetworkSettingsWindow;
import haveno.desktop.main.portfolio.PortfolioView;
import haveno.desktop.main.settings.SettingsView;
import haveno.desktop.main.shared.PriceFeedComboBoxItem;
import haveno.desktop.main.support.SupportView;
import haveno.desktop.util.DisplayUtils;
import haveno.desktop.util.Transitions;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.Date;
import java.util.Locale;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.binding.ObjectBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.NodeOrientation;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Separator;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.AnchorPane;
import static javafx.scene.layout.AnchorPane.setBottomAnchor;
import static javafx.scene.layout.AnchorPane.setLeftAnchor;
import static javafx.scene.layout.AnchorPane.setRightAnchor;
import static javafx.scene.layout.AnchorPane.setTopAnchor;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

@FxmlView
@Slf4j
public class MainView extends InitializableView<StackPane, MainViewModel>  {
    // If after 30 sec we have not got connected we show "open network settings" button
    private final static int SHOW_TOR_SETTINGS_DELAY_SEC = 90;
    @Setter
    private Runnable onApplicationStartedHandler;
    private static Transitions transitions;
    private static StackPane rootContainer;
    private final ViewLoader viewLoader;
    private final Navigation navigation;
    private final ToggleGroup navButtons = new ToggleGroup();
    private ChangeListener<String> walletServiceErrorMsgListener;
    private ChangeListener<String> xmrSyncIconIdListener;
    private ChangeListener<String> splashP2PNetworkErrorMsgListener;
    private ChangeListener<String> splashP2PNetworkIconIdListener;
    private ChangeListener<Boolean> splashP2PNetworkVisibleListener;
    private BusyAnimation splashP2PNetworkBusyAnimation;
    private Label splashP2PNetworkLabel;
    private ProgressBar xmrSyncIndicator;
    private Label xmrSplashInfo;
    private Popup p2PNetworkWarnMsgPopup, xmrNetworkWarnMsgPopup;
    private final TorNetworkSettingsWindow torNetworkSettingsWindow;
    private final Preferences preferences;
    private static final int networkIconSize = 20;

    public static StackPane getRootContainer() {
        return MainView.rootContainer;
    }

    public static void blurLight() {
        transitions.blur(MainView.rootContainer, Transitions.DEFAULT_DURATION, -0.6, false, 15);
    }

    public static void blurUltraLight() {
        transitions.blur(MainView.rootContainer, Transitions.DEFAULT_DURATION, -0.6, false, 15);
    }

    public static void darken() {
        transitions.darken(MainView.rootContainer, Transitions.DEFAULT_DURATION, false);
    }

    public static void removeEffect() {
        transitions.removeEffect(MainView.rootContainer);
    }

    @Inject
    public MainView(MainViewModel model,
                    CachingViewLoader viewLoader,
                    Navigation navigation,
                    Transitions transitions,
                    TorNetworkSettingsWindow torNetworkSettingsWindow,
                    Preferences preferences) {
        super(model);
        this.viewLoader = viewLoader;
        this.navigation = navigation;
        MainView.transitions = transitions;
        this.torNetworkSettingsWindow = torNetworkSettingsWindow;
        this.preferences = preferences;
    }

    @Override
    protected void initialize() {
        MainView.rootContainer = root;
        if (LanguageUtil.isDefaultLanguageRTL())
            MainView.rootContainer.setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);

        ToggleButton marketButton = new NavButton(MarketView.class, Res.get("mainView.menu.market"));
        ToggleButton buyButton = new NavButton(BuyOfferView.class, Res.get("mainView.menu.buyXmr"));
        ToggleButton sellButton = new NavButton(SellOfferView.class, Res.get("mainView.menu.sellXmr"));
        ToggleButton portfolioButton = new NavButton(PortfolioView.class, Res.get("mainView.menu.portfolio"));
        ToggleButton fundsButton = new NavButton(FundsView.class, Res.get("mainView.menu.funds"));

        ToggleButton supportButton = new SecondaryNavButton(SupportView.class, Res.get("mainView.menu.support"), "image-support");
        ToggleButton accountButton = new SecondaryNavButton(AccountView.class, Res.get("mainView.menu.account"), "image-account");
        ToggleButton settingsButton = new SecondaryNavButton(SettingsView.class, Res.get("mainView.menu.settings"), "image-settings");

        JFXBadge portfolioButtonWithBadge = new JFXBadge(portfolioButton);
        JFXBadge supportButtonWithBadge = new JFXBadge(supportButton);
        JFXBadge settingsButtonWithBadge = new JFXBadge(settingsButton);

        Locale locale = GlobalSettings.getLocale();
        DecimalFormat currencyFormat = (DecimalFormat) NumberFormat.getNumberInstance(locale);
        currencyFormat.setMinimumFractionDigits(2);
        currencyFormat.setMaximumFractionDigits(2);

        root.sceneProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null) {
                newValue.addEventHandler(KeyEvent.KEY_RELEASED, keyEvent -> {
                    if (Utilities.isAltOrCtrlPressed(KeyCode.DIGIT1, keyEvent)) {
                        marketButton.fire();
                    } else if (Utilities.isAltOrCtrlPressed(KeyCode.DIGIT2, keyEvent)) {
                        buyButton.fire();
                    } else if (Utilities.isAltOrCtrlPressed(KeyCode.DIGIT3, keyEvent)) {
                        sellButton.fire();
                    } else if (Utilities.isAltOrCtrlPressed(KeyCode.DIGIT4, keyEvent)) {
                        portfolioButton.fire();
                    } else if (Utilities.isAltOrCtrlPressed(KeyCode.DIGIT5, keyEvent)) {
                        fundsButton.fire();
                    } else if (Utilities.isAltOrCtrlPressed(KeyCode.DIGIT6, keyEvent)) {
                        supportButton.fire();
                    } else if (Utilities.isAltOrCtrlPressed(KeyCode.DIGIT8, keyEvent)) {
                        accountButton.fire();
                    } else if (Utilities.isAltOrCtrlPressed(KeyCode.DIGIT7, keyEvent)) {
                        settingsButton.fire();
                    }
                });
            }
        });


        Tuple2<ComboBox<PriceFeedComboBoxItem>, VBox> marketPriceBox = getMarketPriceBox();
        ComboBox<PriceFeedComboBoxItem> priceComboBox = marketPriceBox.first;

        priceComboBox.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) ->
                model.setPriceFeedComboBoxItem(newValue));
        ChangeListener<PriceFeedComboBoxItem> selectedPriceFeedItemListener = (observable, oldValue, newValue) -> {
            if (newValue != null)
                priceComboBox.getSelectionModel().select(newValue);

        };
        model.getSelectedPriceFeedComboBoxItemProperty().addListener(selectedPriceFeedItemListener);
        priceComboBox.setItems(model.getPriceFeedComboBoxItems());

        Tuple2<Label, VBox> availableBalanceBox = getBalanceBox(Res.get("mainView.balance.available"));
        availableBalanceBox.first.textProperty().bind(model.getAvailableBalance());
        availableBalanceBox.first.setPrefWidth(112);
        availableBalanceBox.first.tooltipProperty().bind(new ObjectBinding<>() {
            {
                bind(model.getAvailableBalance());
                bind(model.getMarketPrice());
            }

            @Override
            protected Tooltip computeValue() {
                String tooltipText = Res.get("mainView.balance.available");
                try {
                    String preferredTradeCurrency = model.getPreferences().getPreferredTradeCurrency().getCode();
                    double availableBalance = Double.parseDouble(
                            model.getAvailableBalance().getValue().replace("XMR", ""));
                    double marketPrice = Double.parseDouble(model.getMarketPrice(preferredTradeCurrency).getValue());
                    tooltipText += "\n" + currencyFormat.format(availableBalance * marketPrice) +
                            " " + preferredTradeCurrency;
                } catch (NullPointerException | NumberFormatException e) {
                    // Either the balance or market price is not available yet
                }
                return new Tooltip(tooltipText);
            }
        });

        Tuple2<Label, VBox> reservedBalanceBox = getBalanceBox(Res.get("mainView.balance.reserved.short"));
        reservedBalanceBox.first.textProperty().bind(model.getReservedBalance());
        reservedBalanceBox.first.tooltipProperty().bind(new ObjectBinding<>() {
            {
                bind(model.getReservedBalance());
                bind(model.getMarketPrice());
            }

            @Override
            protected Tooltip computeValue() {
                String tooltipText = Res.get("mainView.balance.reserved");
                try {
                    String preferredTradeCurrency = model.getPreferences().getPreferredTradeCurrency().getCode();
                    double reservedBalance = Double.parseDouble(
                            model.getReservedBalance().getValue().replace("XMR", ""));
                    double marketPrice = Double.parseDouble(model.getMarketPrice(preferredTradeCurrency).getValue());
                    tooltipText += "\n" + currencyFormat.format(reservedBalance * marketPrice) +
                            " " + preferredTradeCurrency;
                } catch (NullPointerException | NumberFormatException e) {
                    // Either the balance or market price is not available yet
                }
                return new Tooltip(tooltipText);
            }
        });

        Tuple2<Label, VBox> pendingBalanceBox = getBalanceBox(Res.get("mainView.balance.pending.short"));
        pendingBalanceBox.first.textProperty().bind(model.getPendingBalance());
        pendingBalanceBox.first.tooltipProperty().bind(new ObjectBinding<>() {
            {
                bind(model.getPendingBalance());
                bind(model.getMarketPrice());
            }

            @Override
            protected Tooltip computeValue() {
                String tooltipText = Res.get("mainView.balance.pending");
                try {
                    String preferredTradeCurrency = model.getPreferences().getPreferredTradeCurrency().getCode();
                    double lockedBalance = Double.parseDouble(
                            model.getPendingBalance().getValue().replace("XMR", ""));
                    double marketPrice = Double.parseDouble(model.getMarketPrice(preferredTradeCurrency).getValue());
                    tooltipText += "\n" + currencyFormat.format(lockedBalance * marketPrice) +
                            " " + preferredTradeCurrency;
                } catch (NullPointerException | NumberFormatException e) {
                    // Either the balance or market price is not available yet
                }
                return new Tooltip(tooltipText);
            }
        });

        // add spacer to center the nav buttons when window is small
        Region rightSpacer = new Region();
        HBox.setHgrow(rightSpacer, Priority.ALWAYS);

        HBox primaryNav = new HBox(getLogoPane(), marketButton, getNavigationSpacer(), buyButton, getNavigationSpacer(),
                sellButton, getNavigationSpacer(), portfolioButtonWithBadge, getNavigationSpacer(), fundsButton, rightSpacer);

        primaryNav.setAlignment(Pos.CENTER_LEFT);
        primaryNav.getStyleClass().add("nav-primary");
        HBox.setHgrow(primaryNav, Priority.SOMETIMES);

        HBox priceAndBalance = new HBox(marketPriceBox.second, getNavigationSeparator(), availableBalanceBox.second,
                getNavigationSeparator(), pendingBalanceBox.second, getNavigationSeparator(), reservedBalanceBox.second);

        priceAndBalance.setAlignment(Pos.CENTER);
        priceAndBalance.setSpacing(12);
        priceAndBalance.getStyleClass().add("nav-price-balance");

        HBox navPane = new HBox(primaryNav, priceAndBalance) {{
            setLeftAnchor(this, 25d);
            setRightAnchor(this, 25d);
            setTopAnchor(this, 20d);
            setPadding(new Insets(0, 0, 0, 0));
            getStyleClass().add("top-navigation");
        }};
        navPane.setAlignment(Pos.CENTER);

        HBox secondaryNav = new HBox(supportButtonWithBadge, accountButton, settingsButtonWithBadge);
        secondaryNav.getStyleClass().add("nav-secondary");
        secondaryNav.setAlignment(Pos.CENTER_RIGHT);
        secondaryNav.setPickOnBounds(false);
        HBox.setHgrow(secondaryNav, Priority.ALWAYS);
        AnchorPane.setLeftAnchor(secondaryNav, 0.0);
        AnchorPane.setRightAnchor(secondaryNav, 0.0);
        AnchorPane.setTopAnchor(secondaryNav, 0.0);

        AnchorPane secondaryNavContainer = new AnchorPane() {{
            setId("nav-secondary-container");
            setLeftAnchor(this, 0d);
            setRightAnchor(this, 0d);
            setTopAnchor(this, 94d);
        }};
        secondaryNavContainer.setPickOnBounds(false);
        secondaryNavContainer.getChildren().add(secondaryNav);

        AnchorPane contentContainer = new AnchorPane() {{
            getStyleClass().add("content-pane");
            setLeftAnchor(this, 0d);
            setRightAnchor(this, 0d);
            setTopAnchor(this, 95d);
            setBottomAnchor(this, 0d);
        }};

        AnchorPane applicationContainer = new AnchorPane(navPane, contentContainer, secondaryNavContainer) {{
            setId("application-container");
        }};

        BorderPane baseApplicationContainer = new BorderPane(applicationContainer) {{
            setId("base-content-container");
        }};
        baseApplicationContainer.setBottom(createFooter());

        setupBadge(portfolioButtonWithBadge, model.getNumPendingTrades(), model.getShowPendingTradesNotification());
        setupBadge(supportButtonWithBadge, model.getNumOpenSupportTickets(), model.getShowOpenSupportTicketsNotification());
        setupBadge(settingsButtonWithBadge, new SimpleStringProperty(Res.get("shared.new")), new SimpleBooleanProperty(false));
        settingsButtonWithBadge.getStyleClass().add("new");

        navigation.addListener((viewPath, data) -> {
            UserThread.await(() -> { // TODO: this uses `await` to fix nagivation link from market view to offer book, but await can cause hanging, so execute should be used
                if (viewPath.size() != 2 || viewPath.indexOf(MainView.class) != 0) return;

                Class<? extends View> viewClass = viewPath.tip();
                View view = viewLoader.load(viewClass);
                contentContainer.getChildren().setAll(view.getRoot());

                try {
                    navButtons.getToggles().stream()
                            .filter(toggle -> toggle instanceof NavButton)
                            .filter(button -> viewClass == ((NavButton) button).viewClass)
                            .findFirst()
                            .orElseThrow(() -> new HavenoException("No button matching %s found", viewClass))
                            .setSelected(true);
                } catch (HavenoException e) {
                    navigation.navigateTo(MainView.class, MarketView.class, OfferBookChartView.class);
                }
            });
        });

        VBox splashScreen = createSplashScreen();

        root.getChildren().addAll(baseApplicationContainer, splashScreen);

        model.getShowAppScreen().addListener((ov, oldValue, newValue) -> {
            if (newValue) {

                navigation.navigateToPreviousVisitedView();

                transitions.fadeOutAndRemove(splashScreen, 1500, actionEvent -> disposeSplashScreen());
            }
        });

        // Delay a bit to give time for rendering the splash screen
        UserThread.execute(() -> onApplicationStartedHandler.run());
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Helpers
    ///////////////////////////////////////////////////////////////////////////////////////////

    @NotNull
    private Separator getNavigationSeparator() {
        final Separator separator = new Separator(Orientation.VERTICAL);
        HBox.setHgrow(separator, Priority.ALWAYS);
        separator.setMaxWidth(Double.MAX_VALUE);
        separator.getStyleClass().add("nav-separator");
        return separator;
    }

    @NotNull
    private Pane getLogoPane() {
        ImageView logo = new ImageView();
        logo.setId("image-logo-landscape");
        logo.setPreserveRatio(true);
        logo.setFitHeight(40);
        logo.setSmooth(true);
        logo.setCache(true);

        final Pane pane = new Pane();
        HBox.setHgrow(pane, Priority.ALWAYS);
        pane.getStyleClass().add("nav-logo");
        pane.getChildren().add(logo);
        return pane;
    }

    @NotNull
    private Region getNavigationSpacer() {
        final Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        spacer.getStyleClass().add("nav-spacer");
        return spacer;
    }

    private Tuple2<Label, VBox> getBalanceBox(String text) {
        Label balanceDisplay = new Label();
        balanceDisplay.getStyleClass().add("nav-balance-display");

        Label label = new Label(text);
        label.getStyleClass().add("nav-balance-label");
        label.maxWidthProperty().bind(balanceDisplay.widthProperty());
        label.setPadding(new Insets(0, 0, 0, 0));
        VBox vBox = new VBox();
        vBox.setAlignment(Pos.CENTER_LEFT);
        vBox.getChildren().addAll(balanceDisplay, label);
        return new Tuple2<>(balanceDisplay, vBox);
    }

    private ListCell<PriceFeedComboBoxItem> getPriceFeedComboBoxListCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(PriceFeedComboBoxItem item, boolean empty) {
                super.updateItem(item, empty);
                if (!empty && item != null) {
                    textProperty().bind(item.displayStringProperty);
                } else {
                    textProperty().unbind();
                }
            }
        };
    }

    private Tuple2<ComboBox<PriceFeedComboBoxItem>, VBox> getMarketPriceBox() {

        VBox marketPriceBox = new VBox();
        marketPriceBox.setAlignment(Pos.CENTER_LEFT);

        ComboBox<PriceFeedComboBoxItem> priceComboBox = new JFXComboBox<>();
        priceComboBox.setVisibleRowCount(12);
        priceComboBox.setFocusTraversable(false);
        priceComboBox.setId("price-feed-combo");
        priceComboBox.setCellFactory(p -> getPriceFeedComboBoxListCell());
        ListCell<PriceFeedComboBoxItem> buttonCell = getPriceFeedComboBoxListCell();
        buttonCell.setId("price-feed-combo");
        priceComboBox.setButtonCell(buttonCell);

        Label marketPriceLabel = new Label();

        updateMarketPriceLabel(marketPriceLabel);

        marketPriceLabel.getStyleClass().add("nav-balance-label");

        marketPriceBox.getChildren().addAll(priceComboBox, marketPriceLabel);

        model.getMarketPriceUpdated().addListener((observable, oldValue, newValue) ->
                updateMarketPriceLabel(marketPriceLabel));

        return new Tuple2<>(priceComboBox, marketPriceBox);
    }

    @NotNull
    private String getPriceProvider() {
        return model.getIsFiatCurrencyPriceFeedSelected().get() ? "BitcoinAverage" : "Poloniex";
    }

    private void updateMarketPriceLabel(Label label) {
        if (model.getIsPriceAvailable().get()) {
            if (model.getIsExternallyProvidedPrice().get()) {
                label.setText(Res.get("mainView.marketPriceWithProvider.label", "Haveno Price Index"));
                label.setTooltip(new Tooltip(getPriceProviderTooltipString()));
            } else {
                label.setText(Res.get("mainView.marketPrice.havenoInternalPrice"));
                final Tooltip tooltip = new Tooltip(Res.get("mainView.marketPrice.tooltip.havenoInternalPrice"));
                label.setTooltip(tooltip);
            }
        } else {
            label.setText("");
            label.setTooltip(null);
        }
    }

    @NotNull
    private String getPriceProviderTooltipString() {

        String selectedCurrencyCode = model.getPriceFeedService().getCurrencyCode();
        MarketPrice selectedMarketPrice = model.getPriceFeedService().getMarketPrice(selectedCurrencyCode);

        return Res.get("mainView.marketPrice.tooltip",
                "Haveno Price Index for " + selectedCurrencyCode,
                "",
                selectedMarketPrice != null ? DisplayUtils.formatTime(new Date(selectedMarketPrice.getTimestampSec())) : Res.get("shared.na"),
                model.getPriceFeedService().getProviderNodeAddress());
    }

    private VBox createSplashScreen() {
        VBox vBox = new VBox();
        vBox.setAlignment(Pos.CENTER);
        vBox.setSpacing(10);
        vBox.setId("splash");

        ImageView logo = new ImageView();
        logo.setId(Config.baseCurrencyNetwork() == BaseCurrencyNetwork.XMR_MAINNET ? "image-logo-splash" : "image-logo-splash-testnet");
        logo.setFitWidth(400);
        logo.setPreserveRatio(true);
        logo.setSmooth(true);

        // createBitcoinInfoBox
        xmrSplashInfo = new AutoTooltipLabel();
        xmrSplashInfo.textProperty().bind(model.getXmrInfo());
        walletServiceErrorMsgListener = (ov, oldValue, newValue) -> {
            xmrSplashInfo.setId("splash-error-state-msg");
            xmrSplashInfo.getStyleClass().add("error-text");
        };
        model.getConnectionServiceErrorMsg().addListener(walletServiceErrorMsgListener);

        xmrSyncIndicator = new ProgressBar();
        xmrSyncIndicator.setPrefWidth(305);
        xmrSyncIndicator.progressProperty().bind(model.getCombinedSyncProgress());

        ImageView xmrSyncIcon = new ImageView();
        xmrSyncIcon.setVisible(false);
        xmrSyncIcon.setManaged(false);

        xmrSyncIconIdListener = (ov, oldValue, newValue) -> {
            xmrSyncIcon.setId(newValue);
            xmrSyncIcon.setVisible(true);
            xmrSyncIcon.setManaged(true);

            // show progress bar until we have checkmark id
            boolean inProgress = "".equals(newValue);
            xmrSyncIndicator.setVisible(inProgress);
            xmrSyncIndicator.setManaged(inProgress);
        };
        model.getXmrSplashSyncIconId().addListener(xmrSyncIconIdListener);


        HBox blockchainSyncBox = new HBox();
        blockchainSyncBox.setSpacing(10);
        blockchainSyncBox.setAlignment(Pos.CENTER);
        blockchainSyncBox.setPadding(new Insets(40, 0, 0, 0));
        blockchainSyncBox.setPrefHeight(50);
        blockchainSyncBox.getChildren().addAll(xmrSplashInfo, xmrSyncIcon);


        // create P2PNetworkBox
        splashP2PNetworkLabel = new AutoTooltipLabel();
        splashP2PNetworkLabel.setWrapText(true);
        splashP2PNetworkLabel.setMaxWidth(700);
        splashP2PNetworkLabel.setTextAlignment(TextAlignment.CENTER);
        splashP2PNetworkLabel.getStyleClass().add("sub-info");
        splashP2PNetworkLabel.textProperty().bind(model.getP2PNetworkInfo());

        Button showTorNetworkSettingsButton = new AutoTooltipButton(Res.get("settings.net.openTorSettingsButton"));
        showTorNetworkSettingsButton.setVisible(false);
        showTorNetworkSettingsButton.setManaged(false);
        showTorNetworkSettingsButton.setOnAction(e -> model.getTorNetworkSettingsWindow().show());

        splashP2PNetworkBusyAnimation = new BusyAnimation(false);

        splashP2PNetworkErrorMsgListener = (ov, oldValue, newValue) -> {
            if (newValue != null) {
                splashP2PNetworkLabel.setId("splash-error-state-msg");
                splashP2PNetworkLabel.getStyleClass().remove("sub-info");
                splashP2PNetworkLabel.getStyleClass().add("error-text");
                splashP2PNetworkBusyAnimation.setDisable(true);
                splashP2PNetworkBusyAnimation.stop();
                showTorNetworkSettingsButton.setVisible(true);
                showTorNetworkSettingsButton.setManaged(true);
                if (model.getUseTorForXmr().get().isUseTorForXmr()) {
                    // If using tor for XMR, hide the XMR status since tor is not working
                    xmrSyncIndicator.setVisible(false);
                    xmrSplashInfo.setVisible(false);
                }
            } else if (model.getSplashP2PNetworkAnimationVisible().get()) {
                splashP2PNetworkBusyAnimation.setDisable(false);
                splashP2PNetworkBusyAnimation.play();
            }
        };
        model.getP2pNetworkWarnMsg().addListener(splashP2PNetworkErrorMsgListener);

        ImageView splashP2PNetworkIcon = new ImageView();
        splashP2PNetworkIcon.setId("image-connection-tor");
        splashP2PNetworkIcon.setFitWidth(networkIconSize);
        splashP2PNetworkIcon.setFitHeight(networkIconSize);
        splashP2PNetworkIcon.setVisible(false);
        splashP2PNetworkIcon.setManaged(false);
        HBox.setMargin(splashP2PNetworkIcon, new Insets(0, 0, 0, 0));
        splashP2PNetworkIcon.setOnMouseClicked(e -> {
            torNetworkSettingsWindow.show();
        });

        Timer showTorNetworkSettingsTimer = UserThread.runAfter(() -> {
            showTorNetworkSettingsButton.setVisible(true);
            showTorNetworkSettingsButton.setManaged(true);
        }, SHOW_TOR_SETTINGS_DELAY_SEC);

        splashP2PNetworkIconIdListener = (ov, oldValue, newValue) -> {
            splashP2PNetworkIcon.setId(newValue);
            splashP2PNetworkIcon.setVisible(true);
            splashP2PNetworkIcon.setManaged(true);
            splashP2PNetworkIcon.setFitWidth(networkIconSize);
            splashP2PNetworkIcon.setFitHeight(networkIconSize);

            // if we can connect in 10 sec. we know that tor is working
            showTorNetworkSettingsTimer.stop();
        };
        model.getP2PNetworkIconId().addListener(splashP2PNetworkIconIdListener);

        splashP2PNetworkVisibleListener = (ov, oldValue, newValue) -> {
            splashP2PNetworkBusyAnimation.setDisable(!newValue);
            if (newValue) splashP2PNetworkBusyAnimation.play();
        };

        model.getSplashP2PNetworkAnimationVisible().addListener(splashP2PNetworkVisibleListener);

        HBox splashP2PNetworkBox = new HBox();
        splashP2PNetworkBox.setSpacing(10);
        splashP2PNetworkBox.setAlignment(Pos.CENTER);
        splashP2PNetworkBox.setPrefHeight(40);
        splashP2PNetworkBox.getChildren().addAll(splashP2PNetworkLabel, splashP2PNetworkBusyAnimation, splashP2PNetworkIcon, showTorNetworkSettingsButton);

        Label versionLabel = new Label("v" + Version.VERSION);

        vBox.getChildren().addAll(logo, blockchainSyncBox, xmrSyncIndicator, splashP2PNetworkBox, versionLabel);
        return vBox;
    }

    private void disposeSplashScreen() {
        model.getConnectionServiceErrorMsg().removeListener(walletServiceErrorMsgListener);
        model.getXmrSplashSyncIconId().removeListener(xmrSyncIconIdListener);

        model.getP2pNetworkWarnMsg().removeListener(splashP2PNetworkErrorMsgListener);
        model.getP2PNetworkIconId().removeListener(splashP2PNetworkIconIdListener);
        model.getSplashP2PNetworkAnimationVisible().removeListener(splashP2PNetworkVisibleListener);

        xmrSplashInfo.textProperty().unbind();
        xmrSyncIndicator.progressProperty().unbind();

        splashP2PNetworkLabel.textProperty().unbind();

        model.onSplashScreenRemoved();
    }


    private AnchorPane createFooter() {
        // line
        Separator separator = new Separator();
        separator.setId("footer-pane-line");
        separator.setPrefHeight(1);
        setLeftAnchor(separator, 0d);
        setRightAnchor(separator, 0d);
        setTopAnchor(separator, 0d);

        // XMR
        Label xmrInfoLabel = new AutoTooltipLabel();
        xmrInfoLabel.setId("footer-pane");
        xmrInfoLabel.textProperty().bind(model.getXmrInfo());
        setLeftAnchor(xmrInfoLabel, 10d);
        setBottomAnchor(xmrInfoLabel, 7d);

        // temporarily disabled due to high CPU usage (per issue #4649)
        //ProgressBar blockchainSyncIndicator = new JFXProgressBar(-1);
        //blockchainSyncIndicator.setPrefWidth(80);
        //blockchainSyncIndicator.setMaxHeight(10);
        //blockchainSyncIndicator.progressProperty().bind(model.getCombinedSyncProgress());

        model.getConnectionServiceErrorMsg().addListener((ov, oldValue, newValue) -> {
            if (newValue != null) {
                xmrInfoLabel.setId("splash-error-state-msg");
                xmrInfoLabel.getStyleClass().add("error-text");
                xmrNetworkWarnMsgPopup = new Popup().warning(newValue);
                xmrNetworkWarnMsgPopup.show();
            } else {
                xmrInfoLabel.setId("footer-pane");
                xmrInfoLabel.getStyleClass().remove("error-text");
                if (xmrNetworkWarnMsgPopup != null)
                    xmrNetworkWarnMsgPopup.hide();
            }
        });

        model.getTopErrorMsg().addListener((ov, oldValue, newValue) -> {
            log.warn("Top level warning: " + newValue);
            if (newValue != null) {
                new Popup().warning(newValue).show();
            }
        });

        // temporarily disabled due to high CPU usage (per issue #4649)
        //model.getCombinedSyncProgress().addListener((ov, oldValue, newValue) -> {
        //    if ((double) newValue >= 1) {
        //        blockchainSyncIndicator.setVisible(false);
        //        blockchainSyncIndicator.setManaged(false);
        //    }
        //});

        // version
        Label versionLabel = new AutoTooltipLabel();
        versionLabel.setId("footer-pane");
        versionLabel.setTextAlignment(TextAlignment.CENTER);
        versionLabel.setAlignment(Pos.BASELINE_CENTER);
        versionLabel.textProperty().bind(model.getCombinedFooterInfo());
        root.widthProperty().addListener((ov, oldValue, newValue) ->
                versionLabel.setLayoutX(((double) newValue - versionLabel.getWidth()) / 2));
        model.getNewVersionAvailableProperty().addListener((observable, oldValue, newValue) -> {
            versionLabel.getStyleClass().removeAll("version-new", "version");
            if (newValue) {
                versionLabel.getStyleClass().add("version-new");
                versionLabel.setOnMouseClicked(e -> model.onOpenDownloadWindow());
            } else {
                versionLabel.getStyleClass().add("version");
                versionLabel.setOnMouseClicked(null);
            }
        });
        HBox versionBox = new HBox();
        versionBox.setSpacing(10);
        versionBox.setAlignment(Pos.CENTER);
        versionBox.setAlignment(Pos.BASELINE_CENTER);
        versionBox.getChildren().addAll(versionLabel); //blockchainSyncIndicator removed per issue #4649
        setLeftAnchor(versionBox, 10d);
        setRightAnchor(versionBox, 10d);
        setBottomAnchor(versionBox, 7d);

        // Dark mode toggle
        ImageView useDarkModeIcon = new ImageView();
        useDarkModeIcon.setId(preferences.getCssTheme() == 1 ? "image-dark-mode-toggle" : "image-light-mode-toggle");
        useDarkModeIcon.setFitHeight(networkIconSize);
        useDarkModeIcon.setPreserveRatio(true);
        useDarkModeIcon.setPickOnBounds(true);
        useDarkModeIcon.setCursor(Cursor.HAND);
        setRightAnchor(useDarkModeIcon, 8d);
        setBottomAnchor(useDarkModeIcon, 6d);
        Tooltip modeToolTip = new Tooltip();
        Tooltip.install(useDarkModeIcon, modeToolTip);
        useDarkModeIcon.setOnMouseEntered(e -> modeToolTip.setText(Res.get(preferences.getCssTheme() == 1 ? "setting.preferences.useLightMode" : "setting.preferences.useDarkMode")));
        useDarkModeIcon.setOnMouseClicked(e -> {
            preferences.setCssTheme(preferences.getCssTheme() != 1);
        });
        preferences.getCssThemeProperty().addListener((observable, oldValue, newValue) -> {
            useDarkModeIcon.setId(preferences.getCssTheme() == 1 ? "image-dark-mode-toggle" : "image-light-mode-toggle");
        });

        // P2P Network
        Label p2PNetworkLabel = new AutoTooltipLabel();
        p2PNetworkLabel.setId("footer-pane");
        p2PNetworkLabel.textProperty().bind(model.getP2PNetworkInfo());

        double networkIconRightAnchor = 54d;
        ImageView p2PNetworkIcon = new ImageView();
        setRightAnchor(p2PNetworkIcon, networkIconRightAnchor);
        setBottomAnchor(p2PNetworkIcon, 6d);
        p2PNetworkIcon.setPickOnBounds(true);
        p2PNetworkIcon.setCursor(Cursor.HAND);
        p2PNetworkIcon.setOpacity(0.4);
        p2PNetworkIcon.setFitWidth(networkIconSize);
        p2PNetworkIcon.setFitHeight(networkIconSize);
        p2PNetworkIcon.idProperty().bind(model.getP2PNetworkIconId());
        p2PNetworkLabel.idProperty().bind(model.getP2pNetworkLabelId());
        model.getP2pNetworkWarnMsg().addListener((ov, oldValue, newValue) -> {
            if (newValue != null) {
                p2PNetworkWarnMsgPopup = new Popup().warning(newValue);
                p2PNetworkWarnMsgPopup.show();
            } else if (p2PNetworkWarnMsgPopup != null) {
                p2PNetworkWarnMsgPopup.hide();
            }
        });
        p2PNetworkIcon.setOnMouseClicked(e -> {
            torNetworkSettingsWindow.show();
        });

        ImageView p2PNetworkStatusIcon = new ImageView();
        p2PNetworkStatusIcon.setPickOnBounds(true);
        p2PNetworkStatusIcon.setCursor(Cursor.HAND);
        p2PNetworkStatusIcon.setFitWidth(networkIconSize);
        p2PNetworkStatusIcon.setFitHeight(networkIconSize);
        setRightAnchor(p2PNetworkStatusIcon, networkIconRightAnchor + 22);
        setBottomAnchor(p2PNetworkStatusIcon, 6d);
        Tooltip p2pNetworkStatusToolTip = new Tooltip();
        Tooltip.install(p2PNetworkStatusIcon, p2pNetworkStatusToolTip);
        p2PNetworkStatusIcon.setOnMouseEntered(e -> p2pNetworkStatusToolTip.setText(model.getP2pConnectionSummary()));
        Timeline flasher = new Timeline(
                new KeyFrame(Duration.seconds(0.5), e -> p2PNetworkStatusIcon.setOpacity(0.2)),
                new KeyFrame(Duration.seconds(1.0), e -> p2PNetworkStatusIcon.setOpacity(1))
        );
        flasher.setCycleCount(Animation.INDEFINITE);
        model.getP2PNetworkStatusIconId().addListener((ov, oldValue, newValue) -> {
            if (newValue.equalsIgnoreCase("flashing:image-yellow_circle")) {
                p2PNetworkStatusIcon.setId("image-yellow_circle");
                flasher.play();
            } else {
                p2PNetworkStatusIcon.setId(newValue);
                flasher.stop();
                p2PNetworkStatusIcon.setOpacity(1);
            }
        });
        p2PNetworkStatusIcon.setOnMouseClicked(e -> {
            if (p2PNetworkStatusIcon.getId().equalsIgnoreCase("image-alert-round")) {
                new Popup().warning(Res.get("popup.info.p2pStatusIndicator.red", model.getP2pConnectionSummary())).show();
            } else if (p2PNetworkStatusIcon.getId().equalsIgnoreCase("image-yellow_circle")) {
                new Popup().information(Res.get("popup.info.p2pStatusIndicator.yellow", model.getP2pConnectionSummary())).show();
            } else {
                new Popup().information(Res.get("popup.info.p2pStatusIndicator.green", model.getP2pConnectionSummary())).show();
            }
        });

        model.getUpdatedDataReceived().addListener((observable, oldValue, newValue) -> UserThread.execute(() -> {
            p2PNetworkIcon.setOpacity(1);
        }));

        VBox vBox = new VBox();
        vBox.setAlignment(Pos.CENTER_RIGHT);
        vBox.getChildren().addAll(p2PNetworkLabel);
        setRightAnchor(vBox, networkIconRightAnchor + 45);
        setBottomAnchor(vBox, 7d);

        return new AnchorPane(separator, xmrInfoLabel, versionBox, vBox, p2PNetworkStatusIcon, p2PNetworkIcon, useDarkModeIcon) {{
            setId("footer-pane");
            setMinHeight(30);
            setMaxHeight(30);
        }};
    }

    private void setupBadge(JFXBadge buttonWithBadge, StringProperty badgeNumber, BooleanProperty badgeEnabled) {
        buttonWithBadge.textProperty().bind(badgeNumber);
        buttonWithBadge.setEnabled(badgeEnabled.get());
        badgeEnabled.addListener((observable, oldValue, newValue) -> UserThread.execute(() -> {
            buttonWithBadge.setEnabled(newValue);
            buttonWithBadge.refreshBadge();
        }));

        buttonWithBadge.setPosition(Pos.TOP_RIGHT);
        buttonWithBadge.setMinHeight(34);
        buttonWithBadge.setMaxHeight(34);
    }

    private class NavButton extends AutoTooltipToggleButton {

        private final Class<? extends View> viewClass;

        NavButton(Class<? extends View> viewClass, String title) {
            super(title);

            this.viewClass = viewClass;

            this.setToggleGroup(navButtons);
            this.getStyleClass().add("nav-button");
            this.setMinWidth(Region.USE_PREF_SIZE); // prevent squashing content
            this.setPrefWidth(Region.USE_COMPUTED_SIZE);

            // Japanese fonts are dense, increase top nav button text size
            if (model.getPreferences() != null && "ja".equals(model.getPreferences().getUserLanguage())) {
                this.getStyleClass().add("nav-button-japanese");
            }

            this.selectedProperty().addListener((ov, oldValue, newValue) -> this.setMouseTransparent(newValue));

            this.setOnAction(e -> navigation.navigateTo(MainView.class, viewClass));
        }

    }

    private class SecondaryNavButton extends NavButton {

        SecondaryNavButton(Class<? extends View> viewClass, String title, String iconId) {
            super(viewClass, title);
            this.getStyleClass().setAll("nav-secondary-button");

            // Japanese fonts are dense, increase top nav button text size
            if (model.getPreferences() != null && "ja".equals(model.getPreferences().getUserLanguage())) {
                this.getStyleClass().setAll("nav-secondary-button-japanese");
            }

            // add icon
            ImageView imageView = new ImageView();
            imageView.setId(iconId);
            imageView.setFitWidth(15);
            imageView.setPreserveRatio(true);
            setGraphicTextGap(10);
            setGraphic(imageView);

            // show cursor hand on any hover
            this.setPickOnBounds(true);
        }

    }
}
