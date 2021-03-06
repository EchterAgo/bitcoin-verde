package com.softwareverde.bitcoin.transaction;

import com.softwareverde.bitcoin.transaction.input.MutableTransactionInput;
import com.softwareverde.bitcoin.transaction.input.TransactionInputInflater;
import com.softwareverde.bitcoin.transaction.locktime.ImmutableLockTime;
import com.softwareverde.bitcoin.transaction.output.MutableTransactionOutput;
import com.softwareverde.bitcoin.transaction.output.TransactionOutputInflater;
import com.softwareverde.bitcoin.util.bytearray.ByteArrayReader;
import com.softwareverde.io.Logger;
import com.softwareverde.util.HexUtil;
import com.softwareverde.util.bytearray.Endian;

public class TransactionInflater {
    protected MutableTransaction _fromByteArrayReader(final ByteArrayReader byteArrayReader) {
        final MutableTransaction transaction = new MutableTransaction();
        transaction._version = byteArrayReader.readLong(4, Endian.LITTLE);

        final TransactionInputInflater transactionInputInflater = new TransactionInputInflater();
        final Integer transactionInputCount = byteArrayReader.readVariableSizedInteger().intValue();
        for (int i=0; i<transactionInputCount; ++i) {
            if (byteArrayReader.remainingByteCount() < 1) { return null; }
            final MutableTransactionInput transactionInput = transactionInputInflater.fromBytes(byteArrayReader);
            if (transactionInput == null) { return null; }
            transaction._transactionInputs.add(transactionInput);
        }

        final TransactionOutputInflater transactionOutputInflater = new TransactionOutputInflater();
        final Integer transactionOutputCount = byteArrayReader.readVariableSizedInteger().intValue();
        for (int i=0; i<transactionOutputCount; ++i) {
            if (byteArrayReader.remainingByteCount() < 1) { return null; }
            final MutableTransactionOutput transactionOutput = transactionOutputInflater.fromBytes(i, byteArrayReader);
            if (transactionOutput == null) { return null; }
            transaction._transactionOutputs.add(transactionOutput);
        }

        {
            final Long lockTimeValue = byteArrayReader.readLong(4, Endian.LITTLE);
            transaction._lockTime = new ImmutableLockTime(lockTimeValue);
        }

        if (byteArrayReader.didOverflow()) { return null; }

        return transaction;
    }

    public void _debugBytes(final ByteArrayReader byteArrayReader) {
        Logger.log("Version: " + HexUtil.toHexString(byteArrayReader.readBytes(4)));

        {
            final ByteArrayReader.VariableSizedInteger inputCount = byteArrayReader.peakVariableSizedInteger();
            Logger.log("Tx Input Count: " + HexUtil.toHexString(byteArrayReader.readBytes(inputCount.bytesConsumedCount)));

            final TransactionInputInflater transactionInputInflater = new TransactionInputInflater();
            for (int i = 0; i < inputCount.value; ++i) {
                transactionInputInflater._debugBytes(byteArrayReader);
            }
        }

        {
            final ByteArrayReader.VariableSizedInteger outputCount = byteArrayReader.peakVariableSizedInteger();
            Logger.log("Tx Output Count: " + HexUtil.toHexString(byteArrayReader.readBytes(outputCount.bytesConsumedCount)));
            final TransactionOutputInflater transactionOutputInflater = new TransactionOutputInflater();
            for (int i=0; i<outputCount.value; ++i) {
                transactionOutputInflater._debugBytes(byteArrayReader);
            }
        }

        Logger.log("LockTime: " + HexUtil.toHexString(byteArrayReader.readBytes(4)));
    }

    public MutableTransaction fromBytes(final ByteArrayReader byteArrayReader) {
        return _fromByteArrayReader(byteArrayReader);
    }

    public MutableTransaction fromBytes(final byte[] bytes) {
        final ByteArrayReader byteArrayReader = new ByteArrayReader(bytes);
        return _fromByteArrayReader(byteArrayReader);
    }
}
