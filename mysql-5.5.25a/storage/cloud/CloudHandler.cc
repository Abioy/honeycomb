#ifdef USE_PRAGMA_IMPLEMENTATION
#pragma implementation        // gcc: Class implementation
#endif

#include "sql_priv.h"
#include "sql_class.h"           // MYSQL_HANDLERTON_INTERFACE_VERSION
#include "CloudHandler.h"
#include "probes_mysql.h"
#include "sql_plugin.h"
#include "ha_cloud.h"
#include "JVMThreadAttach.h"
#include "mysql_time.h"
#include "Macros.h"

void reverse_bytes(uchar *begin, uchar *end);
bool is_little_endian();
void make_big_endian(uchar *begin, uchar *end);
longlong make_big_endian(longlong value);

/*
  If frm_error() is called in table.cc this is called to find out what file
  extensions exist for this handler.

  // TODO: Do any extensions exist for this handler? Doesn't seem like it. - ABC
*/
const char **CloudHandler::bas_ext() const
{
    static const char *cloud_exts[] =
    {
        NullS
    };

    return cloud_exts;
}

record_buffer *CloudHandler::create_record_buffer(unsigned int length)
{
  DBUG_ENTER("CloudHandler::create_record_buffer");
  record_buffer *r;
  if (!(r = (record_buffer*) my_malloc(sizeof(record_buffer), MYF(MY_WME))))
  {
    DBUG_RETURN(NULL);
  }

  r->length= (int)length;

  if (!(r->buffer= (uchar*) my_malloc(r->length, MYF(MY_WME))))
  {
    my_free(r);
    DBUG_RETURN(NULL);
  }

  DBUG_RETURN(r);
}

void CloudHandler::destroy_record_buffer(record_buffer *r)
{
  DBUG_ENTER("CloudHandler::destroy_record_buffer");
  my_free(r->buffer);
  my_free(r);
  DBUG_VOID_RETURN;
}

int CloudHandler::open(const char *name, int mode, uint test_if_locked)
{
    DBUG_ENTER("CloudHandler::open");

    if (!(share = get_share(name, table)))
    {
        DBUG_RETURN(1);
    }

    thr_lock_data_init(&share->lock, &lock, (void*) this);

    DBUG_RETURN(0);
}

int CloudHandler::close(void)
{
    DBUG_ENTER("CloudHandler::close");

    destroy_record_buffer(rec_buffer);

    DBUG_RETURN(free_share(share));
}

int CloudHandler::write_row(uchar *buf)
{
  DBUG_ENTER("CloudHandler::write_row");

    JNIEnv *jni_env;
    JVMThreadAttach attached_thread(&jni_env, this->jvm);
    my_bitmap_map *old_map = dbug_tmp_use_all_columns(table, table->read_set);

  // Boilerplate stuff every engine has to do on writes

  if (share->crashed)
    DBUG_RETURN(HA_ERR_CRASHED_ON_USAGE);

  ha_statistic_increment(&SSV::ha_write_count);

  if (table->timestamp_field_type & TIMESTAMP_AUTO_SET_ON_INSERT)
    table->timestamp_field->set_time();

  jclass adapter_class = jni_env->FindClass("com/nearinfinity/mysqlengine/jni/HBaseAdapter");
  jmethodID write_row_method = jni_env->GetStaticMethodID(adapter_class, "writeRow", "(Ljava/lang/String;Ljava/util/Map;)Z");
  jstring java_table_name = this->string_to_java_string(jni_env, this->share->table_alias);
  jobject java_map = this->create_java_map(jni_env);

  char attribute_buffer[1024];
  String attribute(attribute_buffer, sizeof(attribute_buffer), &my_charset_bin);

  for (Field **field_ptr=table->field; *field_ptr; field_ptr++)
  {
    Field * field = *field_ptr;

    memset(rec_buffer->buffer, 0, rec_buffer->length);

    const bool was_null= field->is_null();

    if (was_null)
    {
      field->set_default();
      field->set_notnull();
    }

    int fieldType = field->type();
    uint actualFieldSize = field->field_length;

    if (fieldType == MYSQL_TYPE_LONG
				|| fieldType == MYSQL_TYPE_SHORT
				|| fieldType == MYSQL_TYPE_TINY
				|| fieldType == MYSQL_TYPE_LONGLONG
				|| fieldType == MYSQL_TYPE_INT24
				|| fieldType == MYSQL_TYPE_YEAR)
		{
			longlong field_value = make_big_endian(field->val_int());
			actualFieldSize = sizeof(longlong);
			memcpy(rec_buffer->buffer, &field_value, sizeof(longlong));
		}
		else if (fieldType == MYSQL_TYPE_DOUBLE
				|| fieldType == MYSQL_TYPE_FLOAT
				|| fieldType == MYSQL_TYPE_DECIMAL
				|| fieldType == MYSQL_TYPE_NEWDECIMAL)
		{
			double field_value = field->val_real();
			actualFieldSize = sizeof(double);
			make_big_endian(field->ptr, field->ptr + sizeof(double));
			memcpy(rec_buffer->buffer, &field_value, sizeof(double));
		}
		else if (fieldType == MYSQL_TYPE_VARCHAR
				|| fieldType == MYSQL_TYPE_STRING
				|| fieldType == MYSQL_TYPE_VAR_STRING
				|| fieldType == MYSQL_TYPE_BLOB
				|| fieldType == MYSQL_TYPE_TINY_BLOB
				|| fieldType == MYSQL_TYPE_MEDIUM_BLOB
				|| fieldType == MYSQL_TYPE_LONG_BLOB
				|| fieldType == MYSQL_TYPE_ENUM)
		{
			ulonglong fieldLength = field->binary() ? field->data_length() : field->field_length;

			char attribute_buffer[fieldLength];
			String attribute(attribute_buffer, sizeof(attribute_buffer), &my_charset_bin);
			field->val_str(&attribute);
			actualFieldSize = attribute.length();
			memcpy(rec_buffer->buffer, field->ptr, field->field_length);
		}
		else if (fieldType == MYSQL_TYPE_TIME
				|| fieldType == MYSQL_TYPE_DATE
				|| fieldType == MYSQL_TYPE_NEWDATE
				|| fieldType == MYSQL_TYPE_DATETIME)
		{

			// Use number_to_datetime to convert from a longlong into a MySQL datetime object on reads - ABC
			MYSQL_TIME mysql_time;
			field->get_time(&mysql_time);
			longlong timeValue = make_big_endian(TIME_to_ulonglong_time(&mysql_time));
			actualFieldSize = sizeof(longlong);
			memcpy(rec_buffer->buffer, &timeValue, sizeof(longlong));
		}
		else
		{
			memcpy(rec_buffer->buffer, field->ptr, field->field_length);
		}

    if (was_null)
    {
      field->set_null();
    }

    jstring field_name = this->string_to_java_string(jni_env, field->field_name);
    jbyteArray java_bytes = this->convert_value_to_java_bytes(jni_env, rec_buffer->buffer, actualFieldSize);

    java_map_insert(jni_env, java_map, field_name, java_bytes);
  }

  jni_env->CallStaticBooleanMethod(adapter_class, write_row_method, java_table_name, java_map);

  dbug_tmp_restore_column_map(table->read_set, old_map);

  DBUG_RETURN(0);
}

int CloudHandler::update_row(const uchar *old_data, uchar *new_data)
{
    DBUG_ENTER("CloudHandler::update_row");
    DBUG_RETURN(HA_ERR_WRONG_COMMAND);
}

int CloudHandler::delete_row(const uchar *buf)
{
    DBUG_ENTER("CloudHandler::delete_row");
    DBUG_RETURN(HA_ERR_WRONG_COMMAND);
}

int CloudHandler::rnd_init(bool scan)
{
    DBUG_ENTER("CloudHandler::rnd_init");

    const char* table_name = this->table->alias;

    JVMThreadAttach attached_thread(&this->env, this->jvm);

    jclass adapter_class = this->env->FindClass("com/nearinfinity/mysqlengine/jni/HBaseAdapter");
    jmethodID start_scan_method = this->env->GetStaticMethodID(adapter_class, "startScan", "(Ljava/lang/String;)J");
    jstring java_table_name = this->string_to_java_string(this->env, table_name);
    
    this->curr_scan_id = this->env->CallStaticLongMethod(adapter_class, start_scan_method, java_table_name);
    
    DBUG_RETURN(0);
}

int CloudHandler::external_lock(THD *thd, int lock_type)
{
    DBUG_ENTER("CloudHandler::external_lock");
    DBUG_RETURN(0);
}

int CloudHandler::rnd_next(uchar *buf)
{
    int rc = 0;
    my_bitmap_map *orig_bitmap;
    
    ha_statistic_increment(&SSV::ha_read_rnd_next_count);
    DBUG_ENTER("CloudHandler::rnd_next");

    orig_bitmap= dbug_tmp_use_all_columns(table, table->write_set);
    
    MYSQL_READ_ROW_START(table_share->db.str, table_share->table_name.str, TRUE);

    memset(buf, 0, table->s->null_bytes);

    JVMThreadAttach attached_thread(&this->env, this->jvm);
    
    jclass adapter_class = this->env->FindClass("com/nearinfinity/mysqlengine/jni/HBaseAdapter");
    jmethodID next_row_method = this->env->GetStaticMethodID(adapter_class, "nextRow", "(J)Lcom/nearinfinity/mysqlengine/jni/Row;");
    jlong java_scan_id = curr_scan_id;
    jobject row = this->env->CallStaticObjectMethod(adapter_class, next_row_method, java_scan_id);

    jclass row_class = this->env->FindClass("com/nearinfinity/mysqlengine/jni/Row");
    jmethodID get_keys_method = this->env->GetMethodID(row_class, "getKeys", "()[Ljava/lang/String;");
    jmethodID get_vals_method = this->env->GetMethodID(row_class, "getValues", "()[[B");
    jmethodID get_uuid_method = this->env->GetMethodID(row_class, "getUUID", "()[B");

    jarray keys = (jarray) this->env->CallObjectMethod(row, get_keys_method);
    jarray vals = (jarray) this->env->CallObjectMethod(row, get_vals_method);
    jbyteArray uuid = (jbyteArray) this->env->CallObjectMethod(row, get_uuid_method);

    if (keys == NULL || vals == NULL) {
      dbug_tmp_restore_column_map(table->write_set, orig_bitmap);
      DBUG_RETURN(HA_ERR_END_OF_FILE);
    }

    this->ref = (uchar*) this->env->GetByteArrayElements(uuid, JNI_FALSE);
    this->ref_length = 16;

    store_field_values(buf, keys, vals);

    dbug_tmp_restore_column_map(table->write_set, orig_bitmap);
    
    stats.records++;
    MYSQL_READ_ROW_DONE(rc);
    
    DBUG_RETURN(rc);
}

void CloudHandler::store_field_values(uchar *buf, jarray keys, jarray vals)
{
  jboolean is_copy = JNI_FALSE;
  jsize size = this->env->GetArrayLength(keys);

  for (jsize i = 0 ; i < size ; i++)
  {
    jstring key_string = (jstring) this->env->GetObjectArrayElement((jobjectArray) keys, i);
    const char* key = java_to_string(key_string);
    jbyteArray byte_array = (jbyteArray) this->env->GetObjectArrayElement((jobjectArray) vals, i);
    jsize val_length = this->env->GetArrayLength(byte_array);
    char* val = (char*) this->env->GetByteArrayElements(byte_array, &is_copy);

    for(int j = 0; j < table->s->fields; j++)
    {
      Field *field = table->field[j];
      if (strcmp(key, field->field_name) != 0)
      {
        continue;
      }

      this->store_field_value(field, buf, key, val, val_length);
      break;
    }

    this->env->ReleaseStringUTFChars(key_string, key);
  }
}

void CloudHandler::store_field_value(Field* field, uchar* buf, const char* key, char* val, jsize val_length)
{
  my_ptrdiff_t offset = (my_ptrdiff_t) (buf - this->table->record[0]);
  enum_field_types field_type = field->type();
  field->move_field_offset(offset);

  if (field_type == MYSQL_TYPE_LONG || 
      field_type == MYSQL_TYPE_SHORT ||
      field_type == MYSQL_TYPE_LONGLONG ||
      field_type == MYSQL_TYPE_INT24 ||
      field_type == MYSQL_TYPE_TINY || 
      field_type == MYSQL_TYPE_ENUM)
  {
    longlong long_value = __builtin_bswap64(*(longlong*)val);
    field->store(long_value, false);
  }
  else if(field_type == MYSQL_TYPE_VARCHAR ||
      field_type == MYSQL_TYPE_STRING ||
      field_type == MYSQL_TYPE_VAR_STRING)
  {
    field->store(val, val_length, &my_charset_bin);
  }

  field->move_field_offset(-offset);
}

void CloudHandler::position(const uchar *record)
{
    DBUG_ENTER("CloudHandler::position");
    DBUG_VOID_RETURN;
}

int CloudHandler::rnd_pos(uchar *buf, uchar *pos)
{
  int rc = 0;
  DBUG_ENTER("CloudHandler::rnd_pos");
  ha_statistic_increment(&SSV::ha_read_rnd_count); // Boilerplate
  MYSQL_READ_ROW_START(table_share->db.str, table_share->table_name.str, FALSE);

  JVMThreadAttach attached_thread(&this->env, this->jvm);
  
  jclass adapter_class = this->env->FindClass("com/nearinfinity/mysqlengine/jni/HBaseAdapter");
  jmethodID get_row_method = this->env->GetStaticMethodID(adapter_class, "getRow", "(JLjava/lang/String;[B)Lcom/nearinfinity/mysqlengine/jni/Row;");
  jlong java_scan_id = curr_scan_id;
  jobject row = this->env->CallStaticObjectMethod(adapter_class, get_row_method, java_scan_id, pos);

  jclass row_class = this->env->FindClass("com/nearinfinity/mysqlengine/jni/Row");
  jmethodID get_keys_method = this->env->GetMethodID(row_class, "getKeys", "()[Ljava/lang/String;");
  jmethodID get_vals_method = this->env->GetMethodID(row_class, "getValues", "()[[B");

  jarray keys = (jarray) this->env->CallObjectMethod(row, get_keys_method);
  jarray vals = (jarray) this->env->CallObjectMethod(row, get_vals_method);

  jboolean is_copy = JNI_FALSE;

  if (keys == NULL || vals == NULL) {
    DBUG_RETURN(HA_ERR_WRONG_COMMAND);
  }

  store_field_values(buf, keys, vals)

  MYSQL_READ_ROW_DONE(rc);
  DBUG_RETURN(rc);
}

int CloudHandler::rnd_end()
{
  DBUG_ENTER("CloudHandler::rnd_end");

  JVMThreadAttach attached_thread(&this->env, this->jvm);
  
  jclass adapter_class = this->env->FindClass("com/nearinfinity/mysqlengine/jni/HBaseAdapter");
  jmethodID end_scan_method = this->env->GetStaticMethodID(adapter_class, "endScan", "(J)V");
  jlong java_scan_id = curr_scan_id;
  
  this->env->CallStaticVoidMethod(adapter_class, end_scan_method, java_scan_id);

  curr_scan_id = -1;
  DBUG_RETURN(0);
}

int CloudHandler::create(const char *name, TABLE *table_arg,
                         HA_CREATE_INFO *create_info)
{
    DBUG_ENTER("CloudHandler::create");
    JNIEnv *jni_env;

    JVMThreadAttach attached_thread(&jni_env, this->jvm);
    jclass adapter_class = jni_env->FindClass("com/nearinfinity/mysqlengine/jni/HBaseAdapter");
    if (adapter_class == NULL)
    {
      if(jni_env->ExceptionCheck() == JNI_TRUE)
      {
        jthrowable throwable = jni_env->ExceptionOccurred();
        jclass objClazz = jni_env->GetObjectClass(throwable);
        jmethodID methodId = jni_env->GetMethodID(objClazz, "toString", "()Ljava/lang/String;");
        jstring result = (jstring)jni_env->CallObjectMethod(throwable, methodId);
        const char* string = jni_env->GetStringUTFChars(result, NULL);
        jni_env->ExceptionDescribe();
      }
      ERROR(("Could not find adapter class HBaseAdapter"));
      DBUG_RETURN(1);
    }

    const char* table_name = create_info->alias;

    jclass list_class = jni_env->FindClass("java/util/LinkedList");
    jmethodID list_constructor = jni_env->GetMethodID(list_class, "<init>", "()V");
    jobject columns = jni_env->NewObject(list_class, list_constructor);
    jmethodID add_column = jni_env->GetMethodID(list_class, "add", "(Ljava/lang/Object;)Z");

    for (Field **field = table_arg->field ; *field ; field++)
    {
      jni_env->CallBooleanMethod(columns, add_column, string_to_java_string(jni_env, (*field)->field_name));
    }

    jmethodID create_table_method = jni_env->GetStaticMethodID(adapter_class, "createTable", "(Ljava/lang/String;Ljava/util/List;)Z");
    jboolean result = jni_env->CallStaticBooleanMethod(adapter_class, create_table_method, string_to_java_string(jni_env, table_name), columns);
    INFO(("Result of createTable: %d", result));

    DBUG_RETURN(0);
}

THR_LOCK_DATA **CloudHandler::store_lock(THD *thd, THR_LOCK_DATA **to, enum thr_lock_type lock_type)
{
    if (lock_type != TL_IGNORE && lock.type == TL_UNLOCK)
        lock.type=lock_type;
    *to++= &lock;
    return to;
}

/*
  Free lock controls.
*/
int CloudHandler::free_share(CloudShare *share)
{
    DBUG_ENTER("CloudHandler::free_share");
    mysql_mutex_lock(cloud_mutex);
    int result_code= 0;
    if (!--share->use_count)
    {
        my_hash_delete(cloud_open_tables, (uchar*) share);
        thr_lock_delete(&share->lock);
        mysql_mutex_destroy(&share->mutex);
        my_free(share);
    }
    mysql_mutex_unlock(cloud_mutex);

    DBUG_RETURN(result_code);
}

int CloudHandler::info(uint)
{
    DBUG_ENTER("CloudHandler::info");
    if (stats.records < 2) 
      stats.records= 2;
    DBUG_RETURN(0);
}

CloudShare *CloudHandler::get_share(const char *table_name, TABLE *table)
{
    CloudShare *share;
    char meta_file_name[FN_REFLEN];
    MY_STAT file_stat;                /* Stat information for the data file */
    char *tmp_path_name;
    char *tmp_alias;
    uint path_length, alias_length;

  rec_buffer= create_record_buffer(table->s->reclength);

  if (!rec_buffer)
  {
    DBUG_PRINT("CloudHandler", ("Ran out of memory while allocating record buffer"));

    return NULL;
  }

    mysql_mutex_lock(cloud_mutex);
    path_length=(uint) strlen(table_name);
    alias_length=(uint) strlen(table->alias);

    /*
    If share is not present in the hash, create a new share and
    initialize its members.
    */
    if (!(share=(CloudShare*) my_hash_search(cloud_open_tables,
                (uchar*) table_name,
                path_length)))
    {
        if (!my_multi_malloc(MYF(MY_WME | MY_ZEROFILL),
                             &share, sizeof(*share),
                             &tmp_path_name, path_length+1,
                             &tmp_alias, alias_length+1,
                             NullS))
        {
            mysql_mutex_unlock(cloud_mutex);
            return NULL;
        }
    }

    share->use_count= 0;
    share->table_path_length= path_length;
    share->path_to_table= tmp_path_name;
    share->table_alias= tmp_alias;
    share->crashed= FALSE;
    share->rows_recorded= 0;

    if (my_hash_insert(cloud_open_tables, (uchar*) share))
        goto error;
    thr_lock_init(&share->lock);

    share->use_count++;
    mysql_mutex_unlock(cloud_mutex);

    return share;

error:
    mysql_mutex_unlock(cloud_mutex);
    my_free(share);

    return NULL;
}

int CloudHandler::extra(enum ha_extra_function operation)
{
  DBUG_ENTER("CloudHandler::extra");
  DBUG_RETURN(0);
}

const char* CloudHandler::java_to_string(jstring j_str)
{
    const char* str = this->env->GetStringUTFChars(j_str, NULL);
    return str;
}

jstring CloudHandler::string_to_java_string(JNIEnv *jni_env, const char* string)
{
  return jni_env->NewStringUTF(string);
}

jobject CloudHandler::create_java_map(JNIEnv* jni_env)
{
  jclass map_class = jni_env->FindClass("java/util/HashMap");
  jmethodID constructor = jni_env->GetMethodID(map_class, "<init>", "()V");
  jobject java_map = jni_env->NewObject(map_class, constructor);
  return java_map;
}

jobject CloudHandler::java_map_insert(JNIEnv *jni_env, jobject java_map, jstring key, jbyteArray value)
{
  jclass map_class = jni_env->FindClass("java/util/HashMap");
  jmethodID put_method = jni_env->GetMethodID(map_class, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");

  return jni_env->CallObjectMethod(java_map, put_method, key, value);
}

jbyteArray CloudHandler::convert_value_to_java_bytes(JNIEnv *jni_env, uchar* value, uint32 length)
{
  jbyteArray byteArray = jni_env->NewByteArray(length);
  jbyte *java_bytes = jni_env->GetByteArrayElements(byteArray, 0);

  memcpy(java_bytes, value, length);

  jni_env->SetByteArrayRegion(byteArray, 0, length, java_bytes);

  return byteArray;
}


void reverse_bytes(uchar *begin, uchar *end)
{
	for (; begin <= end; begin++, end--)
	{
		uchar *tmp = end;
		*end = *begin;
		*begin = *tmp;
	}
}

bool is_little_endian()
{
    union {
        uint32_t i;
        char c[4];
    } bint = {0x01020304};

    return bint.c[0] == 4;
}

void make_big_endian(uchar *begin, uchar *end)
{
	if (is_little_endian())
	{
		reverse_bytes(begin, end);
	}
}

longlong make_big_endian(longlong value)
{
	return is_little_endian() ? __builtin_bswap64(value) : value;
}
