package com.softwareverde.bitcoin.server.database;

import com.softwareverde.bitcoin.address.AddressDatabaseManager;
import com.softwareverde.bitcoin.address.AddressId;
import com.softwareverde.bitcoin.transaction.TransactionId;
import com.softwareverde.bitcoin.transaction.output.MutableTransactionOutput;
import com.softwareverde.bitcoin.transaction.output.TransactionOutput;
import com.softwareverde.bitcoin.transaction.output.TransactionOutputId;
import com.softwareverde.bitcoin.transaction.output.identifier.TransactionOutputIdentifier;
import com.softwareverde.bitcoin.transaction.script.ScriptPatternMatcher;
import com.softwareverde.bitcoin.transaction.script.ScriptType;
import com.softwareverde.bitcoin.transaction.script.locking.LockingScript;
import com.softwareverde.bitcoin.transaction.script.locking.MutableLockingScript;
import com.softwareverde.constable.list.List;
import com.softwareverde.constable.list.immutable.ImmutableListBuilder;
import com.softwareverde.constable.list.mutable.MutableList;
import com.softwareverde.database.DatabaseException;
import com.softwareverde.database.Query;
import com.softwareverde.database.Row;
import com.softwareverde.database.mysql.BatchedInsertQuery;
import com.softwareverde.database.mysql.BatchedUpdateQuery;
import com.softwareverde.database.mysql.MysqlDatabaseConnection;
import com.softwareverde.util.Util;

public class TransactionOutputDatabaseManager {
    protected final MysqlDatabaseConnection _databaseConnection;

    protected TransactionOutputId _findTransactionOutput(final Boolean isSpent, final TransactionId transactionId, final Integer transactionOutputIndex) throws DatabaseException {
        final java.util.List<Row> rows = _databaseConnection.query(
            new Query("SELECT id FROM transaction_outputs WHERE is_spent = ? AND transaction_id = ? AND `index` = ?")
                .setParameter(isSpent ? 1 : 0)
                .setParameter(transactionId)
                .setParameter(transactionOutputIndex)
        );

        if (rows.isEmpty()) { return null; }

        final Row row = rows.get(0);
        return TransactionOutputId.wrap(row.getLong("id"));
    }

    /**
     * Attempts to first find an unspent TransactionOutput that matches the TransactionId/Index combination.
     *  If an unspent TransactionOutput is not found, the search is repeated for spent TransactionOutputs.
     */
    protected TransactionOutputId _findTransactionOutput(final TransactionId transactionId, final Integer transactionOutputIndex) throws DatabaseException {
        final TransactionOutputId unspentTransactionOutputId = _findTransactionOutput(false, transactionId, transactionOutputIndex);
        if (unspentTransactionOutputId != null) { return unspentTransactionOutputId; }

        final TransactionOutputId spentTransactionOutputId = _findTransactionOutput(true, transactionId, transactionOutputIndex);
        return spentTransactionOutputId;
    }

    protected TransactionOutputId _insertTransactionOutput(final TransactionId transactionId, final TransactionOutput transactionOutput) throws DatabaseException {
        final LockingScript lockingScript = transactionOutput.getLockingScript();

        final Long transactionOutputIdLong = _databaseConnection.executeSql(
            new Query("INSERT INTO transaction_outputs (transaction_id, `index`, amount) VALUES (?, ?, ?)")
                .setParameter(transactionId)
                .setParameter(transactionOutput.getIndex())
                .setParameter(transactionOutput.getAmount())
        );

        final TransactionOutputId transactionOutputId = TransactionOutputId.wrap(transactionOutputIdLong);
        if (transactionOutputId == null) { return null; }

        _insertLockingScript(transactionOutputId, lockingScript);

        return transactionOutputId;
    }

    protected void _insertLockingScript(final TransactionOutputId transactionOutputId, final LockingScript lockingScript) throws DatabaseException {
        final ScriptPatternMatcher scriptPatternMatcher = new ScriptPatternMatcher();
        final ScriptType scriptType = scriptPatternMatcher.getScriptType(lockingScript);


        final AddressId addressId;
        if (scriptType != ScriptType.UNKNOWN) {
            final AddressDatabaseManager addressDatabaseManager = new AddressDatabaseManager(_databaseConnection);
            addressId = addressDatabaseManager.storeScriptAddress(lockingScript);
        }
        else {
            addressId = null;
        }

        _databaseConnection.executeSql(
            new Query("INSERT INTO locking_scripts (type, transaction_output_id, script, address_id) VALUES (?, ?, ?, ?)")
                .setParameter(scriptType)
                .setParameter(transactionOutputId)
                .setParameter(lockingScript.getBytes().getBytes())
                .setParameter(addressId)
        );
    }

    protected void _insertLockingScripts(final List<TransactionOutputId> transactionOutputIds, final List<LockingScript> lockingScripts) throws DatabaseException {
        if (! Util.areEqual(transactionOutputIds.getSize(), lockingScripts.getSize())) {
            throw new RuntimeException("TransactionOutputDatabaseManager::_insertLockingScripts -- transactionOutputIds.getSize must equal lockingScripts.getSize");
        }

        final Query batchInsertQuery = new BatchedInsertQuery("INSERT INTO locking_scripts (type, transaction_output_id, script, address_id) VALUES (?, ?, ?, ?)");

        final AddressDatabaseManager addressDatabaseManager = new AddressDatabaseManager(_databaseConnection);

        // final List<AddressId> addressIds = addressDatabaseManager.storeScriptAddresses(lockingScripts);
        final List<AddressId> addressIds;
        {
            final ImmutableListBuilder<AddressId> listBuilder = new ImmutableListBuilder<AddressId>(lockingScripts.getSize());
            for (final LockingScript lockingScript : lockingScripts) {
                final AddressId addressId = addressDatabaseManager.storeScriptAddress(lockingScript);
                listBuilder.add(addressId);
            }
            addressIds = listBuilder.build();
        }

        final ScriptPatternMatcher scriptPatternMatcher = new ScriptPatternMatcher();

        for (int i = 0; i < transactionOutputIds.getSize(); ++i) {
            final TransactionOutputId transactionOutputId = transactionOutputIds.get(i);
            final LockingScript lockingScript = lockingScripts.get(i);
            final AddressId addressId = addressIds.get(i);

            final ScriptType scriptType = scriptPatternMatcher.getScriptType(lockingScript);
            batchInsertQuery.setParameter(scriptType);
            batchInsertQuery.setParameter(transactionOutputId);
            batchInsertQuery.setParameter(lockingScript.getBytes().getBytes());
            batchInsertQuery.setParameter(addressId);
        }

        _databaseConnection.executeSql(batchInsertQuery);
    }

    protected TransactionOutput _getTransactionOutput(final TransactionOutputId transactionOutputId) throws DatabaseException {
        final Row transactionOutputRow;
        {
            final java.util.List<Row> rows = _databaseConnection.query(
                new Query("SELECT * FROM transaction_outputs WHERE id = ?")
                    .setParameter(transactionOutputId)
            );
            if (rows.isEmpty()) { return null; }

            transactionOutputRow = rows.get(0);
        }

        final LockingScript lockingScript;
        {
            final java.util.List<Row> rows = _databaseConnection.query(
                new Query("SELECT id, script FROM locking_scripts WHERE transaction_output_id = ?")
                    .setParameter(transactionOutputId)
            );
            if (rows.isEmpty()) { return null; }

            final Row row = rows.get(0);
            lockingScript = new MutableLockingScript(row.getBytes("script"));
        }

        final MutableTransactionOutput mutableTransactionOutput = new MutableTransactionOutput();
        mutableTransactionOutput.setIndex(transactionOutputRow.getInteger("index"));
        mutableTransactionOutput.setAmount(transactionOutputRow.getLong("amount"));
        mutableTransactionOutput.setLockingScript(lockingScript);
        return mutableTransactionOutput;
    }

    public TransactionOutputDatabaseManager(final MysqlDatabaseConnection databaseConnection) {
        _databaseConnection = databaseConnection;
    }

    public TransactionOutputId insertTransactionOutput(final TransactionId transactionId, final TransactionOutput transactionOutput) throws DatabaseException {
        return _insertTransactionOutput(transactionId, transactionOutput);
    }

    public List<TransactionOutputId> insertTransactionOutputs(final List<TransactionId> transactionIds, final List<List<TransactionOutput>> allTransactionOutputs) throws DatabaseException {
        if (! Util.areEqual(transactionIds.getSize(), allTransactionOutputs.getSize())) {
            throw new RuntimeException("TransactionOutputDatabaseManager::insertTransactionOutputs -- transactionIds.getSize must equal transactionOutputs.getSize");
        }

        final Query batchInsertQuery = new BatchedInsertQuery("INSERT INTO transaction_outputs (transaction_id, `index`, amount) VALUES (?, ?, ?)");

        final MutableList<LockingScript> lockingScripts = new MutableList<LockingScript>(transactionIds.getSize() * 2);

        for (int i = 0; i < transactionIds.getSize(); ++i) {
            final TransactionId transactionId = transactionIds.get(i);
            final List<TransactionOutput> transactionOutputs = allTransactionOutputs.get(i);

            for (final TransactionOutput transactionOutput : transactionOutputs) {
                final LockingScript lockingScript = transactionOutput.getLockingScript();
                lockingScripts.add(lockingScript);

                batchInsertQuery.setParameter(transactionId);
                batchInsertQuery.setParameter(transactionOutput.getIndex());
                batchInsertQuery.setParameter(transactionOutput.getAmount());
            }
        }

        final Long firstTransactionOutputId = _databaseConnection.executeSql(batchInsertQuery);
        if (firstTransactionOutputId == null) { return null; }

        final MutableList<TransactionOutputId> transactionOutputIds = new MutableList<TransactionOutputId>(lockingScripts.getSize());
        for (int i = 0; i < lockingScripts.getSize(); ++i) {
            final TransactionOutputId transactionOutputId = TransactionOutputId.wrap(firstTransactionOutputId + i);
            transactionOutputIds.add(transactionOutputId);
        }

        _insertLockingScripts(transactionOutputIds, lockingScripts);

        return transactionOutputIds;
    }

    public TransactionOutputId findTransactionOutput(final TransactionId transactionId, final Integer transactionOutputIndex) throws DatabaseException {
        return _findTransactionOutput(transactionId, transactionOutputIndex);
    }

    public TransactionOutputId findTransactionOutput(final TransactionOutputIdentifier transactionOutputIdentifier) throws DatabaseException {
        final TransactionDatabaseManager transactionDatabaseManager = new TransactionDatabaseManager(_databaseConnection);

        final Integer transactionOutputIndex = transactionOutputIdentifier.getOutputIndex();
        final TransactionId transactionId = transactionDatabaseManager.getTransactionIdFromHash(transactionOutputIdentifier.getBlockChainSegmentId(), transactionOutputIdentifier.getTransactionHash());
        if (transactionId == null) { return null; }

        final TransactionOutputId transactionOutputId = _findTransactionOutput(transactionId, transactionOutputIndex);
        return transactionOutputId;
    }

    public TransactionOutput getTransactionOutput(final TransactionOutputId transactionOutputId) throws DatabaseException {
        return _getTransactionOutput(transactionOutputId);
    }

    public void markTransactionOutputAsSpent(final TransactionOutputId transactionOutputId) throws DatabaseException {
        _databaseConnection.executeSql(
            new Query("UPDATE transaction_outputs SET is_spent = 1 WHERE id = ?")
                .setParameter(transactionOutputId)
        );
    }

    public void markTransactionOutputsAsSpent(final List<TransactionOutputId> transactionOutputIds) throws DatabaseException {
        final Query batchedUpdateQuery = new BatchedUpdateQuery("UPDATE transaction_outputs SET is_spent = 1 WHERE id IN(?)");
        for (final TransactionOutputId transactionOutputId : transactionOutputIds) {
            batchedUpdateQuery.setParameter(transactionOutputId);
        }

        _databaseConnection.executeSql(batchedUpdateQuery);
    }
}
