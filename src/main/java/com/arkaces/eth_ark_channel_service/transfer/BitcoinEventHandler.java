package com.arkaces.eth_ark_channel_service.transfer;

import ark_java_client.ArkClient;
import com.arkaces.aces_server.common.identifer.IdentifierGenerator;
import com.arkaces.eth_ark_channel_service.FeeSettings;
import com.arkaces.eth_ark_channel_service.ServiceArkAccountSettings;
import com.arkaces.eth_ark_channel_service.ark.ArkSatoshiService;
import com.arkaces.eth_ark_channel_service.bitcoin_rpc.BitcoinService;
import com.arkaces.eth_ark_channel_service.contract.ContractEntity;
import com.arkaces.eth_ark_channel_service.contract.ContractRepository;
import com.arkaces.eth_ark_channel_service.exchange_rate.ExchangeRateService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

@RestController
@Slf4j
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
public class BitcoinEventHandler {

    private final ContractRepository contractRepository;
    private final TransferRepository transferRepository;
    private final BitcoinService bitcoinService;
    private final IdentifierGenerator identifierGenerator;
    private final ExchangeRateService exchangeRateService;
    private final ArkClient arkClient;
    private final ArkSatoshiService arkSatoshiService;
    private final ServiceArkAccountSettings serviceArkAccountSettings;
    private final FeeSettings feeSettings;

    @PostMapping("/bitcoinEvents")
    public ResponseEntity<Void> handleBitcoinEvent(@RequestBody JsonNode event) {
        // todo: verify event post is signed by listener
        String btcTransactionId = event.get("transactionId").toString();
        
        log.info("Received Bitcoin event: " + btcTransactionId + " -> " + event.get("data"));
        
        String subscriptionId = event.get("subscriptionId").asText();
        ContractEntity contractEntity = contractRepository.findOneBySubscriptionId(subscriptionId);
        if (contractEntity != null) {
            // todo: lock contract for update to prevent concurrent processing of a listener transaction.
            // Listeners send events serially, so that shouldn't be an issue, but we might want to lock
            // to be safe.

            log.info("Matched event for contract id " + contractEntity.getId() + " btc transaction id " + btcTransactionId);

            String transferId = identifierGenerator.generate();

            TransferEntity transferEntity = new TransferEntity();
            transferEntity.setId(transferId);
            transferEntity.setCreatedAt(LocalDateTime.now());
            transferEntity.setBtcTransactionId(btcTransactionId);
            transferEntity.setContractEntity(contractEntity);

            // Get BTC amount from transaction
            JsonNode transaction = bitcoinService.getTransaction(btcTransactionId);
            BigDecimal btcAmount = transaction.get("amount").decimalValue();
            BigDecimal btcFee = transaction.get("fee").decimalValue(); // todo: is this fee included in amount?
            transferEntity.setBtcAmount(btcAmount);

            BigDecimal btcToArkRate = exchangeRateService.getRate("BTC", "ARK"); //2027.58, Ark 8, Btc 15000
            transferEntity.setBtcToArkRate(btcToArkRate);

            // Set fees
            transferEntity.setBtcFlatFee(feeSettings.getBtcFlatFee());
            transferEntity.setBtcPercentFee(feeSettings.getBtcPercentFee());

            BigDecimal percentFee = feeSettings.getBtcPercentFee()
                    .divide(new BigDecimal("100.00"), 8, BigDecimal.ROUND_HALF_UP);
            BigDecimal btcTotalFeeAmount = btcAmount.multiply(percentFee).add(feeSettings.getBtcFlatFee());
            transferEntity.setBtcTotalFee(btcTotalFeeAmount);

            // Calculate send ark amount
            BigDecimal arkSendAmount = BigDecimal.ZERO;
            if (btcAmount.compareTo(btcTotalFeeAmount) > 0) {
                arkSendAmount = btcAmount.multiply(btcToArkRate).setScale(8, RoundingMode.HALF_DOWN);
            }
            transferEntity.setArkSendAmount(arkSendAmount);

            transferEntity.setStatus(TransferStatus.NEW);
            transferRepository.save(transferEntity);

            // Send ark transaction
            Long arkSendSatoshis = arkSatoshiService.toSatoshi(arkSendAmount);
            String arkTransactionId = arkClient.broadcastTransaction(
                contractEntity.getRecipientArkAddress(),
                arkSendSatoshis,
                null,
                serviceArkAccountSettings.getPassphrase()
            );
            transferEntity.setArkTransactionId(arkTransactionId);

            log.info("Sent " + arkSendAmount + " ark to " + contractEntity.getRecipientArkAddress()
                + ", ark transaction id " + arkTransactionId + ", btc transaction " + btcTransactionId);

            transferEntity.setStatus(TransferStatus.COMPLETE);
            transferRepository.save(transferEntity);
            
            log.info("Saved transfer id " + transferEntity.getId() + " to contract " + contractEntity.getId());
        }
        
        return ResponseEntity.ok().build();
    }
}