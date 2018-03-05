package com.softwareverde.bitcoin.block.header;

import com.softwareverde.bitcoin.block.header.difficulty.Difficulty;
import com.softwareverde.bitcoin.block.merkleroot.Hashable;
import com.softwareverde.bitcoin.type.hash.Hash;
import com.softwareverde.bitcoin.type.hash.ImmutableHash;
import com.softwareverde.bitcoin.type.merkleroot.MerkleRoot;
import com.softwareverde.bitcoin.util.BitcoinUtil;
import com.softwareverde.constable.Constable;

public interface BlockHeader extends Hashable, Constable<ImmutableBlockHeader> {
    static final Integer VERSION = 0x04;
    static final ImmutableHash GENESIS_BLOCK_HEADER_HASH = new ImmutableHash(BitcoinUtil.hexStringToByteArray("000000000019D6689C085AE165831E934FF763AE46A2A6C172B3F1B60A8CE26F"));

    Hash getPreviousBlockHash();
    MerkleRoot getMerkleRoot();
    Difficulty getDifficulty();
    Integer getVersion();
    Long getTimestamp();
    Long getNonce();

    Boolean isValid();

    @Override
    ImmutableBlockHeader asConst();
}
