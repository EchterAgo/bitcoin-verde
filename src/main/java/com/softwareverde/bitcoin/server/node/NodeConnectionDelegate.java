package com.softwareverde.bitcoin.server.node;

import com.softwareverde.bitcoin.server.message.ProtocolMessage;
import com.softwareverde.bitcoin.server.message.type.block.BlockMessage;
import com.softwareverde.bitcoin.server.message.type.error.ErrorMessage;
import com.softwareverde.bitcoin.server.message.type.node.address.NodeIpAddressMessage;
import com.softwareverde.bitcoin.server.message.type.node.ping.PingMessage;
import com.softwareverde.bitcoin.server.message.type.query.response.QueryResponseMessage;
import com.softwareverde.bitcoin.server.message.type.version.acknowledge.AcknowledgeVersionMessage;
import com.softwareverde.io.Logger;
import com.softwareverde.util.HexUtil;

public abstract class NodeConnectionDelegate {
    protected final NodeConnection _connection;

    protected abstract void _onConnect();
    protected abstract void _onDisconnect();
    protected abstract void _onPingReceived(final PingMessage pingMessage);
    protected abstract void _onAcknowledgeVersionMessageReceived(final AcknowledgeVersionMessage acknowledgeVersionMessage);
    protected abstract void _onNodeAddressesReceived(final NodeIpAddressMessage nodeIpAddressMessage);
    protected abstract void _onErrorMessageReceived(final ErrorMessage errorMessage);
    protected abstract void _onQueryResponseMessageReceived(final QueryResponseMessage queryResponseMessage);
    protected abstract void _onBlockMessageReceived(final BlockMessage blockMessage);

    public NodeConnectionDelegate(final String host, final Integer port) {
        _connection = new NodeConnection(host, port);

        _connection.setMessageReceivedCallback(new NodeConnection.MessageReceivedCallback() {
            @Override
            public void onMessageReceived(final ProtocolMessage message) {
                switch (message.getCommand()) {
                    case PING: {
                        _onPingReceived((PingMessage) message);
                    } break;
                    case ACKNOWLEDGE_VERSION: {
                        _onAcknowledgeVersionMessageReceived((AcknowledgeVersionMessage) message);
                    } break;
                    case NODE_ADDRESSES: {
                        _onNodeAddressesReceived((NodeIpAddressMessage) message);
                    } break;
                    case ERROR: {
                        _onErrorMessageReceived((ErrorMessage) message);
                    } break;
                    case QUERY_RESPONSE: {
                        _onQueryResponseMessageReceived((QueryResponseMessage) message);
                    } break;
                    case BLOCK: {
                        _onBlockMessageReceived((BlockMessage) message);
                    } break;
                    default: {
                        Logger.log("NOTICE: Unhandled Message Command: "+ message.getCommand() +": 0x"+ HexUtil.toHexString(message.getHeaderBytes()));
                    } break;
                }
            }
        });

        _connection.setOnConnectCallback(new Runnable() {
            @Override
            public void run() {
                _onConnect();
            }
        });

        _connection.setOnDisconnectCallback(new Runnable() {
            @Override
            public void run() {
                _onDisconnect();
            }
        });

        _connection.startConnectionThread();
    }
}
