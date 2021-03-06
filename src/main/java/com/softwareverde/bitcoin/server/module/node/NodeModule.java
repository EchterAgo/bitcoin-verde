package com.softwareverde.bitcoin.server.module.node;

import com.softwareverde.bitcoin.address.AddressDatabaseManager;
import com.softwareverde.bitcoin.address.AddressId;
import com.softwareverde.bitcoin.block.BlockId;
import com.softwareverde.bitcoin.chain.time.MutableMedianBlockTime;
import com.softwareverde.bitcoin.server.Configuration;
import com.softwareverde.bitcoin.server.Constants;
import com.softwareverde.bitcoin.server.Environment;
import com.softwareverde.bitcoin.server.database.BlockDatabaseManager;
import com.softwareverde.bitcoin.server.database.TransactionDatabaseManager;
import com.softwareverde.bitcoin.server.database.cache.AddressIdCache;
import com.softwareverde.bitcoin.server.database.cache.TransactionIdCache;
import com.softwareverde.bitcoin.server.message.BitcoinProtocolMessage;
import com.softwareverde.bitcoin.server.module.node.handler.QueryBlockHeadersHandler;
import com.softwareverde.bitcoin.server.module.node.handler.QueryBlocksHandler;
import com.softwareverde.bitcoin.server.module.node.handler.RequestDataHandler;
import com.softwareverde.bitcoin.server.module.node.rpc.NodeHandler;
import com.softwareverde.bitcoin.server.module.node.rpc.QueryBalanceHandler;
import com.softwareverde.bitcoin.server.module.node.rpc.ShutdownHandler;
import com.softwareverde.bitcoin.server.module.node.sync.BlockDownloader;
import com.softwareverde.bitcoin.server.module.node.sync.BlockHeaderDownloader;
import com.softwareverde.bitcoin.server.node.BitcoinNode;
import com.softwareverde.bitcoin.transaction.TransactionId;
import com.softwareverde.bitcoin.type.hash.sha256.MutableSha256Hash;
import com.softwareverde.bitcoin.type.hash.sha256.Sha256Hash;
import com.softwareverde.bitcoin.util.BitcoinUtil;
import com.softwareverde.database.DatabaseException;
import com.softwareverde.database.Query;
import com.softwareverde.database.Row;
import com.softwareverde.database.mysql.MysqlDatabaseConnection;
import com.softwareverde.database.mysql.MysqlDatabaseConnectionFactory;
import com.softwareverde.database.mysql.debug.DebugEmbeddedMysqlDatabase;
import com.softwareverde.database.mysql.embedded.DatabaseCommandLineArguments;
import com.softwareverde.database.mysql.embedded.DatabaseInitializer;
import com.softwareverde.database.mysql.embedded.EmbeddedMysqlDatabase;
import com.softwareverde.database.mysql.embedded.properties.DatabaseProperties;
import com.softwareverde.io.Logger;
import com.softwareverde.network.socket.BinarySocket;
import com.softwareverde.network.socket.BinarySocketServer;
import com.softwareverde.network.socket.JsonSocketServer;
import com.softwareverde.util.ByteUtil;

import java.io.File;

public class NodeModule {
    public static void execute(final String configurationFileName) {
        final NodeModule nodeModule = new NodeModule(configurationFileName);
        nodeModule.loop();
    }

    protected final Boolean _shouldWarmUpCache = false;

    protected final Configuration _configuration;
    protected final Environment _environment;
    protected final ReadUncommittedDatabaseConnectionPool _readUncommittedDatabaseConnectionPool;

    protected final BitcoinNodeManager _nodeManager;
    protected final BinarySocketServer _socketServer;
    protected final JsonSocketServer _jsonRpcSocketServer;
    protected final BlockDownloader _blockDownloader;
    protected final BlockHeaderDownloader _blockHeaderDownloader;

    protected final NodeInitializer _nodeInitializer;

    protected Configuration _loadConfigurationFile(final String configurationFilename) {
        final File configurationFile =  new File(configurationFilename);
        if (! configurationFile.isFile()) {
            Logger.error("Invalid configuration file.");
            BitcoinUtil.exitFailure();
        }

        return new Configuration(configurationFile);
    }

    protected NodeModule(final String configurationFilename) {
        final Thread mainThread = Thread.currentThread();

        _configuration = _loadConfigurationFile(configurationFilename);

        final Configuration.ServerProperties serverProperties = _configuration.getServerProperties();
        final DatabaseProperties databaseProperties = _configuration.getDatabaseProperties();

        final EmbeddedMysqlDatabase database;
        {
            EmbeddedMysqlDatabase databaseInstance = null;
            try {
                final DatabaseInitializer databaseInitializer = new DatabaseInitializer("queries/init.sql", Constants.DATABASE_VERSION, new DatabaseInitializer.DatabaseUpgradeHandler() {
                    @Override
                    public Boolean onUpgrade(final int currentVersion, final int requiredVersion) { return false; }
                });

                final DatabaseCommandLineArguments commandLineArguments = new DatabaseCommandLineArguments();
                {
                    commandLineArguments.setInnoDbBufferPoolByteCount(serverProperties.getMaxMemoryByteCount());
                    commandLineArguments.setInnoDbBufferPoolInstanceCount(1);
                    commandLineArguments.setInnoDbLogFileByteCount(64 * ByteUtil.Unit.MEGABYTES);
                    commandLineArguments.setInnoDbLogBufferByteCount(8 * ByteUtil.Unit.MEGABYTES);
                    commandLineArguments.setQueryCacheByteCount(0L);
                    commandLineArguments.setMaxAllowedPacketByteCount(32 * ByteUtil.Unit.MEGABYTES);
                    // commandLineArguments.enableSlowQueryLog("slow-query.log", 1L);
                    // commandLineArguments.addArgument("--performance_schema");
                    // commandLineArguments.addArgument("--general_log_file=query.log");
                    // commandLineArguments.addArgument("--general_log=1");
                }

                databaseInstance = new DebugEmbeddedMysqlDatabase(databaseProperties, databaseInitializer, commandLineArguments);
                // databaseInstance = new EmbeddedMysqlDatabase(databaseProperties, databaseInitializer, commandLineArguments);
            }
            catch (final DatabaseException exception) {
                Logger.log(exception);
            }
            database = databaseInstance;

            if (database != null) {
                Logger.log("[Database Online]");
            }
            else {
                BitcoinUtil.exitFailure();
            }
        }

        _environment = new Environment(database);

        final MysqlDatabaseConnectionFactory databaseConnectionFactory = database.getDatabaseConnectionFactory();
        _readUncommittedDatabaseConnectionPool = new ReadUncommittedDatabaseConnectionPool(databaseConnectionFactory);

        final Integer maxPeerCount = serverProperties.getMaxPeerCount();
        _nodeManager = new BitcoinNodeManager(maxPeerCount, databaseConnectionFactory);

        {
            final QueryBlocksHandler queryBlocksHandler = new QueryBlocksHandler(databaseConnectionFactory);
            final QueryBlockHeadersHandler queryBlockHeadersHandler = new QueryBlockHeadersHandler(databaseConnectionFactory);
            final RequestDataHandler requestDataHandler = new RequestDataHandler(databaseConnectionFactory);
            _nodeInitializer = new NodeInitializer(queryBlocksHandler, queryBlockHeadersHandler, requestDataHandler);
        }

        _socketServer = new BinarySocketServer(serverProperties.getBitcoinPort(), BitcoinProtocolMessage.BINARY_PACKET_FORMAT);
        _socketServer.setSocketConnectedCallback(new BinarySocketServer.SocketConnectedCallback() {
            @Override
            public void run(final BinarySocket binarySocket) {
                Logger.log("New Connection: " + binarySocket);
                final BitcoinNode node = _nodeInitializer.initializeNode(binarySocket);
                _nodeManager.addNode(node);
            }
        });

        { // Initialize BlockDownloader...
            final MutableMedianBlockTime medianBlockTime;
            {
                MutableMedianBlockTime newMedianBlockTime = null;
                try (final MysqlDatabaseConnection databaseConnection = databaseConnectionFactory.newConnection()) {
                    final BlockDatabaseManager blockDatabaseManager = new BlockDatabaseManager(databaseConnection);
                    newMedianBlockTime = blockDatabaseManager.initializeMedianBlockTime();
                }
                catch (final DatabaseException exception) {
                    Logger.log(exception);
                    BitcoinUtil.exitFailure();
                }
                medianBlockTime = newMedianBlockTime;
            }

            final Integer maxQueueSize = serverProperties.getMaxBlockQueueSize();
            final BlockProcessor blockProcessor = new BlockProcessor(databaseConnectionFactory, _nodeManager, medianBlockTime, _readUncommittedDatabaseConnectionPool);
            blockProcessor.setMaxThreadCount(serverProperties.getMaxThreadCount());
            blockProcessor.setTrustedBlockHeight(serverProperties.getTrustedBlockHeight());
            _blockDownloader = new BlockDownloader(databaseConnectionFactory, _nodeManager, blockProcessor);
            _blockDownloader.setMaxQueueSize(maxQueueSize);
        }

        { // Initialize BlockHeaderDownloader...
            final MutableMedianBlockTime medianBlockTime;
            {
                MutableMedianBlockTime newMedianBlockTime = null;
                try (final MysqlDatabaseConnection databaseConnection = databaseConnectionFactory.newConnection()) {
                    final BlockDatabaseManager blockDatabaseManager = new BlockDatabaseManager(databaseConnection);
                    newMedianBlockTime = blockDatabaseManager.initializeMedianBlockHeaderTime();
                }
                catch (final DatabaseException exception) {
                    Logger.log(exception);
                    BitcoinUtil.exitFailure();
                }
                medianBlockTime = newMedianBlockTime;
            }

            _blockHeaderDownloader = new BlockHeaderDownloader(databaseConnectionFactory, _nodeManager, medianBlockTime);
        }

        final JsonRpcSocketServerHandler.StatisticsContainer statisticsContainer = _blockDownloader.getStatisticsContainer();
        statisticsContainer.averageBlockHeadersPerSecond = _blockHeaderDownloader.getAverageBlockHeadersPerSecondContainer();

        final Integer rpcPort = _configuration.getServerProperties().getBitcoinRpcPort();
        if (rpcPort > 0) {
            final JsonRpcSocketServerHandler.ShutdownHandler shutdownHandler = new ShutdownHandler(mainThread, _blockHeaderDownloader, _blockDownloader);
            final JsonRpcSocketServerHandler.NodeHandler nodeHandler = new NodeHandler(_nodeManager, _nodeInitializer);
            final JsonRpcSocketServerHandler.QueryBalanceHandler queryBalanceHandler = new QueryBalanceHandler(databaseConnectionFactory);

            final JsonSocketServer jsonRpcSocketServer = new JsonSocketServer(rpcPort);

            final JsonRpcSocketServerHandler rpcSocketServerHandler = new JsonRpcSocketServerHandler(_environment, statisticsContainer);
            rpcSocketServerHandler.setShutdownHandler(shutdownHandler);
            rpcSocketServerHandler.setNodeHandler(nodeHandler);
            rpcSocketServerHandler.setQueryBalanceHandler(queryBalanceHandler);

            jsonRpcSocketServer.setSocketConnectedCallback(rpcSocketServerHandler);
            _jsonRpcSocketServer = jsonRpcSocketServer;
        }
        else {
            _jsonRpcSocketServer = null;
        }
    }

    public void loop() {
        if (_shouldWarmUpCache) {
            Logger.log("[Warming Cache]");
            try (final MysqlDatabaseConnection databaseConnection = _environment.getDatabase().newConnection()) {
                { // Warm Up AddressDatabaseManager Cache...
                    final AddressDatabaseManager addressDatabaseManager = new AddressDatabaseManager(databaseConnection);
                    final java.util.List<Row> rows = databaseConnection.query(
                        new Query("SELECT id, address FROM addresses ORDER BY id DESC LIMIT " + AddressIdCache.DEFAULT_CACHE_SIZE)
                    );
                    for (final Row row : rows) {
                        final AddressId addressId = AddressId.wrap(row.getLong("id"));
                        final String address = row.getString("address");
                        addressDatabaseManager.getAddressId(address);
                    }

                    AddressDatabaseManager.ADDRESS_CACHE.clearDebug();
                }

                { // Warm Up TransactionDatabaseManager Cache...
                    final TransactionDatabaseManager transactionDatabaseManager = new TransactionDatabaseManager(databaseConnection);
                    final java.util.List<Row> rows = databaseConnection.query(
                        new Query("SELECT id, block_id, hash FROM transactions WHERE block_id IS NOT NULL ORDER BY id DESC LIMIT " + TransactionIdCache.DEFAULT_CACHE_SIZE)
                    );
                    for (final Row row : rows) {
                        final TransactionId transactionId = TransactionId.wrap(row.getLong("id"));
                        final BlockId blockId = BlockId.wrap(row.getLong("block_id"));
                        final Sha256Hash transactionHash = MutableSha256Hash.fromHexString(row.getString("hash"));
                        transactionDatabaseManager.getTransactionIdFromHash(blockId, transactionHash);
                    }

                    TransactionDatabaseManager.TRANSACTION_CACHE.clearDebug();
                }
            }
            catch (final DatabaseException exception) {
                Logger.log(exception);
                BitcoinUtil.exitFailure();
            }
        }

        _nodeManager.startNodeMaintenanceThread();

        Logger.log("[Server Online]");

        final Configuration.ServerProperties serverProperties = _configuration.getServerProperties();
        for (final Configuration.SeedNodeProperties seedNodeProperties : serverProperties.getSeedNodeProperties()) {
            final String host = seedNodeProperties.getAddress();
            final Integer port = seedNodeProperties.getPort();

            final BitcoinNode node = _nodeInitializer.initializeNode(host, port);
            _nodeManager.addNode(node);
        }

        if (_jsonRpcSocketServer != null) {
            _jsonRpcSocketServer.start();
            Logger.log("[RPC Server Online]");
        }
        else {
            Logger.log("NOTICE: Bitcoin RPC Server not started.");
        }

        _socketServer.start();
        Logger.log("[Listening For Connections]");

        _blockDownloader.start();
        Logger.log("[Started Syncing Blocks]");

        _blockHeaderDownloader.start();
        Logger.log("[Started Syncing Headers]");

        while (! Thread.currentThread().isInterrupted()) {
            try { Thread.sleep(5000); } catch (final Exception e) { break; }
        }

        _blockHeaderDownloader.stop();
        _blockDownloader.stop();
        _nodeManager.stopNodeMaintenanceThread();
        _readUncommittedDatabaseConnectionPool.shutdown();
        _socketServer.stop();

        if (_jsonRpcSocketServer != null) {
            _jsonRpcSocketServer.stop();
        }

        System.exit(0);
    }
}
