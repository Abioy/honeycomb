package com.nearinfinity.hbaseclient;

import org.apache.hadoop.hbase.util.Bytes;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Created with IntelliJ IDEA.
 * User: jedstrom
 * Date: 8/20/12
 * Time: 4:03 PM
 * To change this template use File | Settings | File Templates.
 */
public class ValueEncoder {
    private static final byte BYTE_MASK = (byte) 0x000000ff;

    private static final byte NEGATIVE_MASK = (byte)0x00000080;

    private static final long INVERT_SIGN_MASK = 0x8000000000000000L;

    private static final long INVERT_ALL_BITS_MASK = 0xFFFFFFFFFFFFFFFFL;

    public static byte[] reverseValue(byte[] value) {
        ByteBuffer buffer = ByteBuffer.allocate(value.length);

        for (int i = 0 ; i < value.length ; i++) {
            buffer.put((byte) (BYTE_MASK ^ value[i]));
        }

        return buffer.array();
    }

    public static byte[] encodeValue(byte[] value, ColumnType columnType) {
        if (value == null || value.length == 0) {
            return new byte[0];
        }
        byte[] encodedValue;
        switch (columnType) {
            case LONG: {
                long longValue = ByteBuffer.wrap(value).getLong();
                encodedValue = positionOfLong(longValue);
            } break;
            case DOUBLE: {
                double doubleValue = ByteBuffer.wrap(value).getDouble();
                encodedValue = positionOfDouble(doubleValue);
            } break;
            case STRING: {
                /* TODO: this should already have been lower-cased in canonical value, can we remove? */
                String stringValue = new String(value).toLowerCase();
                encodedValue = Bytes.toBytes(stringValue);
            } break;
            default:
                encodedValue = value;
        }
        return encodedValue;
    }

    private static byte[] positionOfLong(long value) {
        return ByteBuffer.allocate(8).putLong(value ^ INVERT_SIGN_MASK).array();
    }

    private static byte[] positionOfDouble(double value) {
        long longValue = Double.doubleToLongBits(value);
        if (isNegative(value)) {
            return ByteBuffer.allocate(8).putLong(longValue ^ INVERT_ALL_BITS_MASK).array();
        }
        return ByteBuffer.allocate(8).putLong(longValue ^ INVERT_SIGN_MASK).array();
    }

    private static boolean isNegative(double value) {
        byte [] bytes = ByteBuffer.allocate(8).putDouble(value).array();
        return (bytes[0] & NEGATIVE_MASK) != 0;
    }

    public static byte[] canonicalValue(byte[] value, ColumnType columnType) {
        if (value == null || value.length == 0) {
            return new byte[0];
        }
        byte[] canonicalValue;
        switch (columnType) {
            case STRING: {
                String stringValue = new String(value).toLowerCase();
                canonicalValue = Bytes.toBytes(stringValue);
            } break;
            default:
                canonicalValue = value;
        }
        return canonicalValue;
    }

    public static byte[] padValue(byte[] value, int padLength) {
        byte[] paddedValue = new byte[value.length + padLength];
        Arrays.fill(paddedValue, BYTE_MASK);
        for (int i = 0 ; i < value.length ; i++) {
            paddedValue[i] = value[i];
        }

        return paddedValue;
    }
}
