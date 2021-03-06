package com.softwareverde.bitcoin.server.message.type.query.response.hash;

import com.softwareverde.bitcoin.type.hash.sha256.MutableSha256Hash;
import com.softwareverde.bitcoin.type.hash.sha256.Sha256Hash;
import com.softwareverde.util.bytearray.ByteArrayReader;
import com.softwareverde.util.bytearray.Endian;

public class DataHashInflater {
    public static final Integer HASH_BYTE_COUNT = 32;

    public DataHash fromBytes(final ByteArrayReader byteArrayReader) {
        final Integer inventoryTypeCode = byteArrayReader.readInteger(4, Endian.LITTLE);
        final Sha256Hash objectHash = MutableSha256Hash.wrap(byteArrayReader.readBytes(HASH_BYTE_COUNT, Endian.LITTLE));

        if (byteArrayReader.didOverflow()) { return null; }

        final DataHashType dataType = DataHashType.fromValue(inventoryTypeCode);
        return new DataHash(dataType, objectHash);
    }
}
