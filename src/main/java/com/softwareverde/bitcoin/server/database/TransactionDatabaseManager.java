package com.softwareverde.bitcoin.server.database;

import com.softwareverde.bitcoin.block.BlockId;
import com.softwareverde.bitcoin.chain.segment.BlockChainSegmentId;
import com.softwareverde.bitcoin.server.database.cache.TransactionIdCache;
import com.softwareverde.bitcoin.transaction.MutableTransaction;
import com.softwareverde.bitcoin.transaction.Transaction;
import com.softwareverde.bitcoin.transaction.TransactionId;
import com.softwareverde.bitcoin.transaction.input.TransactionInput;
import com.softwareverde.bitcoin.transaction.input.TransactionInputId;
import com.softwareverde.bitcoin.transaction.locktime.ImmutableLockTime;
import com.softwareverde.bitcoin.transaction.locktime.LockTime;
import com.softwareverde.bitcoin.transaction.output.TransactionOutput;
import com.softwareverde.bitcoin.transaction.output.TransactionOutputId;
import com.softwareverde.bitcoin.type.hash.sha256.MutableSha256Hash;
import com.softwareverde.bitcoin.type.hash.sha256.Sha256Hash;
import com.softwareverde.constable.list.List;
import com.softwareverde.constable.list.mutable.MutableList;
import com.softwareverde.database.DatabaseException;
import com.softwareverde.database.Query;
import com.softwareverde.database.Row;
import com.softwareverde.database.mysql.BatchedInsertQuery;
import com.softwareverde.database.mysql.MysqlDatabaseConnection;
import com.softwareverde.io.Logger;
import com.softwareverde.util.HexUtil;
import com.softwareverde.util.Util;

import java.util.Map;

public class TransactionDatabaseManager {
    public static final TransactionIdCache TRANSACTION_CACHE = new TransactionIdCache();

    protected final MysqlDatabaseConnection _databaseConnection;

    protected void _insertTransactionInputs(final BlockChainSegmentId blockChainSegmentId, final TransactionId transactionId, final Transaction transaction) throws DatabaseException {
        final TransactionInputDatabaseManager transactionInputDatabaseManager = new TransactionInputDatabaseManager(_databaseConnection);

        for (final TransactionInput transactionInput : transaction.getTransactionInputs()) {
            transactionInputDatabaseManager.insertTransactionInput(blockChainSegmentId, transactionId, transactionInput);
        }
    }

    protected void _insertTransactionOutputs(final TransactionId transactionId, final Transaction transaction) throws DatabaseException {
        final TransactionOutputDatabaseManager transactionOutputDatabaseManager = new TransactionOutputDatabaseManager(_databaseConnection);

        for (final TransactionOutput transactionOutput : transaction.getTransactionOutputs()) {
            transactionOutputDatabaseManager.insertTransactionOutput(transactionId, transactionOutput);
        }
    }

    protected void _insertTransactionInputs(final BlockChainSegmentId blockChainSegmentId, final List<TransactionId> transactionIds, final List<Transaction> transactions) throws DatabaseException {
        final TransactionInputDatabaseManager transactionInputDatabaseManager = new TransactionInputDatabaseManager(_databaseConnection);

        final MutableList<List<TransactionInput>> transactionInputs = new MutableList<List<TransactionInput>>(transactions.getSize());
        for (final Transaction transaction : transactions) {
            transactionInputs.add(transaction.getTransactionInputs());
        }

        transactionInputDatabaseManager.insertTransactionInputs(blockChainSegmentId, transactionIds, transactionInputs);
    }

    protected void _insertTransactionOutputs(final List<TransactionId> transactionIds, final List<Transaction> transactions) throws DatabaseException {
        final TransactionOutputDatabaseManager transactionOutputDatabaseManager = new TransactionOutputDatabaseManager(_databaseConnection);

        final MutableList<List<TransactionOutput>> transactionOutputs = new MutableList<List<TransactionOutput>>(transactions.getSize());
        for (final Transaction transaction : transactions) {
            transactionOutputs.add(transaction.getTransactionOutputs());
        }

        transactionOutputDatabaseManager.insertTransactionOutputs(transactionIds, transactionOutputs);
    }

    /**
     * Returns the transaction that matches the provided transactionHash, or null if one was not found.
     *  A matched transaction must belong to a block that is connected to the blockChainSegmentId.
     */
    protected TransactionId _getTransactionIdFromHash(final BlockChainSegmentId blockChainSegmentId, final Sha256Hash transactionHash) throws DatabaseException {
        final BlockDatabaseManager blockDatabaseManager = new BlockDatabaseManager(_databaseConnection);

        { // Attempt to find in cache first...
            final Map<BlockId, TransactionId> cachedTransactionIds = TRANSACTION_CACHE.getCachedTransactionIds(transactionHash);
            if (cachedTransactionIds != null) {
                for (final BlockId blockId : cachedTransactionIds.keySet()) {
                    final Boolean blockIsConnectedToChain = blockDatabaseManager.isBlockConnectedToChain(blockId, blockChainSegmentId);
                    if (blockIsConnectedToChain) {
                        return cachedTransactionIds.get(blockId);
                    }
                }
            }
        }

        final java.util.List<Row> rows = _databaseConnection.query(
            new Query("SELECT id, block_id FROM transactions WHERE hash = ?")
                .setParameter(HexUtil.toHexString(transactionHash.getBytes()))
        );
        if (rows.isEmpty()) { return null; }

        TransactionId matchedTransactionId = null;
        for (final Row row : rows) {
            final TransactionId transactionId = TransactionId.wrap(row.getLong("id"));
            final BlockId blockId = BlockId.wrap(row.getLong("block_id"));

            TRANSACTION_CACHE.cacheTransactionId(blockId, transactionId, transactionHash); // Cache all of the found TransactionIds for this hash, even if they're not on this BlockChainSegment...

            if (matchedTransactionId == null) {
                final Boolean blockIsConnectedToChain = blockDatabaseManager.isBlockConnectedToChain(blockId, blockChainSegmentId);
                if (blockIsConnectedToChain) {
                    matchedTransactionId = TransactionId.wrap(row.getLong("id"));
                }
            }
        }

        return matchedTransactionId;
    }

    /**
     * Returns the transaction that matches the provided transactionHash, or null if one was not found.
     *  A matched transaction must belong to a block that has not been assigned a blockChainSegmentId.
     */
    protected TransactionId _getUncommittedTransactionIdFromHash(final Sha256Hash transactionHash) throws DatabaseException {
        final java.util.List<Row> rows = _databaseConnection.query(
            new Query("SELECT transactions.id, transactions.block_id FROM transactions INNER JOIN blocks ON blocks.id = transactions.block_id WHERE blocks.block_chain_segment_id IS NULL AND transactions.hash = ?")
                .setParameter(HexUtil.toHexString(transactionHash.getBytes()))
        );
        if (rows.isEmpty()) { return null; }

        final Row row = rows.get(0);
        return TransactionId.wrap(row.getLong("id"));
    }

    protected TransactionId _getTransactionIdFromHash(final BlockId blockId, final Sha256Hash transactionHash) throws DatabaseException {
        final TransactionId cachedTransactionId = TRANSACTION_CACHE.getCachedTransactionId(blockId, transactionHash);
        if (cachedTransactionId != null) { return cachedTransactionId; }

        final java.util.List<Row> rows = _databaseConnection.query(
            new Query(
                "SELECT id FROM transactions WHERE block_id = ? AND hash = ?")
                .setParameter(blockId)
                .setParameter(HexUtil.toHexString(transactionHash.getBytes()))
        );

        if (rows.isEmpty()) { return null; }

        final Row row = rows.get(0);
        final TransactionId transactionId = TransactionId.wrap(row.getLong("id"));

        TRANSACTION_CACHE.cacheTransactionId(blockId, transactionId, transactionHash);

        return transactionId;
    }

    protected void _updateTransaction(final TransactionId transactionId, final BlockId blockId, final Transaction transaction) throws DatabaseException {
        final LockTime lockTime = transaction.getLockTime();
        _databaseConnection.executeSql(
            new Query("UPDATE transactions SET hash = ?, block_id = ?, version = ? = ?, lock_time = ? WHERE id = ?")
                .setParameter(HexUtil.toHexString(transaction.getHash().getBytes()))
                .setParameter(blockId)
                .setParameter(transaction.getVersion())
                .setParameter(lockTime.getValue())
                .setParameter(transactionId)
        );

        TRANSACTION_CACHE.cacheTransactionId(blockId, transactionId, transaction.getHash());
    }

    protected TransactionId _insertTransaction(final BlockId blockId, final Transaction transaction) throws DatabaseException {
        final LockTime lockTime = transaction.getLockTime();
        final TransactionId transactionId = TransactionId.wrap(_databaseConnection.executeSql(
            new Query("INSERT INTO transactions (hash, block_id, version, lock_time) VALUES (?, ?, ?, ?)")
                .setParameter(transaction.getHash())
                .setParameter(blockId)
                .setParameter(transaction.getVersion())
                .setParameter(lockTime.getValue())
        ));

        TRANSACTION_CACHE.cacheTransactionId(blockId, transactionId, transaction.getHash());
        return transactionId;
    }

    protected List<TransactionId> _insertTransactions(final BlockId blockId, final List<Transaction> transactions) throws DatabaseException {
        final Query batchedInsertQuery = new BatchedInsertQuery("INSERT INTO transactions (hash, block_id, version, lock_time) VALUES (?, ?, ?, ?)");
        for (final Transaction transaction : transactions) {
            final LockTime lockTime = transaction.getLockTime();

            batchedInsertQuery.setParameter(transaction.getHash());
            batchedInsertQuery.setParameter(blockId);
            batchedInsertQuery.setParameter(transaction.getVersion());
            batchedInsertQuery.setParameter(lockTime.getValue());
        }

        final Long firstTransactionId = _databaseConnection.executeSql(batchedInsertQuery);
        if (firstTransactionId == null) { return null; }

        final MutableList<TransactionId> transactionIds = new MutableList<TransactionId>(transactions.getSize());
        for (int i = 0; i < transactions.getSize(); ++i) {
            final Transaction transaction = transactions.get(i);
            final TransactionId transactionId = TransactionId.wrap(firstTransactionId + i);

            transactionIds.add(transactionId);
            TRANSACTION_CACHE.cacheTransactionId(blockId, transactionId, transaction.getHash());
        }
        return transactionIds;
    }

    public TransactionDatabaseManager(final MysqlDatabaseConnection databaseConnection) {
        _databaseConnection = databaseConnection;
    }

    public TransactionId insertTransaction(final BlockChainSegmentId blockChainSegmentId, final BlockId blockId, final Transaction transaction) throws DatabaseException {
        final TransactionId transactionId = _insertTransaction(blockId, transaction);

        _insertTransactionOutputs(transactionId, transaction);
        _insertTransactionInputs(blockChainSegmentId, transactionId, transaction);

        return transactionId;
    }

    public void insertTransactions(final BlockChainSegmentId blockChainSegmentId, final BlockId blockId, final List<Transaction> transactions) throws DatabaseException {
        final List<TransactionId> transactionIds = _insertTransactions(blockId, transactions);

        _insertTransactionOutputs(transactionIds, transactions); // NOTE: Since this is a bulk-insert and the following inputs may reference these outputs, insert the TransactionOutputs first...
        _insertTransactionInputs(blockChainSegmentId, transactionIds, transactions);

        // TODO: Bulk-inserting does not validate the order of the transactions (inputs may be (incorrectly) spending outputs that are in this batch, but not in the correct order).  Consider asserting that the order of the transactions is correct.
    }

    public List<TransactionId> getTransactionIdsFromHash(final Sha256Hash transactionHash) throws DatabaseException {
        final java.util.List<Row> rows = _databaseConnection.query(
            new Query("SELECT id FROM transactions WHERE hash = ?")
                .setParameter(transactionHash)
        );

        final MutableList<TransactionId> transactionIds = new MutableList<TransactionId>(rows.size());
        for (final Row row : rows) {
            final TransactionId transactionId = TransactionId.wrap(row.getLong("id"));
            transactionIds.add(transactionId);
        }

        return transactionIds;
    }

    /**
     * Attempts to find the transaction that matches transactionHash that was published on the provided blockChainSegmentId.
     *  This function is intended to be used when the blockId is not known.
     *  Uncommitted transactions are NOT included in this search.
     */
    public TransactionId getTransactionIdFromHash(final BlockChainSegmentId blockChainSegmentId, final Sha256Hash transactionHash) throws DatabaseException {
        final TransactionId transactionId = _getTransactionIdFromHash(blockChainSegmentId, transactionHash);
        return transactionId;
    }

    public Sha256Hash getTransactionHashFromTransactionId(final TransactionId transactionId) throws DatabaseException {
        final java.util.List<Row> rows = _databaseConnection.query(
            new Query("SELECT id, hash FROM transactions WHERE id = ?")
                .setParameter(transactionId)
        );
        if (rows.isEmpty()) { return null; }

        final Row row = rows.get(0);
        return MutableSha256Hash.fromHexString(row.getString("hash"));
    }

    /**
     * Attempts to find the transaction that matches transactionHash that has not been committed to a block.
     *  This function is intended to be used when the blockId is not known.
     *  Only uncommitted transactions (i.e. transactions have not been assigned a block or ones that associated to the block
     *  that is currently being stored...) are included in the search.
     */
    public TransactionId getUncommittedTransactionIdFromHash(final Sha256Hash transactionHash) throws DatabaseException {
        return _getUncommittedTransactionIdFromHash(transactionHash);
    }

    public TransactionId getTransactionIdFromHash(final BlockId blockId, final Sha256Hash transactionHash) throws DatabaseException {
        return _getTransactionIdFromHash(blockId, transactionHash);
    }

    public MutableTransaction getTransaction(final TransactionId transactionId) throws DatabaseException {
        final TransactionInputDatabaseManager transactionInputDatabaseManager = new TransactionInputDatabaseManager(_databaseConnection);
        final TransactionOutputDatabaseManager transactionOutputDatabaseManager = new TransactionOutputDatabaseManager(_databaseConnection);

        final java.util.List<Row> rows = _databaseConnection.query(
            new Query("SELECT * FROM transactions WHERE id = ?")
                .setParameter(transactionId)
        );
        if (rows.isEmpty()) { return null; }

        final Row row = rows.get(0);
        final Long version = row.getLong("version");
        final LockTime lockTime = new ImmutableLockTime(row.getLong("lock_time"));

        final MutableTransaction transaction = new MutableTransaction();

        transaction.setVersion(version);
        transaction.setLockTime(lockTime);

        final java.util.List<Row> transactionInputRows = _databaseConnection.query(
            new Query("SELECT id FROM transaction_inputs WHERE transaction_id = ? ORDER BY id ASC")
                .setParameter(transactionId)
        );
        for (final Row transactionInputRow : transactionInputRows) {
            final TransactionInputId transactionInputId = TransactionInputId.wrap(transactionInputRow.getLong("id"));
            final TransactionInput transactionInput = transactionInputDatabaseManager.getTransactionInput(transactionInputId);
            transaction.addTransactionInput(transactionInput);
        }

        final java.util.List<Row> transactionOutputRows = _databaseConnection.query(
            new Query("SELECT id FROM transaction_outputs WHERE transaction_id = ? ORDER BY id ASC")
                .setParameter(transactionId)
        );
        for (final Row transactionOutputRow : transactionOutputRows) {
            final TransactionOutputId transactionOutputId = TransactionOutputId.wrap(transactionOutputRow.getLong("id"));
            final TransactionOutput transactionOutput = transactionOutputDatabaseManager.getTransactionOutput(transactionOutputId);
            transaction.addTransactionOutput(transactionOutput);
        }

        { // Validate inflated transaction hash...
            final Sha256Hash expectedTransactionHash = MutableSha256Hash.fromHexString(row.getString("hash"));
            if (! Util.areEqual(expectedTransactionHash, transaction.getHash())) {
                Logger.log("ERROR: Error inflating transaction: " + expectedTransactionHash);
                Logger.log(transaction.toJson());
                return null;
            }
        }

        return transaction;
    }

    public BlockId getBlockId(final TransactionId transactionId) throws DatabaseException {
        final java.util.List<Row> rows = _databaseConnection.query(
            new Query("SELECT id, block_id FROM transactions WHERE id = ?")
                .setParameter(transactionId)
        );
        if (rows.isEmpty()) { return null; }

        final Row row = rows.get(0);
        return BlockId.wrap(row.getLong("block_id"));
    }

    public Integer getTransactionCount(final BlockId blockId) throws DatabaseException {
        final java.util.List<Row> rows = _databaseConnection.query(
            new Query("SELECT block_id, COUNT(*) AS transaction_count FROM transactions WHERE block_id = ?")
                .setParameter(blockId)
        );

        if (rows.isEmpty()) { return null; }
        final Row row = rows.get(0);

        return row.getInteger("transaction_count");
    }
}
