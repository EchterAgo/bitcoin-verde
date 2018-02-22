package com.softwareverde.bitcoin.server;

import com.softwareverde.bitcoin.block.Block;
import com.softwareverde.bitcoin.server.node.Node;
import com.softwareverde.bitcoin.type.hash.Hash;
import com.softwareverde.bitcoin.util.BitcoinUtil;
import com.softwareverde.io.Logger;
import org.junit.Test;

import java.util.List;

public class MainTests {
    protected void _loop() {
        while (true) {
            try { Thread.sleep(500); } catch (final Exception e) { }
        }
    }

//    @Test
    public void execute() {
        final String host = "btc.softwareverde.com";
        final Integer port = 8333;

        final Node node = new Node(host, port);

        node.getBlockHashesAfter(Block.GENESIS_BLOCK_HEADER_HASH, new Node.QueryCallback() {
            @Override
            public void onResult(final List<Hash> blockHashes) {
                Logger.log(blockHashes.size());

                node.requestBlock(Block.GENESIS_BLOCK_HEADER_HASH, new Node.DownloadBlockCallback() {
                    @Override
                    public void onResult(final Block block) {
                        Logger.log("BLOCK: " + BitcoinUtil.toHexString(block.calculateSha256Hash()));
                    }
                });
            }
        });

        _loop();
    }
}
