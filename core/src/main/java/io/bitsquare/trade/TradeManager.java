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

package io.bitsquare.trade;

import io.bitsquare.account.AccountSettings;
import io.bitsquare.bank.BankAccount;
import io.bitsquare.btc.BlockChainService;
import io.bitsquare.btc.WalletService;
import io.bitsquare.crypto.SignatureService;
import io.bitsquare.network.Message;
import io.bitsquare.network.Peer;
import io.bitsquare.offer.Direction;
import io.bitsquare.offer.Offer;
import io.bitsquare.offer.OfferBookService;
import io.bitsquare.offer.OpenOffer;
import io.bitsquare.persistence.Persistence;
import io.bitsquare.trade.handlers.MessageHandler;
import io.bitsquare.trade.handlers.TransactionResultHandler;
import io.bitsquare.trade.listeners.SendMessageListener;
import io.bitsquare.trade.protocol.availability.CheckOfferAvailabilityModel;
import io.bitsquare.trade.protocol.availability.CheckOfferAvailabilityProtocol;
import io.bitsquare.trade.protocol.availability.messages.ReportOfferAvailabilityMessage;
import io.bitsquare.trade.protocol.availability.messages.RequestIsOfferAvailableMessage;
import io.bitsquare.trade.protocol.placeoffer.PlaceOfferModel;
import io.bitsquare.trade.protocol.placeoffer.PlaceOfferProtocol;
import io.bitsquare.trade.protocol.trade.offerer.BuyerAsOffererModel;
import io.bitsquare.trade.protocol.trade.offerer.BuyerAsOffererProtocol;
import io.bitsquare.trade.protocol.trade.taker.SellerAsTakerModel;
import io.bitsquare.trade.protocol.trade.taker.SellerAsTakerProtocol;
import io.bitsquare.user.User;
import io.bitsquare.util.handlers.ErrorMessageHandler;
import io.bitsquare.util.handlers.ResultHandler;

import org.bitcoinj.core.Coin;
import org.bitcoinj.utils.Fiat;

import java.util.HashMap;
import java.util.Map;

import javax.inject.Inject;

import javafx.collections.FXCollections;
import javafx.collections.ObservableMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;

public class TradeManager {
    private static final Logger log = LoggerFactory.getLogger(TradeManager.class);

    private final User user;
    private final AccountSettings accountSettings;
    private final Persistence persistence;
    private final TradeMessageService tradeMessageService;
    private final BlockChainService blockChainService;
    private final WalletService walletService;
    private final SignatureService signatureService;
    private final OfferBookService offerBookService;

    private final Map<String, SellerAsTakerProtocol> sellerAsTakerProtocolMap = new HashMap<>();
    private final Map<String, BuyerAsOffererProtocol> buyerAcceptsOfferProtocolMap = new HashMap<>();
    private final Map<String, CheckOfferAvailabilityProtocol> checkOfferAvailabilityProtocolMap = new HashMap<>();

    private final ObservableMap<String, OpenOffer> openOffers = FXCollections.observableHashMap();
    private final ObservableMap<String, Trade> pendingTrades = FXCollections.observableHashMap();
    private final ObservableMap<String, Trade> closedTrades = FXCollections.observableHashMap();
    private final MessageHandler messageHandler;

    private Trade currentPendingTrade;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public TradeManager(User user, AccountSettings accountSettings, Persistence persistence,
                        TradeMessageService tradeMessageService, BlockChainService blockChainService,
                        WalletService walletService, SignatureService signatureService,
                        OfferBookService offerBookService) {
        this.user = user;
        this.accountSettings = accountSettings;
        this.persistence = persistence;
        this.tradeMessageService = tradeMessageService;
        this.blockChainService = blockChainService;
        this.walletService = walletService;
        this.signatureService = signatureService;
        this.offerBookService = offerBookService;

        Object openOffersObject = persistence.read(this, "openOffers");
        if (openOffersObject instanceof Map) {
            openOffers.putAll((Map<String, OpenOffer>) openOffersObject);
        }

        Object pendingTradesObject = persistence.read(this, "pendingTrades");
        if (pendingTradesObject instanceof Map) {
            pendingTrades.putAll((Map<String, Trade>) pendingTradesObject);
        }

        Object closedTradesObject = persistence.read(this, "closedTrades");
        if (closedTradesObject instanceof Map) {
            closedTrades.putAll((Map<String, Trade>) closedTradesObject);
        }
        messageHandler = this::handleMessage;

        tradeMessageService.addMessageHandler(messageHandler);
    }

    // When all services are initialized we create the protocols for our open offers (which will listen for take offer requests)
    public void onAllServicesInitialized() {
        for (Map.Entry<String, OpenOffer> entry : openOffers.entrySet()) {
            createBuyerAcceptsOfferProtocol(entry.getValue());
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Called from UI
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void checkOfferAvailability(Offer offer) {
        if (!checkOfferAvailabilityProtocolMap.containsKey(offer.getId())) {

            CheckOfferAvailabilityModel model = new CheckOfferAvailabilityModel(
                    offer,
                    tradeMessageService,
                    () -> disposeCheckOfferAvailabilityRequest(offer));

            CheckOfferAvailabilityProtocol protocol = new CheckOfferAvailabilityProtocol(model);
            checkOfferAvailabilityProtocolMap.put(offer.getId(), protocol);
            protocol.checkOfferAvailability();
        }
        else {
            log.error("That should never happen: onCheckOfferAvailability already called for offer with ID:" + offer.getId());
        }
    }

    // When closing take offer view, we are not interested in the onCheckOfferAvailability result anymore, so remove from the map
    public void cancelGetOfferAvailableStateRequest(Offer offer) {
        disposeCheckOfferAvailabilityRequest(offer);
    }

    public void placeOffer(String id,
                           Direction direction,
                           Fiat price,
                           Coin amount,
                           Coin minAmount,
                           TransactionResultHandler resultHandler,
                           ErrorMessageHandler errorMessageHandler) {

        BankAccount currentBankAccount = user.getCurrentBankAccount().get();
        Offer offer = new Offer(id,
                user.getMessagePublicKey(),
                direction,
                price.getValue(),
                amount,
                minAmount,
                currentBankAccount.getBankAccountType(),
                currentBankAccount.getCurrency(),
                currentBankAccount.getCountry(),
                currentBankAccount.getUid(),
                accountSettings.getAcceptedArbitrators(),
                accountSettings.getSecurityDeposit(),
                accountSettings.getAcceptedCountries(),
                accountSettings.getAcceptedLanguageLocales());

        PlaceOfferModel model = new PlaceOfferModel(offer, walletService, offerBookService);

        PlaceOfferProtocol placeOfferProtocol = new PlaceOfferProtocol(
                model,
                (transaction) -> {
                    OpenOffer openOffer = createOpenOffer(offer);
                    createBuyerAcceptsOfferProtocol(openOffer);
                    resultHandler.handleResult(transaction);
                },
                (message) -> {
                    errorMessageHandler.handleErrorMessage(message);
                }
        );

        placeOfferProtocol.placeOffer();
    }

    public void removeOpenOffer(Offer offer, ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        removeOpenOffer(offer, resultHandler, errorMessageHandler, true);
    }

    public Trade takeOffer(Coin amount, Offer offer) {
        Trade trade = createTrade(offer);
        trade.setTradeAmount(amount);

        // TODO check
        trade.stateProperty().addListener((ov, oldValue, newValue) -> {
            log.debug("trade state = " + newValue);
            switch (newValue) {
                case OPEN:
                    break;
                case OFFERER_ACCEPTED:
                case DEPOSIT_PUBLISHED:
                case DEPOSIT_CONFIRMED:
                case FIAT_PAYMENT_STARTED:
                case FIAT_PAYMENT_RECEIVED:
                case PAYOUT_PUBLISHED:
                    persistPendingTrades();
                    break;
                case OFFERER_REJECTED:
                case MESSAGE_SENDING_FAILED:
                    removeFailedTrade(trade);
                    break;
                default:
                    log.error("Unhandled trade state: " + newValue);
                    break;
            }
        });

        SellerAsTakerModel model = new SellerAsTakerModel(
                trade,
                tradeMessageService,
                walletService,
                blockChainService,
                signatureService,
                user);

        SellerAsTakerProtocol sellerTakesOfferProtocol = new SellerAsTakerProtocol(model);
        sellerAsTakerProtocolMap.put(trade.getId(), sellerTakesOfferProtocol);

        sellerTakesOfferProtocol.takeOffer();

        return trade;
    }

    public void onFiatPaymentStarted(String tradeId) {
        // TODO remove if check when peristence is impl.
        if (buyerAcceptsOfferProtocolMap.containsKey(tradeId)) {
            buyerAcceptsOfferProtocolMap.get(tradeId).onFiatPaymentStarted();
            persistPendingTrades();
        }
    }

    public void onFiatPaymentReceived(String tradeId) {
        sellerAsTakerProtocolMap.get(tradeId).onFiatPaymentReceived();
    }


    public void closeTrade(Trade trade) {
        closeTrade(trade, false);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Called from Offerbook when offer gets removed from DHT
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void onOfferRemovedFromRemoteOfferBook(Offer offer) {
        disposeCheckOfferAvailabilityRequest(offer);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Process new tradeMessages
    ///////////////////////////////////////////////////////////////////////////////////////////

    // Routes the incoming messages to the responsible protocol
    private void handleMessage(Message message, Peer sender) {
        if (message instanceof RequestIsOfferAvailableMessage) {
            String offerId = ((RequestIsOfferAvailableMessage) message).getOfferId();
            checkNotNull(offerId);

            ReportOfferAvailabilityMessage reportOfferAvailabilityMessage = new ReportOfferAvailabilityMessage(offerId, isOfferOpen(offerId));
            tradeMessageService.sendMessage(sender, reportOfferAvailabilityMessage, new SendMessageListener() {
                @Override
                public void handleResult() {
                    // Offerer does not do anything at that moment. Peer might only watch the offer and does nto start a trade.
                    log.trace("ReportOfferAvailabilityMessage successfully arrived at peer");
                }

                @Override
                public void handleFault() {
                    log.warn("Sending ReportOfferAvailabilityMessage failed.");
                }
            });
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    private OpenOffer createOpenOffer(Offer offer) {
        OpenOffer openOffer = new OpenOffer(offer);
        openOffers.put(openOffer.getId(), openOffer);
        persistOpenOffers();
        return openOffer;
    }

    private void removeOpenOffer(Offer offer,
                                 ResultHandler resultHandler,
                                 ErrorMessageHandler errorMessageHandler,
                                 boolean removeFromOffererAsBuyerProtocolMap) {
        String offerId = offer.getId();
        offerBookService.removeOffer(offer,
                () -> {
                    if (openOffers.containsKey(offerId)) {
                        OpenOffer openOffer = openOffers.remove(offerId);
                        disposeCheckOfferAvailabilityRequest(openOffer.getOffer());
                        persistOpenOffers();
                        if (removeFromOffererAsBuyerProtocolMap && buyerAcceptsOfferProtocolMap.containsKey(offerId)) {
                            buyerAcceptsOfferProtocolMap.get(offerId).cleanup();
                            buyerAcceptsOfferProtocolMap.remove(offerId);
                        }

                        resultHandler.handleResult();
                    }
                    else {
                        log.error("Locally stored offers does not contain the offer with the ID " + offerId);
                        errorMessageHandler.handleErrorMessage("Locally stored offers does not contain the offer with the ID " + offerId);
                    }
                },
                (message, throwable) -> errorMessageHandler.handleErrorMessage(message));
    }

    private Trade createTrade(Offer offer) {
        if (pendingTrades.containsKey(offer.getId()))
            log.error("That must never happen: Trades contains already an trade with the ID " + offer.getId());

        Trade trade = new Trade(offer);
        pendingTrades.put(offer.getId(), trade);
        persistPendingTrades();

        currentPendingTrade = trade;

        return trade;
    }

    private void createBuyerAcceptsOfferProtocol(OpenOffer openOffer) {
        BuyerAsOffererModel model = new BuyerAsOffererModel(
                openOffer,
                tradeMessageService,
                walletService,
                blockChainService,
                signatureService,
                user);

        if (pendingTrades.containsKey(openOffer.getId()))
            model.setTrade(pendingTrades.get(openOffer.getId()));

        openOffer.stateProperty().addListener((ov, oldValue, newValue) -> {
            log.debug("openOffer state = " + newValue);
            switch (newValue) {
                case OPEN:
                    break;
                case OFFER_ACCEPTED:
                    removeOpenOffer(openOffer.getOffer(),
                            () -> log.debug("remove offer was successful"),
                            (message) -> log.error(message),
                            false);

                    Trade trade = model.getTrade();
                    pendingTrades.put(trade.getId(), trade);
                    persistPendingTrades();
                    currentPendingTrade = trade;

                    // TODO check, remove listener
                    trade.stateProperty().addListener((ov2, oldValue2, newValue2) -> {
                        log.debug("trade state = " + newValue);
                        switch (newValue2) {
                            case OPEN:
                                break;
                            case OFFERER_ACCEPTED: // only taker side
                            case DEPOSIT_PUBLISHED:
                            case DEPOSIT_CONFIRMED:
                            case FIAT_PAYMENT_STARTED:
                            case FIAT_PAYMENT_RECEIVED:
                            case PAYOUT_PUBLISHED:
                                persistPendingTrades();
                                break;
                            case OFFERER_REJECTED:
                            case MESSAGE_SENDING_FAILED:
                                removeFailedTrade(trade);
                                buyerAcceptsOfferProtocolMap.get(trade.getId()).cleanup();
                                break;
                            default:
                                log.error("Unhandled trade state: " + newValue);
                                break;
                        }
                    });
                    break;
                default:
                    log.error("Unhandled openOffer state: " + newValue);
                    break;
            }
        });

        BuyerAsOffererProtocol buyerAcceptsOfferProtocol = new BuyerAsOffererProtocol(model);
        buyerAcceptsOfferProtocolMap.put(openOffer.getId(), buyerAcceptsOfferProtocol);
    }

    private void removeFailedTrade(Trade trade) {
        closeTrade(trade, true);
    }

    private void closeTrade(Trade trade, boolean failed) {
        if (!pendingTrades.containsKey(trade.getId()))
            log.error("That must never happen: trades does not contain the trade with the ID " + trade.getId());

        pendingTrades.remove(trade.getId());
        persistPendingTrades();

        if (sellerAsTakerProtocolMap.containsKey(trade.getId())) {
            sellerAsTakerProtocolMap.get(trade.getId()).cleanup();
            sellerAsTakerProtocolMap.remove(trade.getId());
        }
        else if (buyerAcceptsOfferProtocolMap.containsKey(trade.getId())) {
            buyerAcceptsOfferProtocolMap.get(trade.getId()).cleanup();
            buyerAcceptsOfferProtocolMap.remove(trade.getId());
        }

        if (!failed) {
            closedTrades.put(trade.getId(), trade);
            persistClosedTrades();
        }
        else {
            // TODO add failed trades to history
        }
    }

    private void disposeCheckOfferAvailabilityRequest(Offer offer) {
        if (checkOfferAvailabilityProtocolMap.containsKey(offer.getId())) {
            CheckOfferAvailabilityProtocol protocol = checkOfferAvailabilityProtocolMap.get(offer.getId());
            protocol.cancel();
            protocol.cleanup();
            checkOfferAvailabilityProtocolMap.remove(offer.getId());
        }
    }

    public boolean isOfferOpen(String offerId) {
        // Don't use openOffers as the offer gets removed async from DHT, but is added sync to pendingTrades
        return !pendingTrades.containsKey(offerId) && !closedTrades.containsKey(offerId);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Getters
    ///////////////////////////////////////////////////////////////////////////////////////////

    public ObservableMap<String, OpenOffer> getOpenOffers() {
        return openOffers;
    }

    public ObservableMap<String, Trade> getPendingTrades() {
        return pendingTrades;
    }

    public ObservableMap<String, Trade> getClosedTrades() {
        return closedTrades;
    }

    public Trade getCurrentPendingTrade() {
        return currentPendingTrade;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void persistOpenOffers() {
        persistence.write(this, "openOffers", (Map<String, OpenOffer>) new HashMap<>(openOffers));
    }

    private void persistPendingTrades() {
        persistence.write(this, "pendingTrades", (Map<String, Trade>) new HashMap<>(pendingTrades));
    }

    private void persistClosedTrades() {
        persistence.write(this, "closedTrades", (Map<String, Trade>) new HashMap<>(closedTrades));
    }

}