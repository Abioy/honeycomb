package com.nearinfinity.honeycomb.mysql;

import com.nearinfinity.honeycomb.HoneycombException;
import com.nearinfinity.honeycomb.mysql.gen.TableSchema;
import org.apache.avro.io.*;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.hadoop.conf.Configuration;
import org.apache.log4j.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.*;
import java.nio.ByteBuffer;
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
    public static UUID BytesToUUID(byte[] bytes) {
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
            logger.error("Serialization failed", e);
            throw new HoneycombException("Serialization failed", e);
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
            logger.error("Serialization failed", e);
            throw new HoneycombException("Serialization failed", e);
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
        final DatumReader<T> userDatumReader = new SpecificDatumReader<T>(clazz);
        final ByteArrayInputStream in = new ByteArrayInputStream(serializedData);
        binaryDecoder = DecoderFactory.get().binaryDecoder(in, binaryDecoder);

        try {
            return userDatumReader.read(null, binaryDecoder);
        } catch (IOException e) {
            throw new HoneycombException("Deserialization failed", e);
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
        final ByteArrayInputStream in = new ByteArrayInputStream(serializedData);
        binaryDecoder = DecoderFactory.get().binaryDecoder(in, binaryDecoder);


        try {
            return reader.read(null, binaryDecoder);
        } catch (IOException e) {
            throw new HoneycombException("Deserialization failed", e);
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
     * Read an XML file and extract a {@link Configuration} object from the XML.
     *
     * @param source XML file
     * @return {@link Configuration}
     * @throws IOException                  if any IO errors occur
     * @throws ParserConfigurationException if a DocumentBuilder cannot be created which satisfies the configuration requested
     * @throws SAXException                 If any parse errors occur.
     */
    public static Configuration readConfiguration(File source)
            throws IOException, ParserConfigurationException, SAXException {
        Configuration params = new Configuration(false);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(source);
        NodeList options = doc.getElementsByTagName("adapteroption");
        logger.info(String.format("Number of options %d", options.getLength()));
        for (int i = 0; i < options.getLength(); i++) {
            Element node = (Element) options.item(i);
            String name = node.getAttribute("name");
            String nodeValue = node.getTextContent();
            logger.info(String.format("Node %s = %s", name, nodeValue));
            params.set(name, nodeValue);
        }

        return params;
    }

    /**
     * Quietly close a {@link Closeable} suppressing the IOException thrown
     * @param closeable Closeable
     */
    public static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }
}
