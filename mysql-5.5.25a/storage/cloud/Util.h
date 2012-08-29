#ifndef UTIL_H
#define UTIL_H
#include "sql_class.h"
#include <jni.h>

#include "Macros.h"
#include "m_string.h"

enum hbase_data_type { UNKNOWN_TYPE, JAVA_STRING, JAVA_LONG, JAVA_ULONG, JAVA_DOUBLE, JAVA_TIME, JAVA_DATE, JAVA_DATETIME };

hbase_data_type extract_field_type(Field *field);
bool is_unsigned_field(Field *field);
jclass find_jni_class(const char* class_name, JNIEnv* env);
void print_java_exception(JNIEnv* jni_env);
void extract_mysql_newdate(long tmp, MYSQL_TIME *time);
void extract_mysql_old_date(int tmp, MYSQL_TIME *time);
void extract_mysql_time(long tmp, MYSQL_TIME *time);
void extract_mysql_datetime(ulonglong tmp, MYSQL_TIME *time);

#endif
