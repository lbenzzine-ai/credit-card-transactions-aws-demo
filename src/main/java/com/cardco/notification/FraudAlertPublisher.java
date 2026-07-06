package com.cardco.notification;

import com.cardco.model.CardTransaction;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;

import java.util.Map;

public class FraudAlertPublisher {

    private final SnsClient sns;
    private final String topicArn;

    public FraudAlertPublisher(SnsClient sns, String topicArn) {
        this.sns = sns;
        this.topicArn = topicArn;
    }

    public void publishHighRiskAlert(CardTransaction txn, double riskScore) {
        String message = """
                {"transactionId":"%s","merchantId":"%s","maskedPan":"%s","amount":%s,"riskScore":%.2f}"""
                .formatted(txn.getTransactionId(), txn.getMerchantId(),
                        txn.getMaskedPan(), txn.getAmount(), riskScore);

        PublishRequest request = PublishRequest.builder()
                .topicArn(topicArn)
                .message(message)
                .subject("High-risk transaction flagged")
                .messageAttributes(Map.of(
                        "severity", MessageAttributeValue.builder()
                                .dataType("String")
                                .stringValue(riskScore > 0.85 ? "CRITICAL" : "WARNING")
                                .build()))
                .build();

        sns.publish(request);
    }
}
