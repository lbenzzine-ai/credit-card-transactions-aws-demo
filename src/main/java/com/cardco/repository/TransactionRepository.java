package com.cardco.repository;

import com.cardco.model.CardTransaction;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class TransactionRepository {

    private final DynamoDbTable<CardTransaction> table;
    private final DynamoDbIndex<CardTransaction> statusIndex;

    public TransactionRepository(DynamoDbClient client, String tableName) {
        DynamoDbEnhancedClient enhancedClient = DynamoDbEnhancedClient.builder()
                .dynamoDbClient(client)
                .build();
        this.table = enhancedClient.table(tableName, TableSchema.fromBean(CardTransaction.class));
        this.statusIndex = table.index(CardTransaction.STATUS_INDEX);
    }

    /** Conditional put: fails if this idempotency key has already been recorded. */
    public boolean recordAuthorization(CardTransaction txn) {
        try {
            table.putItem(PutItemEnhancedRequest.builder(CardTransaction.class)
                    .item(txn)
                    .conditionExpression(Expression.builder()
                            .expression("attribute_not_exists(idempotencyKey)")
                            .build())
                    .build());
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false; // duplicate authorization request — already processed
        }
    }

    public void updateStatus(String idempotencyKey, String newStatus) {
        CardTransaction existing = find(idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("Transaction not found: " + idempotencyKey));
        existing.setStatus(newStatus);
        table.updateItem(existing);
    }

    public Optional<CardTransaction> find(String idempotencyKey) {
        Key key = Key.builder()
                .partitionValue(idempotencyKey)
                .build();
        return Optional.ofNullable(table.getItem(key));
    }

    /**
     * Queries the status-index GSI for every transaction currently in the
     * given status, oldest-first (by createdAt) — the same access pattern
     * a real settlement batch job uses: "give me everything still
     * AUTHORIZED," not a full-table scan.
     */
    public List<CardTransaction> findByStatus(String status) {
        List<CardTransaction> results = new ArrayList<>();
        statusIndex.query(QueryConditional.keyEqualTo(Key.builder().partitionValue(status).build()))
                .forEach(page -> results.addAll(page.items()));
        return results;
    }
}
