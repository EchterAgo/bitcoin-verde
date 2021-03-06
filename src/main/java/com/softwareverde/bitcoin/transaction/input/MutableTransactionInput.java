package com.softwareverde.bitcoin.transaction.input;

import com.softwareverde.bitcoin.transaction.locktime.SequenceNumber;
import com.softwareverde.bitcoin.transaction.script.unlocking.UnlockingScript;
import com.softwareverde.bitcoin.type.hash.sha256.Sha256Hash;
import com.softwareverde.json.Json;

public class MutableTransactionInput implements TransactionInput {

    protected Sha256Hash _previousOutputTransactionHash = Sha256Hash.EMPTY_HASH;
    protected Integer _previousOutputIndex = 0;
    protected UnlockingScript _unlockingScript = UnlockingScript.EMPTY_SCRIPT;
    protected SequenceNumber _sequenceNumber = SequenceNumber.MAX_SEQUENCE_NUMBER;

    public MutableTransactionInput() { }

    public MutableTransactionInput(final TransactionInput transactionInput) {
        _previousOutputTransactionHash = transactionInput.getPreviousOutputTransactionHash().asConst();
        _previousOutputIndex = transactionInput.getPreviousOutputIndex();
        _unlockingScript = transactionInput.getUnlockingScript().asConst();
        _sequenceNumber = transactionInput.getSequenceNumber();
    }

    @Override
    public Sha256Hash getPreviousOutputTransactionHash() { return _previousOutputTransactionHash; }
    public void setPreviousOutputTransactionHash(final Sha256Hash previousOutputTransactionHash) {
        _previousOutputTransactionHash = previousOutputTransactionHash;
    }

    @Override
    public Integer getPreviousOutputIndex() { return _previousOutputIndex; }
    public void setPreviousOutputIndex(final Integer index) {
        _previousOutputIndex = index;
    }

    @Override
    public UnlockingScript getUnlockingScript() { return _unlockingScript; }
    public void setUnlockingScript(final UnlockingScript signatureScript) {
        _unlockingScript = signatureScript;
    }

    @Override
    public SequenceNumber getSequenceNumber() { return _sequenceNumber; }

    public void setSequenceNumber(final SequenceNumber sequenceNumber) {
        _sequenceNumber = sequenceNumber.asConst();
    }

    @Override
    public ImmutableTransactionInput asConst() {
        return new ImmutableTransactionInput(this);
    }

    @Override
    public Json toJson() {
        final TransactionInputDeflater transactionInputDeflater = new TransactionInputDeflater();
        return transactionInputDeflater.toJson(this);
    }
}
