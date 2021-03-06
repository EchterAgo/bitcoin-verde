package com.softwareverde.bitcoin.server.module.node;

import com.softwareverde.bitcoin.address.Address;
import com.softwareverde.bitcoin.address.AddressInflater;
import com.softwareverde.bitcoin.block.Block;
import com.softwareverde.bitcoin.block.BlockDeflater;
import com.softwareverde.bitcoin.block.BlockId;
import com.softwareverde.bitcoin.block.header.BlockHeader;
import com.softwareverde.bitcoin.block.header.BlockHeaderDeflater;
import com.softwareverde.bitcoin.chain.segment.BlockChainSegmentId;
import com.softwareverde.bitcoin.chain.time.MedianBlockTime;
import com.softwareverde.bitcoin.server.Environment;
import com.softwareverde.bitcoin.server.database.BlockChainDatabaseManager;
import com.softwareverde.bitcoin.server.database.BlockDatabaseManager;
import com.softwareverde.bitcoin.server.database.TransactionDatabaseManager;
import com.softwareverde.bitcoin.server.database.TransactionOutputDatabaseManager;
import com.softwareverde.bitcoin.transaction.Transaction;
import com.softwareverde.bitcoin.transaction.TransactionDeflater;
import com.softwareverde.bitcoin.transaction.TransactionId;
import com.softwareverde.bitcoin.transaction.input.TransactionInput;
import com.softwareverde.bitcoin.transaction.output.TransactionOutput;
import com.softwareverde.bitcoin.transaction.output.TransactionOutputId;
import com.softwareverde.bitcoin.transaction.output.identifier.TransactionOutputIdentifier;
import com.softwareverde.bitcoin.transaction.script.ScriptPatternMatcher;
import com.softwareverde.bitcoin.transaction.script.ScriptType;
import com.softwareverde.bitcoin.transaction.script.locking.LockingScript;
import com.softwareverde.bitcoin.type.hash.sha256.MutableSha256Hash;
import com.softwareverde.bitcoin.type.hash.sha256.Sha256Hash;
import com.softwareverde.constable.bytearray.ByteArray;
import com.softwareverde.constable.list.List;
import com.softwareverde.database.DatabaseException;
import com.softwareverde.database.Query;
import com.softwareverde.database.Row;
import com.softwareverde.database.mysql.MysqlDatabaseConnection;
import com.softwareverde.database.mysql.embedded.EmbeddedMysqlDatabase;
import com.softwareverde.io.Logger;
import com.softwareverde.json.Json;
import com.softwareverde.network.p2p.node.Node;
import com.softwareverde.network.p2p.node.address.NodeIpAddress;
import com.softwareverde.network.socket.JsonProtocolMessage;
import com.softwareverde.network.socket.JsonSocket;
import com.softwareverde.network.socket.JsonSocketServer;
import com.softwareverde.util.Container;
import com.softwareverde.util.DateUtil;
import com.softwareverde.util.HexUtil;
import com.softwareverde.util.Util;
import com.softwareverde.util.type.time.SystemTime;

public class JsonRpcSocketServerHandler implements JsonSocketServer.SocketConnectedCallback {
    public interface ShutdownHandler {
        Boolean shutdown();
    }

    public interface NodeHandler {
        Boolean addNode(String host, Integer port);
        List<Node> getNodes();
    }

    public interface QueryBalanceHandler {
        Long getBalance(Address address);
    }

    public static class StatisticsContainer {
        public Container<Float> averageBlockHeadersPerSecond;
        public Container<Float> averageBlocksPerSecond;
        public Container<Float> averageTransactionsPerSecond;
    }

    protected final Environment _environment;
    protected final Container<Float> _averageBlocksPerSecond;
    protected final Container<Float> _averageBlockHeadersPerSecond;
    protected final Container<Float> _averageTransactionsPerSecond;

    protected ShutdownHandler _shutdownHandler = null;
    protected NodeHandler _nodeHandler = null;
    protected QueryBalanceHandler _queryBalanceHandler = null;

    protected void _addMetadataForTransactionToJson(final BlockChainSegmentId mainBlockChainSegmentId, final Transaction transaction, final Json transactionJson, final MysqlDatabaseConnection databaseConnection) throws DatabaseException {
        final Sha256Hash transactionHash = transaction.getHash();
        final String transactionHashString = transactionHash.toString();

        final BlockDatabaseManager blockDatabaseManager = new BlockDatabaseManager(databaseConnection);
        final TransactionDatabaseManager transactionDatabaseManager = new TransactionDatabaseManager(databaseConnection);
        final TransactionOutputDatabaseManager transactionOutputDatabaseManager = new TransactionOutputDatabaseManager(databaseConnection);
        final ScriptPatternMatcher scriptPatternMatcher = new ScriptPatternMatcher();

        final TransactionDeflater transactionDeflater = new TransactionDeflater();
        final ByteArray transactionData = transactionDeflater.toBytes(transaction);
        transactionJson.put("byteCount", transactionData.getByteCount());

        Long transactionFee = 0L;

        { // Include Block hashes which include this transaction...
            final Json blockHashesJson = new Json(true);
            final List<TransactionId> duplicateTransactionIds = transactionDatabaseManager.getTransactionIdsFromHash(transactionHash);
            for (final TransactionId duplicateTransactionId : duplicateTransactionIds) {
                final BlockId blockId = transactionDatabaseManager.getBlockId(duplicateTransactionId);
                final Sha256Hash blockHash = blockDatabaseManager.getBlockHashFromId(blockId);
                blockHashesJson.add(blockHash);
            }
            transactionJson.put("blocks", blockHashesJson);
        }

        { // Process TransactionInputs...
            Integer transactionInputIndex = 0;
            for (final TransactionInput transactionInput : transaction.getTransactionInputs()) {
                final TransactionOutputId previousTransactionOutputId;
                {
                    final Sha256Hash previousOutputTransactionHash = transactionInput.getPreviousOutputTransactionHash();
                    if (previousOutputTransactionHash != null) {
                        final TransactionOutputIdentifier previousTransactionOutputIdentifier = new TransactionOutputIdentifier(mainBlockChainSegmentId, previousOutputTransactionHash, transactionInput.getPreviousOutputIndex());
                        previousTransactionOutputId = transactionOutputDatabaseManager.findTransactionOutput(previousTransactionOutputIdentifier);

                        if (previousTransactionOutputId == null) {
                            Logger.log("NOTICE: Error calculating fee for Transaction: " + transactionHashString);
                        }
                    }
                    else {
                        previousTransactionOutputId = null;
                    }
                }

                if (previousTransactionOutputId == null) {
                    transactionFee = null; // Abort calculating the transaction fee but continue with the rest of the processing...
                }

                final TransactionOutput previousTransactionOutput = ( previousTransactionOutputId != null ? transactionOutputDatabaseManager.getTransactionOutput(previousTransactionOutputId) : null );
                final Long previousTransactionOutputAmount = ( previousTransactionOutput != null ? previousTransactionOutput.getAmount() : null );

                if (transactionFee != null) {
                    transactionFee += Util.coalesce(previousTransactionOutputAmount);
                }

                final String addressString;
                {
                    if (previousTransactionOutput != null) {
                        final LockingScript lockingScript = previousTransactionOutput.getLockingScript();
                        final ScriptType scriptType = scriptPatternMatcher.getScriptType(lockingScript);
                        final Address address = scriptPatternMatcher.extractAddress(scriptType, lockingScript);
                        addressString = address.toBase58CheckEncoded();
                    }
                    else {
                        addressString = null;
                    }
                }

                final Json transactionInputJson = transactionJson.get("inputs").get(transactionInputIndex);
                transactionInputJson.put("previousTransactionAmount", previousTransactionOutputAmount);
                transactionInputJson.put("address", addressString);

                transactionInputIndex += 1;
            }
        }

        { // Process TransactionOutputs...
            int transactionOutputIndex = 0;
            for (final TransactionOutput transactionOutput : transaction.getTransactionOutputs()) {
                if (transactionFee != null) {
                    transactionFee -= transactionOutput.getAmount();
                }

                { // Add extra TransactionOutput json fields...
                    final String addressString;
                    {
                        final LockingScript lockingScript = transactionOutput.getLockingScript();
                        final ScriptType scriptType = scriptPatternMatcher.getScriptType(lockingScript);
                        final Address address = scriptPatternMatcher.extractAddress(scriptType, lockingScript);
                        addressString = (address != null ? address.toBase58CheckEncoded() : null);
                    }

                    final Json transactionOutputJson = transactionJson.get("outputs").get(transactionOutputIndex);
                    transactionOutputJson.put("address", addressString);
                }

                transactionOutputIndex += 1;
            }
        }

        transactionJson.put("fee", transactionFee);
    }

    public JsonRpcSocketServerHandler(final Environment environment, final StatisticsContainer statisticsContainer) {
        _environment = environment;

        _averageBlockHeadersPerSecond = statisticsContainer.averageBlockHeadersPerSecond;
        _averageBlocksPerSecond = statisticsContainer.averageBlocksPerSecond;
        _averageTransactionsPerSecond = statisticsContainer.averageTransactionsPerSecond;
    }

    protected Long _calculateBlockHeight(final MysqlDatabaseConnection databaseConnection) throws DatabaseException {
        final BlockDatabaseManager blockDatabaseManager = new BlockDatabaseManager(databaseConnection);

        final BlockId blockId = blockDatabaseManager.getHeadBlockId();
        if (blockId == null) { return 0L; }

        return blockDatabaseManager.getBlockHeightForBlockId(blockId);
    }

    protected Long _calculateBlockHeaderHeight(final MysqlDatabaseConnection databaseConnection) throws DatabaseException {
        final BlockDatabaseManager blockDatabaseManager = new BlockDatabaseManager(databaseConnection);

        final BlockId blockId = blockDatabaseManager.getHeadBlockHeaderId();
        if (blockId == null) { return 0L; }

        return blockDatabaseManager.getBlockHeightForBlockId(blockId);
    }

    // Requires GET:    [blockHeight=null], [maxBlockCount=10]
    protected void _getBlockHeaders(final Json parameters, final Json response) {
        final EmbeddedMysqlDatabase database = _environment.getDatabase();
        try (final MysqlDatabaseConnection databaseConnection = database.newConnection()) {
            final BlockDatabaseManager blockDatabaseManager = new BlockDatabaseManager(databaseConnection);
            final TransactionDatabaseManager transactionDatabaseManager = new TransactionDatabaseManager(databaseConnection);

            final Long startingBlockHeight;
            {
                final String blockHeightString = parameters.getString("blockHeight");
                if (Util.isInt(blockHeightString)) {
                    startingBlockHeight = parameters.getLong("blockHeight");
                }
                else {
                    final BlockId headBlockId = blockDatabaseManager.getHeadBlockId();
                    startingBlockHeight = blockDatabaseManager.getBlockHeightForBlockId(headBlockId);
                }
            }

            final Integer maxBlockCount;
            if (parameters.hasKey("maxBlockCount")) {
                maxBlockCount = parameters.getInteger("maxBlockCount");
            }
            else {
                maxBlockCount = 10;
            }

            final Json blockHeadersJson = new Json(true);
            final java.util.List<Row> rows = databaseConnection.query(
                new Query("SELECT id FROM blocks WHERE block_height <= ? ORDER BY block_height DESC LIMIT " + maxBlockCount)
                    .setParameter(startingBlockHeight)
            );
            for (final Row row : rows) {
                final BlockId blockId = BlockId.wrap(row.getLong("id"));

                final Json blockJson;
                {
                    final BlockHeader blockHeader = blockDatabaseManager.getBlockHeader(blockId);
                    blockJson = blockHeader.toJson();
                }

                { // Include Extra Block Metadata...
                    final Boolean isFullBlock = false;
                    final Long blockHeight = blockDatabaseManager.getBlockHeightForBlockId(blockId);
                    final Integer transactionCount = transactionDatabaseManager.getTransactionCount(blockId);

                    blockJson.put("height", blockHeight);
                    blockJson.put("reward", BlockHeader.calculateBlockReward(blockHeight));
                    // blockJson.put("byteCount", (isFullBlock ? blockHeader.getByteCount() : null));
                    blockJson.put("transactionCount", transactionCount);
                    blockJson.put("byteCount", null);
                }

                blockHeadersJson.add(blockJson);
            }

            response.put("blockHeaders", blockHeadersJson);

            response.put("wasSuccess", 1);
        }
        catch (final Exception exception) {
            response.put("wasSuccess", 0);
            response.put("errorMessage", exception.getMessage());
        }
    }

    protected void _getBlockHeader(final Json parameters, final Json response) {
        if (! parameters.hasKey("hash")) {
            response.put("errorMessage", "Missing parameters. Required: hash");
            return;
        }

        final Boolean shouldReturnRawBlockData = parameters.getBoolean("rawFormat");

        final String blockHashString = parameters.getString("hash");
        final Sha256Hash blockHash = MutableSha256Hash.fromHexString(blockHashString);

        if (blockHash == null) {
            response.put("errorMessage", "Invalid block hash: " + blockHashString);
            return;
        }

        final EmbeddedMysqlDatabase database = _environment.getDatabase();
        try (final MysqlDatabaseConnection databaseConnection = database.newConnection()) {
            final BlockDatabaseManager blockDatabaseManager = new BlockDatabaseManager(databaseConnection);
            final TransactionDatabaseManager transactionDatabaseManager = new TransactionDatabaseManager(databaseConnection);

            final BlockId blockId = blockDatabaseManager.getBlockIdFromHash(blockHash);
            if (blockId == null) {
                response.put("errorMessage", "Block not found: " + blockHashString);
                return;
            }

            final BlockHeader block;
            final ByteArray blockData;
            {
                final BlockHeader blockHeader = blockDatabaseManager.getBlockHeader(blockId);
                if (blockHeader == null) {
                    response.put("errorMessage", "Could not inflate Block; it may be corrupted.");
                    return;
                }

                final BlockHeaderDeflater blockHeaderDeflater = new BlockHeaderDeflater();

                block = blockHeader;
                blockData = blockHeaderDeflater.toBytes(blockHeader);
            }

            if (shouldReturnRawBlockData) {
                response.put("block", HexUtil.toHexString(blockData.getBytes()));
            }
            else {
                final Json blockJson = block.toJson();

                { // Include Extra Block Metadata...
                    final Long blockHeight = blockDatabaseManager.getBlockHeightForBlockId(blockId);
                    final Integer transactionCount = transactionDatabaseManager.getTransactionCount(blockId);

                    blockJson.put("height", blockHeight);
                    blockJson.put("reward", BlockHeader.calculateBlockReward(blockHeight));
                    blockJson.put("byteCount", null);
                    blockJson.put("transactionCount", transactionCount);
                }

                response.put("block", blockJson);
            }

            response.put("wasSuccess", 1);
        }
        catch (final Exception exception) {
            response.put("wasSuccess", 0);
            response.put("errorMessage", exception.getMessage());
        }
    }

    protected void _getBlock(final Json parameters, final Json response) {
        if (! parameters.hasKey("hash")) {
            response.put("errorMessage", "Missing parameters. Required: hash");
            return;
        }

        final Boolean shouldReturnRawBlockData = parameters.getBoolean("rawFormat");

        final String blockHashString = parameters.getString("hash");
        final Sha256Hash blockHash = MutableSha256Hash.fromHexString(blockHashString);

        if (blockHash == null) {
            response.put("errorMessage", "Invalid block hash: " + blockHashString);
            return;
        }

        final EmbeddedMysqlDatabase database = _environment.getDatabase();
        try (final MysqlDatabaseConnection databaseConnection = database.newConnection()) {
            final BlockDatabaseManager blockDatabaseManager = new BlockDatabaseManager(databaseConnection);
            final TransactionDatabaseManager transactionDatabaseManager = new TransactionDatabaseManager(databaseConnection);

            final BlockId blockId = blockDatabaseManager.getBlockIdFromHash(blockHash);
            if (blockId == null) {
                response.put("errorMessage", "Block not found: " + blockHashString);
                return;
            }

            final BlockHeader block;
            final ByteArray blockData;
            final Boolean isFullBlock;
            {
                final Block fullBlock = blockDatabaseManager.getBlock(blockId);
                if (fullBlock != null) {
                    final BlockDeflater blockDeflater = new BlockDeflater();

                    isFullBlock = true;
                    block = fullBlock;
                    blockData = blockDeflater.toBytes(fullBlock);
                }
                else {
                    final BlockHeader blockHeader = blockDatabaseManager.getBlockHeader(blockId);
                    if (blockHeader == null) {
                        response.put("errorMessage", "Could not inflate Block; it may be corrupted.");
                        return;
                    }

                    final BlockHeaderDeflater blockHeaderDeflater = new BlockHeaderDeflater();

                    isFullBlock = false;
                    block = blockHeader;
                    blockData = blockHeaderDeflater.toBytes(blockHeader);
                    response.put("errorMessage", "Block not synchronized yet; falling back to BlockHeader.");
                }
            }

            if (shouldReturnRawBlockData) {
                response.put("block", HexUtil.toHexString(blockData.getBytes()));
            }
            else {
                final Json blockJson = block.toJson();

                { // Include Extra Block Metadata...
                    final Long blockHeight = blockDatabaseManager.getBlockHeightForBlockId(blockId);
                    final Integer transactionCount = transactionDatabaseManager.getTransactionCount(blockId);

                    blockJson.put("height", blockHeight);
                    blockJson.put("reward", BlockHeader.calculateBlockReward(blockHeight));
                    blockJson.put("byteCount", (isFullBlock ? blockData.getByteCount() : null));
                    blockJson.put("transactionCount", transactionCount);
                }

                { // Add extra transaction metadata...
                    if (isFullBlock) {
                        final BlockId headBlockId = blockDatabaseManager.getHeadBlockId();
                        final BlockChainSegmentId mainBlockChainSegmentId = blockDatabaseManager.getBlockChainSegmentId(headBlockId);

                        final Json transactionsJson = blockJson.get("transactions");

                        int transactionIndex = 0;
                        for (final Transaction transaction : ((Block) block).getTransactions()) {
                            final Json transactionJson = transactionsJson.get(transactionIndex);
                            _addMetadataForTransactionToJson(mainBlockChainSegmentId, transaction, transactionJson, databaseConnection);
                            transactionIndex += 1;
                        }
                    }
                }

                response.put("block", blockJson);
            }

            response.put("wasSuccess", 1);
        }
        catch (final Exception exception) {
            response.put("wasSuccess", 0);
            response.put("errorMessage", exception.getMessage());
        }
    }

    protected void _getTransaction(final Json parameters, final Json response) {
        if (! parameters.hasKey("hash")) {
            response.put("errorMessage", "Missing parameters. Required: hash");
            return;
        }

        final Boolean shouldReturnRawTransactionData = parameters.getBoolean("rawFormat");

        final String transactionHashString = parameters.getString("hash");
        final Sha256Hash transactionHash = MutableSha256Hash.fromHexString(transactionHashString);

        if (transactionHash == null) {
            response.put("errorMessage", "Invalid transaction hash: " + transactionHashString);
            return;
        }

        final EmbeddedMysqlDatabase database = _environment.getDatabase();
        try (final MysqlDatabaseConnection databaseConnection = database.newConnection()) {
            final BlockChainDatabaseManager blockChainDatabaseManager = new BlockChainDatabaseManager(databaseConnection);
            final BlockDatabaseManager blockDatabaseManager = new BlockDatabaseManager(databaseConnection);
            final TransactionDatabaseManager transactionDatabaseManager = new TransactionDatabaseManager(databaseConnection);

            final BlockId headBlockId = blockDatabaseManager.getHeadBlockId();
            final BlockChainSegmentId mainBlockChainSegmentId = blockDatabaseManager.getBlockChainSegmentId(headBlockId);

            final TransactionId transactionId = transactionDatabaseManager.getTransactionIdFromHash(mainBlockChainSegmentId, transactionHash);
            if (transactionId == null) {
                response.put("errorMessage", "Transaction not found: " + transactionHashString);
                return;
            }

            final Transaction transaction = transactionDatabaseManager.getTransaction(transactionId);

            if (shouldReturnRawTransactionData) {
                final TransactionDeflater transactionDeflater = new TransactionDeflater();
                final ByteArray transactionData = transactionDeflater.toBytes(transaction);
                response.put("transaction", HexUtil.toHexString(transactionData.getBytes()));
            }
            else {
                final Json transactionJson = transaction.toJson();

                _addMetadataForTransactionToJson(mainBlockChainSegmentId, transaction, transactionJson, databaseConnection);

                response.put("transaction", transactionJson);
            }
            response.put("wasSuccess", 1);
        }
        catch (final Exception exception) {
            response.put("wasSuccess", 0);
            response.put("errorMessage", exception.getMessage());
        }
    }

    protected void _queryBlockHeight(final Json response) {
        final EmbeddedMysqlDatabase database = _environment.getDatabase();
        try (final MysqlDatabaseConnection databaseConnection = database.newConnection()) {
            final Long blockHeight = _calculateBlockHeight(databaseConnection);
            final Long blockHeaderHeight = _calculateBlockHeaderHeight(databaseConnection);

            response.put("wasSuccess", 1);
            response.put("blockHeight", blockHeight);
            response.put("blockHeaderHeight", blockHeaderHeight);
        }
        catch (final Exception exception) {
            response.put("wasSuccess", 0);
            response.put("errorMessage", exception.getMessage());
        }
    }

    protected void _queryStatus(final Json response) {
        final SystemTime systemTime = new SystemTime();

        final EmbeddedMysqlDatabase database = _environment.getDatabase();
        try (final MysqlDatabaseConnection databaseConnection = database.newConnection()) {

            final Long blockTimestampInSeconds;
            {
                final BlockDatabaseManager blockDatabaseManager = new BlockDatabaseManager(databaseConnection);
                final Sha256Hash lastKnownHash = blockDatabaseManager.getHeadBlockHash();
                if (lastKnownHash == null) {
                    blockTimestampInSeconds = MedianBlockTime.GENESIS_BLOCK_TIMESTAMP;
                }
                else {
                    final BlockId blockId = blockDatabaseManager.getBlockIdFromHash(lastKnownHash);
                    final BlockHeader blockHeader = blockDatabaseManager.getBlockHeader(blockId);
                    blockTimestampInSeconds = blockHeader.getTimestamp();
                }
            }

            final Long blockHeaderTimestampInSeconds;
            {
                final BlockDatabaseManager blockDatabaseManager = new BlockDatabaseManager(databaseConnection);
                final Sha256Hash lastKnownHash = blockDatabaseManager.getHeadBlockHeaderHash();
                if (lastKnownHash == null) {
                    blockHeaderTimestampInSeconds = MedianBlockTime.GENESIS_BLOCK_TIMESTAMP;
                }
                else {
                    final BlockId blockId = blockDatabaseManager.getBlockIdFromHash(lastKnownHash);
                    final BlockHeader blockHeader = blockDatabaseManager.getBlockHeader(blockId);
                    blockHeaderTimestampInSeconds = blockHeader.getTimestamp();
                }
            }

            final Long secondsBehind = (systemTime.getCurrentTimeInSeconds() - blockTimestampInSeconds);

            final Integer secondsInAnHour = (60 * 60);
            final Boolean isSyncing = (secondsBehind > secondsInAnHour);

            response.put("wasSuccess", 1);
            response.put("status", (isSyncing ? "SYNCHRONIZING" : "ONLINE"));

            final Long blockHeight = _calculateBlockHeight(databaseConnection);
            final Long blockHeaderHeight = _calculateBlockHeaderHeight(databaseConnection);

            final Json statisticsJson = new Json();
            statisticsJson.put("blockHeaderHeight", blockHeaderHeight);
            statisticsJson.put("blockHeadersPerSecond", _averageBlockHeadersPerSecond.value);
            statisticsJson.put("blockHeaderDate", DateUtil.timestampToDatetimeString(blockHeaderTimestampInSeconds * 1000));

            statisticsJson.put("blockHeight", blockHeight);
            statisticsJson.put("blocksPerSecond", _averageBlocksPerSecond.value);
            statisticsJson.put("blockDate", DateUtil.timestampToDatetimeString(blockTimestampInSeconds * 1000));

            statisticsJson.put("transactionsPerSecond", _averageTransactionsPerSecond.value);

            response.put("statistics", statisticsJson);
        }
        catch (final Exception exception) {
            response.put("wasSuccess", 0);
            response.put("errorMessage", exception.getMessage());
        }
    }

    protected void _queryBalance(final Json parameters, final Json response) {
        final QueryBalanceHandler queryBalanceHandler = _queryBalanceHandler;
        if (queryBalanceHandler == null) {
            response.put("errorMessage", "Operation not supported.");
            return;
        }

        if (! parameters.hasKey("address")) {
            response.put("errorMessage", "Missing parameters. Required: address");
            return;
        }

        final String addressString = parameters.getString("address");
        final AddressInflater addressInflater = new AddressInflater();
        final Address address = addressInflater.fromBase58Check(addressString);

        if (address == null) {
            response.put("errorMessage", "Invalid address.");
            return;
        }

        final Long balance = queryBalanceHandler.getBalance(address);

        if (balance == null) {
            response.put("errorMessage", "Unable to determine balance.");
            return;
        }

        response.put("balance", balance);
        response.put("wasSuccess", 1);
    }

    protected void _shutdown(final Json parameters, final Json response) {
        final ShutdownHandler shutdownHandler = _shutdownHandler;
        if (shutdownHandler == null) {
            response.put("errorMessage", "Operation not supported.");
            return;
        }

        final Boolean wasSuccessful = shutdownHandler.shutdown();
        response.put("wasSuccess", (wasSuccessful ? 1 : 0));
    }

    protected void _addNode(final Json parameters, final Json response) {
        final NodeHandler nodeHandler = _nodeHandler;
        if (nodeHandler == null) {
            response.put("errorMessage", "Operation not supported.");
            return;
        }

        if ((! parameters.hasKey("host")) || (! parameters.hasKey("port"))) {
            response.put("errorMessage", "Missing parameters. Required: host, port");
            return;
        }

        final String host = parameters.getString("host");
        final Integer port = parameters.getInteger("port");

        final Boolean wasSuccessful = nodeHandler.addNode(host, port);
        response.put("wasSuccess", (wasSuccessful ? 1 : 0));
    }

    protected void _nodeStatus(final Json response) {
        final NodeHandler nodeHandler = _nodeHandler;
        if (nodeHandler == null) {
            response.put("errorMessage", "Operation not supported.");
            return;
        }

        final Json nodeListJson = new Json();

        final List<Node> nodes = _nodeHandler.getNodes();
        for (final Node node : nodes) {
            final Json nodeJson = new Json();

            final NodeIpAddress nodeIpAddress = node.getRemoteNodeIpAddress();
            nodeJson.put("host", nodeIpAddress.getIp().toString());
            nodeJson.put("port", nodeIpAddress.getPort());

            nodeListJson.add(nodeJson);
        }

        response.put("nodes", nodeListJson);
        response.put("wasSuccess", 1);
    }

    public void setShutdownHandler(final ShutdownHandler shutdownHandler) {
        _shutdownHandler = shutdownHandler;
    }

    public void setNodeHandler(final NodeHandler nodeHandler) {
        _nodeHandler = nodeHandler;
    }

    public void setQueryBalanceHandler(final QueryBalanceHandler queryBalanceHandler) {
        _queryBalanceHandler = queryBalanceHandler;
    }

    @Override
    public void run(final JsonSocket socketConnection) {
        Logger.log("New Connection: " + socketConnection);
        socketConnection.setMessageReceivedCallback(new Runnable() {
            @Override
            public void run() {
                final Json message = socketConnection.popMessage().getMessage();
                Logger.log("Message received: "+ message);

                final String method = message.getString("method");
                final String query = message.getString("query");

                final Json response = new Json();
                response.put("method", "RESPONSE");
                response.put("wasSuccess", 0);
                response.put("errorMessage", null);

                final Json parameters = message.get("parameters");

                switch (method.toUpperCase()) {
                    case "GET": {
                        switch (query.toUpperCase()) {
                            case "BLOCK_HEADERS": {
                                _getBlockHeaders(parameters, response);
                            } break;

                            case "BLOCK": {
                                _getBlock(parameters, response);
                            } break;

                            case "BLOCK_HEADER": {
                                _getBlockHeader(parameters, response);
                            } break;

                            case "TRANSACTION": {
                                _getTransaction(parameters, response);
                            } break;

                            case "BLOCK_HEIGHT": {
                                _queryBlockHeight(response);
                            } break;

                            case "STATUS": {
                                _queryStatus(response);
                            } break;

                            case "NODES": {
                                _nodeStatus(response);
                            } break;

                            case "BALANCE": {
                                _queryBalance(parameters, response);
                            } break;

                            default: {
                                response.put("errorMessage", "Invalid command: " + method + "/" + query);
                            } break;
                        }
                    } break;

                    case "POST": {
                        switch (query.toUpperCase()) {
                            case "SHUTDOWN": {
                                _shutdown(parameters, response);
                            } break;

                            case "ADD_NODE": {
                                _addNode(parameters, response);
                            } break;

                            default: {
                                response.put("errorMessage", "Invalid command: " + method + "/" + query);
                            } break;
                        }
                    } break;

                    default: {
                        response.put("errorMessage", "Invalid method: " + method);
                    } break;
                }

                final String responseString = response.toString();
                final String truncatedResponseString = responseString.substring(0, Math.min(256, responseString.length()));
                Logger.log("Writing: " + truncatedResponseString + (responseString.length() > 256 ? "..." : ""));
                socketConnection.write(new JsonProtocolMessage(response));
            }
        });
        socketConnection.beginListening();
    }
}
