package com.nearinfinity.honeycomb.mysql;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.nearinfinity.honeycomb.RuntimeIOException;
import com.nearinfinity.honeycomb.mysql.gen.ColumnSchema;
import com.nearinfinity.honeycomb.mysql.gen.IndexSchema;
import com.nearinfinity.honeycomb.mysql.gen.TableSchema;
import org.apache.avro.io.*;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.log4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Utility class containing helper functions.
 */
public class Util {
    public static final int UUID_WIDTH = 16;
    private static final Logger logger = Logger.getLogger(Util.class);
    private static BinaryDecoder binaryDecoder;

    /**
     * Returns a byte wide buffer from a {@link UUID}.
     *
     * @param uuid The {@link UUID} to convert
     * @return A byte array representation that is {@value #UUID_WIDTH} bytes wide
     */
    public static byte[] UUIDToBytes(UUID uuid) {
        checkNotNull(uuid, "uuid must not be null.");
        return ByteBuffer.allocate(UUID_WIDTH)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array();
    }

    /**
     * Create a {@link UUID} from the provided byte array.
     *
     * @param bytes A byte array that must be {@value #UUID_WIDTH} bytes wide, not null
     * @return A {@link UUID} representation
     */
    public static UUID bytesToUUID(byte[] bytes) {
        checkNotNull(bytes, "bytes must not be null.");
        checkArgument(bytes.length == UUID_WIDTH, "bytes must be of length " + UUID_WIDTH + ".");
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    public static byte[] serializeTableSchema(TableSchema schema) {
        checkNotNull(schema, "Schema cannot be null");
        return serializeAvroObject(schema, TableSchema.class);
    }

    public static TableSchema deserializeTableSchema(byte[] schema) {
        checkNotNull(schema, "Schema cannot be null");
        return deserializeAvroObject(schema, TableSchema.class);
    }

    public static byte[] serializeIndexSchema(IndexSchema schema) {
        checkNotNull(schema, "Schema cannot be null");
        return serializeAvroObject(schema, IndexSchema.class);
    }

    public static IndexSchema deserializeIndexSchema(byte[] schema) {
        checkNotNull(schema, "Schema cannot be null");
        return deserializeAvroObject(schema, IndexSchema.class);
    }

    /**
     * Serialize an object to a byte array
     *
     * @param obj   The object to serialize
     * @param clazz The type of the object being serialized
     * @return Serialized row
     */
    public static <T> byte[] serializeAvroObject(T obj, Class<T> clazz) {
        DatumWriter<T> writer = new SpecificDatumWriter<T>(clazz);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Encoder encoder = EncoderFactory.get().binaryEncoder(out, null);
        try {
            writer.write(obj, encoder);
            encoder.flush();
        } catch (IOException e) {
            throw serializationError(obj, e);
        }

        return out.toByteArray();
    }

    /**
     * Serialize an object to a byte array
     *
     * @param obj    The object to serialize
     * @param writer The datum writer for the class
     * @return Serialized row
     */
    public static <T> byte[] serializeAvroObject(T obj, DatumWriter<T> writer) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Encoder encoder = EncoderFactory.get().binaryEncoder(out, null);
        try {
            writer.write(obj, encoder);
            encoder.flush();
        } catch (IOException e) {
            throw serializationError(obj, e);
        }

        return out.toByteArray();
    }

    /**
     * Deserialize the provided serialized data into an instance of the specified class type
     *
     * @param serializedData a buffer containing the serialized data
     * @param clazz          the class type to instantiate to store the deserialized data
     * @return A new instance of the specified class representing the deserialized data
     */
    public static <T> T deserializeAvroObject(byte[] serializedData, Class<T> clazz) {
        checkNotNull(serializedData);
        checkNotNull(clazz);
        final DatumReader<T> userDatumReader = new SpecificDatumReader<T>(clazz);
        final ByteArrayInputStream in = new ByteArrayInputStream(serializedData);
        binaryDecoder = DecoderFactory.get().binaryDecoder(in, binaryDecoder);

        try {
            return userDatumReader.read(null, binaryDecoder);
        } catch (IOException e) {
            throw deserializationError(serializedData, e, clazz);
        }
    }

    /**
     * Deserialize the provided serialized data into an instance of the specified class type
     *
     * @param serializedData a buffer containing the serialized data
     * @param reader         the datum reader for the class
     * @return A new instance of the specified class representing the deserialized data
     */
    public static <T> T deserializeAvroObject(byte[] serializedData, DatumReader<T> reader) {
        checkNotNull(serializedData);
        checkNotNull(reader);
        final ByteArrayInputStream in = new ByteArrayInputStream(serializedData);
        binaryDecoder = DecoderFactory.get().binaryDecoder(in, binaryDecoder);
        try {
            return reader.read(null, binaryDecoder);
        } catch (IOException e) {
            throw deserializationError(serializedData, e, null);
        }
    }

    public static String generateHexString(final byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }

        return sb.toString();
    }

    /**
     * Quietly close a {@link Closeable} suppressing the IOException thrown
     *
     * @param closeable Closeable
     */
    public static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException e) {
            logger.error(String.format("IOException thrown while closing resource of type %s", closeable.getClass().getName()), e);
            throw new RuntimeIOException(e);
        }
    }

    /**
     * Return the set of names of unique indices in the table.
     *
     * @param schema
     * @return
     */
    public static Set<String> getUniqueIndices(TableSchema schema) {
        Set<String> indices = Sets.newHashSet();
        for (Map.Entry<String, IndexSchema> entry : schema.getIndices().entrySet()) {
            if (entry.getValue().getIsUnique()) {
                indices.add(entry.getKey());
            }
        }
        return ImmutableSet.copyOf(indices);
    }
    /**
     * Return the name of the auto increment column in the table, or null.
     * @param schema
     * @return
     */
    public static String getAutoIncrementColumn(TableSchema schema) {
        Map<String, ColumnSchema> columns = schema.getColumns();
        for (Map.Entry<String, ColumnSchema> entry : columns.entrySet()) {
            if (entry.getValue().getIsAutoIncrement()) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static <T> RuntimeException deserializationError(byte[] serializedData, IOException e, Class<T> clazz) {
        String clazzMessage = clazz == null ? "" : "of class type " + clazz.getName();
        String format = String.format("Deserialization failed for data (%s) " + clazzMessage,
                Bytes.toStringBinary(serializedData));
        logger.error(format, e);
        return new RuntimeException(format, e);
    }

    private static <T> RuntimeException serializationError(T obj, IOException e) {
        String format = String.format("Serialization failed for data (%s) of class type %s",
                obj.toString(), obj.getClass().getName());
        logger.error(format, e);
        return new RuntimeException(format, e);
	}
}
