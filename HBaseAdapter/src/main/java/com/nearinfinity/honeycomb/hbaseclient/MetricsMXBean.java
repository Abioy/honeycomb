package com.nearinfinity.honeycomb.hbaseclient;

import java.util.Map;

public interface MetricsMXBean {
    void resetAll();

    long getHBaseCalls();

    long getHBaseTime();

    long getParseResultTime();

    long getParseRowMapTime();

    Map<String, Long> getStatistics();
}
