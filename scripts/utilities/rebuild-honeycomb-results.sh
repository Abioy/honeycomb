#!/bin/sh
: ${MYSQL_HOME?"Need to set MYSQL_HOME environmental variable."}

# Clear out old results
rm $MYSQL_HOME/mysql-test/suite/honeycomb-test/honeycomb/r/*

# Record baseline results with InnoDB
cd $MYSQL_HOME/mysql-test
./mtr --suite=honeycomb-test/honeycomb           \
  --mysqld=--default-storage-engine=InnoDB \
  --mysqld=--character-set-server=utf8     \
  --mysqld=--collation-server=utf8_bin     \
  --record                                 \
issues        \
autoincrement \
bigint        \
binary        \
char          \
date          \
datetime      \
decimal       \
double        \
enum          \
explain       \
float         \
group_by      \
int           \
join_del_upd  \
joins         \
mediumint     \
primary_key   \
smallint      \
time          \
timestamp     \
tinyint       \
varbinary     \
varchar       \
year          \

# Move test results that are manually built
cp $MYSQL_HOME/mysql-test/suite/honeycomb-test/honeycomb/t/manual_results/* \
   $MYSQL_HOME/mysql-test/suite/honeycomb-test/honeycomb/r/
