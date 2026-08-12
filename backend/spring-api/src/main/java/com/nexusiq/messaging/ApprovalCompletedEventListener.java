package com.nexusiq.messaging;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class ApprovalCompletedEventListener {

    private final ApprovalCompletedProducer producer;

    public ApprovalCompletedEventListener(ApprovalCompletedProducer producer) {
        this.producer = producer;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onApprovalCompleted(ApprovalCompletedEvent event) {
        producer.publish(event.envelope());
    }
}
