package com.nearinfinity.bulkloader;

import com.nearinfinity.hbaseclient.ColumnType;
import org.apache.hadoop.hbase.util.Bytes;
import org.junit.Assert;
import org.junit.Test;

import java.math.BigInteger;
import java.util.Arrays;

/**
 * Created with IntelliJ IDEA.
 * User: dburkert
 * Date: 9/6/12
 * Time: 3:36 PM
 * To change this template use File | Settings | File Templates.
 */
public class ValueTransformerTest {
    @Test
    public void testLongTransform() throws Exception {
        Assert.assertArrayEquals(
                Bytes.toBytes(0x00l),
                ValueTransformer.transform("00", ColumnType.LONG)
        );

        Assert.assertArrayEquals(
                Bytes.toBytes(0xFFFFFFFFFFFFFF85l),
                ValueTransformer.transform("-123", ColumnType.LONG)
        );

        Assert.assertArrayEquals(
                Bytes.toBytes(0x7Bl),
                ValueTransformer.transform("123", ColumnType.LONG)
        );

        Assert.assertArrayEquals(
                Bytes.toBytes(0x7FFFFFFFFFFFFFFFl),
                ValueTransformer.transform("9223372036854775807", ColumnType.LONG)
        );

        Assert.assertArrayEquals(
                Bytes.toBytes(0x8000000000000000l),
                ValueTransformer.transform("-9223372036854775808", ColumnType.LONG)
        );
    }
    @Test
    public void testULongTransform() throws Exception {
        Assert.assertArrayEquals(
                Bytes.toBytes(0x00l),
                ValueTransformer.transform("00", ColumnType.ULONG)
        );

        Assert.assertArrayEquals(
                Bytes.toBytes(0x7Bl),
                ValueTransformer.transform("123", ColumnType.ULONG)
        );

        Assert.assertArrayEquals(
                Bytes.toBytes(0x7FFFFFFFFFFFFFFFl),
                ValueTransformer.transform("9223372036854775807", ColumnType.ULONG)
        );

        Assert.assertArrayEquals(
                Bytes.toBytes(0x8000000000000000l),
                ValueTransformer.transform("9223372036854775808", ColumnType.ULONG)
        );

        Assert.assertArrayEquals(
                Bytes.toBytes(0xFFFFFFFFFFFFFFFFl),
                ValueTransformer.transform("18446744073709551615", ColumnType.ULONG)
        );
    }
    @Test(expected=Exception.class)
    public void testULongNegativeInput() throws Exception {
        ValueTransformer.transform("-123", ColumnType.ULONG);
    }
    @Test
    public void testDoubleTransform() throws Exception {
        // Note:  These values are all big endian, as per the JVM
        Assert.assertArrayEquals(
                Bytes.toBytes(0x00l),
                ValueTransformer.transform("0.0", ColumnType.DOUBLE)
        );

        Assert.assertArrayEquals(
                Bytes.toBytes(0x40283d70a3d70a3dl),
                ValueTransformer.transform("12.12", ColumnType.DOUBLE)
        );

        Assert.assertArrayEquals(
                Bytes.toBytes(0xc0283d70a3d70a3dl),
                ValueTransformer.transform("-12.12", ColumnType.DOUBLE)
        );
    }
    @Test
    public void testDateTransform() throws Exception {
        String[] formats = {
                "1989-05-13"
              , "1989.05.13"
              , "1989/05/13"
              , "19890513"
        };

        for (String format : formats) {
            Assert.assertArrayEquals(
                    "1989-05-13".getBytes(),
                    ValueTransformer.transform(format, ColumnType.DATE)
            );
        }
    }
    @Test
    public void testTimeTransform() throws Exception {
        String[] formats = {
                "07:32:15"
              , "073215"
        };

        for (String format : formats) {
            Assert.assertArrayEquals(
                    "07:32:15".getBytes(),
                    ValueTransformer.transform(format, ColumnType.TIME)
            );
        }
    }
    @Test
    public void testDateTimeTransform() throws Exception {
        String[] formats = {
                "1989-05-13 07:32:15"
              , "1989.05.13 07:32:15"
              , "1989/05/13 07:32:15"
              , "19890513 073215"
        };

        for (String format : formats) {
            Assert.assertArrayEquals(
                    "1989-05-13 07:32:15".getBytes(),
                    ValueTransformer.transform(format, ColumnType.DATETIME)
            );
        }
    }
    @Test
    public void testDecimalTranform() throws Exception {
        Assert.assertArrayEquals(
                Arrays.copyOfRange(Bytes.toBytes(0x807b2dl), 5, 8),
                ValueTransformer.transform("123.45", ColumnType.DECIMAL)
        );
        Assert.assertArrayEquals(
                Arrays.copyOfRange(Bytes.toBytes(0x7f84d2l), 5, 8),
                ValueTransformer.transform("-123.45", ColumnType.DECIMAL)
        );
        Assert.assertArrayEquals(
                Arrays.copyOfRange(Bytes.toBytes(0x800000l), 5, 8),
                ValueTransformer.transform("000.00", ColumnType.DECIMAL)
        );
        Assert.assertArrayEquals(
                Arrays.copyOfRange(Bytes.toBytes(0x800000l), 5, 8),
                ValueTransformer.transform("-000.00", ColumnType.DECIMAL)
        );
        Assert.assertArrayEquals(
                Arrays.copyOfRange(Bytes.toBytes(0x83e763l), 5, 8),
                ValueTransformer.transform("999.99", ColumnType.DECIMAL)
        );
        Assert.assertArrayEquals(
                Arrays.copyOfRange(Bytes.toBytes(0x7c189cl), 5, 8),
                ValueTransformer.transform("-999.99", ColumnType.DECIMAL)
        );
    }

    @Test
    public void testBytesFromDigits() {
        Assert.assertEquals(0, ValueTransformer.bytesFromDigits(0));
        Assert.assertEquals(1, ValueTransformer.bytesFromDigits(1));
        Assert.assertEquals(1, ValueTransformer.bytesFromDigits(2));
        Assert.assertEquals(2, ValueTransformer.bytesFromDigits(3));
        Assert.assertEquals(2, ValueTransformer.bytesFromDigits(4));
        Assert.assertEquals(3, ValueTransformer.bytesFromDigits(5));
        Assert.assertEquals(3, ValueTransformer.bytesFromDigits(6));
        Assert.assertEquals(4, ValueTransformer.bytesFromDigits(7));
        Assert.assertEquals(4, ValueTransformer.bytesFromDigits(8));
        Assert.assertEquals(4, ValueTransformer.bytesFromDigits(9));
        Assert.assertEquals(5, ValueTransformer.bytesFromDigits(10));
        Assert.assertEquals(5, ValueTransformer.bytesFromDigits(11));
        Assert.assertEquals(6, ValueTransformer.bytesFromDigits(12));
        Assert.assertEquals(6, ValueTransformer.bytesFromDigits(13));
        Assert.assertEquals(7, ValueTransformer.bytesFromDigits(14));
        Assert.assertEquals(7, ValueTransformer.bytesFromDigits(15));
        Assert.assertEquals(8, ValueTransformer.bytesFromDigits(16));
        Assert.assertEquals(8, ValueTransformer.bytesFromDigits(17));
        Assert.assertEquals(8, ValueTransformer.bytesFromDigits(18));
        Assert.assertEquals(9, ValueTransformer.bytesFromDigits(19));
        Assert.assertEquals(9, ValueTransformer.bytesFromDigits(20));
    }
}
