package com.nexusiq.messaging;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class DocumentUploadedEventListener {

    private final DocumentUploadedProducer producer;

    public DocumentUploadedEventListener(DocumentUploadedProducer producer) {
        this.producer = producer;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDocumentUploaded(DocumentUploadedEvent event) {
        producer.publish(event.envelope());
    }
}
