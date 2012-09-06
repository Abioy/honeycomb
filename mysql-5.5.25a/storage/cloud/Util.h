#ifndef UTIL_H
#define UTIL_H

#define MYSQL_SERVER 1

#include "sql_class.h"
#include <jni.h>
#include <tztime.h>

#include "Macros.h"
#include "m_string.h"

bool is_unsigned_field(Field *field);
void extract_mysql_newdate(long tmp, MYSQL_TIME *time);
void extract_mysql_old_date(int32 tmp, MYSQL_TIME *time);
void extract_mysql_time(long tmp, MYSQL_TIME *time);
void extract_mysql_datetime(longlong tmp, MYSQL_TIME *time);
void extract_mysql_timestamp(long tmp, MYSQL_TIME *time, THD *thd);
void reverse_bytes(uchar *begin, uint length);
bool is_little_endian();
float floatGet(const uchar *ptr);
void make_big_endian(uchar *begin, uint length);
const char *extract_table_name_from_path(const char *path);

#endif
