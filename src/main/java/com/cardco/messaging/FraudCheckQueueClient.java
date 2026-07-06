package com.cardco.messaging;

import com.cardco.model.CardTransaction;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.List;
import java.util.Map;

/**
 * Publishes authorized transactions for asynchronous fraud scoring, and
 * polls the same (standard) queue to simulate the downstream consumer in
 * this single-process demo. In production the publisher and consumer are
 * normally separate services/roles.
 */
public class FraudCheckQueueClient {

    private final SqsClient sqs;
    private final String queueUrl;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public FraudCheckQueueClient(SqsClient sqs, String queueUrl) {
        this.sqs = sqs;
        this.queueUrl = queueUrl;
    }

    public void publish(CardTransaction txn) throws Exception {
        SendMessageRequest request = SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(mapper.writeValueAsString(txn))
                .messageAttributes(Map.of(
                        "amount", MessageAttributeValue.builder()
                                .dataType("Number")
                                .stringValue(txn.getAmount().toString())
                                .build()))
                .build();

        sqs.sendMessage(request);
    }

    /** Long-polls once and returns whatever fraud-check messages are waiting. */
    public List<Message> poll() {
        ReceiveMessageRequest request = ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(10)
                .waitTimeSeconds(5)
                .build();
        return sqs.receiveMessage(request).messages();
    }

    public void acknowledge(Message message) {
        sqs.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(message.receiptHandle())
                .build());
    }
}
