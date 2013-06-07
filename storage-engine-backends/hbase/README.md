# HBase Backend

An HBase backend for the [Honeycomb Storage Engine](https://www.github.com/nearinfinity/honeycomb).

## Building

The HBase backend can be built with maven:

```Bash
mvn clean package
```

## Configuration

Add the following to your `honeycomb.xml`, along with configuration options:

```XML
<adapter name="hbase">
  <configuration>
  </configuration>
<adapter name="hbase">
```

The following are valid configuration options:

| Option         | Description   |
| -------------- | ------------- |
| `tableName`    | The name of the HBase table used to store Honeycomb data |
| `columnFamily` | The name of the column family used to store Honeycomb data (Keep as short as possible for better performance) |
| `tablePoolSize` | The number of HBase client connections to keep active (Match as closely as possible to expected number of concurrent client connections accessing Honeycomb tables) |
| `flushChangesImmediately` | Indicates whether writes and updates will flush to HBase immediately or be buffered (boolean value) |

Additionally, any valid HBase client configuration option will be honored.  The following are the most important:

| Option         | Description   |
| -------------- | ------------- |
| `hbase.client.scanner.caching` | The number of results to retrieve from HBase per scan (Set to the average number of MySQL rows per result set) |
| `hbase.client.write.buffer` | The amount of data to buffer before writing to HBase.  All writes will be written upon completion; this only affects how much large writes will be buffered |
| `hbase.zookeeper.quorum` | The location of the Zookeeper quorum |

Any tables created in the `hbase` database will automatically use the HBase backend.  If you configure the `defaultAdapter` tag in `honeycomb.xml` to `hbase`, then tables in other databases will use it as well.  For a more detailed explanation see the main Honeycomb documentation.  If you intend to run only the HBase backend it is recommended that you configure the `defaultAdapter` as `hbase`.

## Examples

```sql
use hbase;
create table in-hbase (x int, index(x)) engine=Honeycomb;
insert into in-hbase values (123);
```

### Testing

The HBase backend contains its own unit tests which can be run with `mvn test`.  Additionally, the integration tests will be run against the HBase backend if the `defaultAdapter` is configured as `hbase` in `honeycomb.xml`.  Please ensure these tests pass before contributing changes.

## License

Copyright © 2013 [Near Infinity Corporation](https://www.nearinfinity.com).

Distributed under the [Apache License](https://www.apache.org/licenses/LICENSE-2.0.html).
