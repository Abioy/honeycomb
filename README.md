# Honeycomb

```
      ,-.            __
      \_/         __/  \__
     {|||)<    __/  \__/  \__
      / \     /  \__/  \__/  \
      `-'     \__/  \__/  \__/
              /  \__/  \__/  \
              \__/  \__/  \__/
                 \__/  \__/
                    \__/

```

Honeycomb is an open source MySQL storage engine which stores tables into an external data store, or backend.  Honeycomb currently supports storing tables to HBase and an in-memory store.  See the [project page](http://nearinfinity.github.io/honeycomb/) for more details.

## System Requirements

The following system requirements must be installed and configured for Honeycomb execution:

* Oracle Java 6
* Hadoop 
  * Apache 1.0+ or CDH 4.3+ 	
* HBase 
  * Apache 0.94.3+ or CDH 4.3+

## Getting Started

**Install MySQL and Honeycomb binaries**

1. Export JAVA_HOME environment variable
2. Download the Linux 64-bit [tarball](https://s3.amazonaws.com/Honeycomb/releases/mysql-5.5.31-honeycomb-0.1-SNAPSHOT-linux-64bit.tar.gz)
3. Run `tar xzf mysql-5.5.31-honeycomb-0.1-SNAPSHOT-linux-64bit.tar.gz`
4. Change directory to `mysql-5.5.31-honeycomb-0.1-SNAPSHOT`

or, run the following in a shell:

```bash
curl -O https://s3.amazonaws.com/Honeycomb/releases/mysql-5.5.31-honeycomb-0.1-SNAPSHOT-linux-64bit.tar.gz
tar xzf mysql-5.5.31-honeycomb-0.1-SNAPSHOT-linux-64bit.tar.gz
cd mysql-5.5.31-honeycomb-0.1-SNAPSHOT
```

**Configure Honeycomb**

Honeycomb reads configuration from `honeycomb.xml`, located in the top level of the install binary

* If using the HBase backend, add the following to the `hbase-site.xml` on each HBase region server and restart the region server:

```XML
  <property>
    <name>hbase.coprocessor.region.classes</name>
    <value>org.apache.hadoop.hbase.coprocessor.example.BulkDeleteEndpoint</value>
  </property>
```

* If connecting to a remote HBase cluster, change the value of the `hbase.zookeeper.quorum` tag in the hbase backend configuration section of `honeycomb.xml` to the quorum location
* If you want to use the in-memory backend, change the value of the `defaultAdapter` element in `honeycomb.xml` to `memory`

**Start MySQL**

5. Execute `run-mysql-with-honeycomb-installed.sh`
6. Execute `bin/mysql -u root --socket=mysql.sock` 

or,

```bash
./run-mysql-with-honeycomb-installed.sh
bin/mysql -u root --socket=mysql.sock
```

Once Honeycomb is up and running, test it with:

```SQL
create table foo (x int, y varchar(20)) character set utf8 collate utf8_bin engine=honeycomb;
insert into foo values (1, 'Testing Honeycomb');
select * from foo;
```

## Documentation

* [Building from source](https://github.com/nearinfinity/honeycomb/wiki/Building-From-Source)
* [User documentation](https://github.com/nearinfinity/honeycomb/wiki)
* [Developer documentation](https://github.com/nearinfinity/honeycomb/wiki/Developer-Resources)

## License

The Honeycomb storage engine plugin (the C++ library) is released under [GPL v2.0](https://www.gnu.org/licenses/gpl-2.0.html).

The Honeycomb storage proxy and backends (the JVM libraries) are released under [Apache v2.0](https://www.apache.org/licenses/LICENSE-2.0.html).

## Issues & Contributing

Check out the [contributor guidelines](https://github.com/nearinfinity/honeycomb/blob/develop/CONTRIBUTING.md)
