package com.nexusiq.messaging;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class DecisionRequestedEventListener {

    private final DecisionRequestedProducer producer;

    public DecisionRequestedEventListener(DecisionRequestedProducer producer) {
        this.producer = producer;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDecisionRequested(DecisionRequestedEvent event) {
        producer.publish(event.envelope());
    }
}
