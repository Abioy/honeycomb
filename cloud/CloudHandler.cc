#ifdef USE_PRAGMA_IMPLEMENTATION
#pragma implementation        // gcc: Class implementation
#endif

#include "sql_priv.h"
#include "sql_class.h"           // MYSQL_HANDLERTON_INTERFACE_VERSION
#include "CloudHandler.h"
#include "probes_mysql.h"
#include "sql_plugin.h"
#include "ha_cloud.h"
#include "mysql_time.h"
#include "m_string.h"

#include <sys/time.h>

const char **CloudHandler::bas_ext() const
{
  static const char *cloud_exts[] = { NullS };

  return cloud_exts;
}

int CloudHandler::open(const char *path, int mode, uint test_if_locked)
{
  DBUG_ENTER("CloudHandler::open");

  if (!(share = get_share(path, table)))
  {
    DBUG_RETURN(1);
  }

  thr_lock_data_init(&share->lock, &lock, (void*) this);

  DBUG_RETURN(0);
}

int CloudHandler::close(void)
{
  DBUG_ENTER("CloudHandler::close");

  DBUG_RETURN(free_share(share));
}

/*
 This will be called in a table scan right before the previous ::rnd_next()
 call.
 */
int CloudHandler::update_row(const uchar *old_data, uchar *new_data)
{
  DBUG_ENTER("CloudHandler::update_row");

  ha_statistic_increment(&SSV::ha_update_count);

  // TODO: The next two lines should really be some kind of transaction.
  delete_row(old_data);
  write_row(new_data);

  if (table->timestamp_field_type & TIMESTAMP_AUTO_SET_ON_UPDATE)
    table->timestamp_field->set_time();

  this->flush_writes();
  DBUG_RETURN(0);
}

int CloudHandler::delete_row(const uchar *buf)
{
  DBUG_ENTER("CloudHandler::delete_row");
  ha_statistic_increment(&SSV::ha_delete_count);
  jclass adapter_class = this->adapter();
  jmethodID delete_row_method = find_static_method(adapter_class, "deleteRow", "(J)Z",this->env);
  this->env->CallStaticBooleanMethod(adapter_class, delete_row_method, this->curr_scan_id);
  DBUG_RETURN(0);
}

bool CloudHandler::start_bulk_delete()
{
  DBUG_ENTER("CloudHandler::start_bulk_delete");

  attach_thread();

  DBUG_RETURN(true);
}

int CloudHandler::end_bulk_delete()
{
  DBUG_ENTER("CloudHandler::end_bulk_delete");

  jclass adapter_class = this->adapter();
  detach_thread();

  DBUG_RETURN(0);
}

int CloudHandler::delete_all_rows()
{
  DBUG_ENTER("CloudHandler::delete_all_rows");

  attach_thread();

  jstring table_name = this->table_name();
  jclass adapter_class = this->adapter();
  jmethodID delete_rows_method = find_static_method(adapter_class, "deleteAllRows", "(Ljava/lang/String;)I",this->env);

  int count = this->env->CallStaticIntMethod(adapter_class, delete_rows_method,
      table_name);
  jmethodID set_count_method = find_static_method(adapter_class, "setRowCount", "(Ljava/lang/String;J)V",this->env);
  this->env->CallStaticVoidMethod(adapter_class, set_count_method, table_name,
      (jlong) 0);
  this->flush_writes();

  detach_thread();

  DBUG_RETURN(0);
}

int CloudHandler::truncate()
{
  DBUG_ENTER("CloudHandler::truncate");

  DBUG_RETURN(delete_all_rows());
}

void CloudHandler::drop_table(const char *path)
{
  close();

  delete_table(path);
}

int CloudHandler::delete_table(const char *path)
{
  DBUG_ENTER("CloudHandler::delete_table");

  attach_thread();

  char* table = extract_table_name_from_path(path);
  jstring table_name = string_to_java_string(table);
  ARRAY_DELETE(table);

  jclass adapter_class = this->adapter();
  jmethodID drop_table_method = find_static_method(adapter_class, "dropTable", "(Ljava/lang/String;)Z",this->env);

  this->env->CallStaticBooleanMethod(adapter_class, drop_table_method,
      table_name);

  detach_thread();

  DBUG_RETURN(0);
}

int CloudHandler::rnd_init(bool scan)
{
  DBUG_ENTER("CloudHandler::rnd_init");

  attach_thread();

  jclass adapter_class = this->adapter();
  jmethodID start_scan_method = find_static_method(adapter_class, "startScan", "(Ljava/lang/String;Z)J",this->env);
  jstring table_name = this->table_name();

  jboolean java_scan_boolean = scan ? JNI_TRUE : JNI_FALSE;

  this->curr_scan_id = this->env->CallStaticLongMethod(adapter_class,
      start_scan_method, table_name, java_scan_boolean);

  this->performing_scan = scan;

  DBUG_RETURN(0);
}

int CloudHandler::rnd_next(uchar *buf)
{
  int rc = 0;

  ha_statistic_increment(&SSV::ha_read_rnd_next_count);
  DBUG_ENTER("CloudHandler::rnd_next");

  MYSQL_READ_ROW_START(table_share->db.str, table_share->table_name.str, TRUE);

  memset(buf, 0, table->s->null_bytes);
  jclass adapter_class = this->adapter();
  jmethodID next_row_method = find_static_method(adapter_class, "nextRow", "(J)L" MYSQLENGINE "Row;",this->env);
  jobject row = this->env->CallStaticObjectMethod(adapter_class,
      next_row_method, this->curr_scan_id);

  jclass row_class = find_jni_class("Row", this->env);
  jmethodID get_row_map_method = this->env->GetMethodID(row_class, "getRowMap",
      "()Ljava/util/Map;");
  jmethodID get_uuid_method = this->env->GetMethodID(row_class, "getUUID",
      "()[B");

  jobject row_map = this->env->CallObjectMethod(row, get_row_map_method);

  if (row_map == NULL)
  {
    this->table->status = STATUS_NOT_FOUND;
    DBUG_RETURN(HA_ERR_END_OF_FILE);
  }

  this->store_uuid_ref(row, get_uuid_method);
  java_to_sql(buf, row_map);
  this->table->status = 0;

  MYSQL_READ_ROW_DONE(rc);

  DBUG_RETURN(rc);
}

void CloudHandler::store_field_value(Field *field, char *val, int val_length)
{
  int type = field->real_type();

  if (!is_unsupported_field(type))
  {
    if (is_integral_field(type))
    {
      long long long_value = *(long long*) val;
      if (is_little_endian())
      {
        long_value = __builtin_bswap64(long_value);
      }
      field->store(long_value, false);
    }
    else if (is_byte_field(type))
    {
      field->store(val, val_length, &my_charset_bin);
    }
    else if (is_date_or_time_field(type))
    {
      if (type == MYSQL_TYPE_TIME)
      {
        MYSQL_TIME mysql_time;
        int warning;
        str_to_time(val, val_length, &mysql_time, &warning);
        field->store_time(&mysql_time, mysql_time.time_type);
      }
      else
      {
        MYSQL_TIME mysql_time;
        int was_cut;
        str_to_datetime(val, val_length, &mysql_time, TIME_FUZZY_DATE, &was_cut);
        field->store_time(&mysql_time, mysql_time.time_type);
      }
    }
    else if (is_decimal_field(type))
    {
      // TODO: Is this reliable? Field_decimal doesn't seem to have these members. Potential crash for old decimal types. - ABC
      uint precision = ((Field_new_decimal*) field)->precision;
      uint scale = ((Field_new_decimal*) field)->dec;
      my_decimal decimal_val;
      binary2my_decimal(0, (const uchar *) val, &decimal_val, precision, scale);
      ((Field_new_decimal *) field)->store_value(
          (const my_decimal*) &decimal_val);
    }
    else if (is_floating_point_field(type))
    {
      double double_value;
      if (is_little_endian())
      {
        long long* long_ptr = (long long*) val;
        longlong swapped_long = __builtin_bswap64(*long_ptr);
        double_value = *(double*) &swapped_long;
      } else
      {
        double_value = *(double*) val;
      }
      field->store(double_value);
    }
  }
}

void CloudHandler::java_to_sql(uchar* buf, jobject row_map)
{
  jboolean is_copy = JNI_FALSE;
  my_bitmap_map *orig_bitmap;
  orig_bitmap = dbug_tmp_use_all_columns(table, table->write_set);

  for (int i = 0; i < table->s->fields; i++)
  {
    Field *field = table->field[i];
    field->set_notnull(); // for some reason the field was inited as null during rnd_pos
    const char* key = field->field_name;
    jstring java_key = string_to_java_string(key);
    jbyteArray java_val = java_map_get(row_map, java_key, this->env);
    if (java_val == NULL)
    {
      field->set_null();
      continue;
    }
    char* val = (char*) this->env->GetByteArrayElements(java_val, &is_copy);
    jsize val_length = this->env->GetArrayLength(java_val);

    my_ptrdiff_t offset = (my_ptrdiff_t) (buf - this->table->record[0]);
    field->move_field_offset(offset);

    this->store_field_value(field, val, val_length);

    field->move_field_offset(-offset);
    this->env->ReleaseByteArrayElements(java_val, (jbyte*) val, 0);
  }

  dbug_tmp_restore_column_map(table->write_set, orig_bitmap);

  return;
}

int CloudHandler::external_lock(THD *thd, int lock_type)
{
  DBUG_ENTER("CloudHandler::external_lock");
  DBUG_RETURN(0);
}

void CloudHandler::position(const uchar *record)
{
  DBUG_ENTER("CloudHandler::position");
  DBUG_VOID_RETURN;
}

int CloudHandler::rnd_pos(uchar *buf, uchar *pos)
{
  int rc = 0;
  ha_statistic_increment(&SSV::ha_read_rnd_count); // Boilerplate
  DBUG_ENTER("CloudHandler::rnd_pos");

  MYSQL_READ_ROW_START(table_share->db.str, table_share->table_name.str, FALSE);

  jclass adapter_class = this->adapter();
  jmethodID get_row_method = find_static_method(adapter_class, "getRow", "(J[B)L" MYSQLENGINE "Row;",this->env);
  jbyteArray uuid = convert_value_to_java_bytes(pos, 16, this->env);
  jobject row = this->env->CallStaticObjectMethod(adapter_class, get_row_method,
      this->curr_scan_id, uuid);

  jclass row_class = find_jni_class("Row", this->env);
  jmethodID get_row_map_method = this->env->GetMethodID(row_class, "getRowMap",
      "()Ljava/util/Map;");

  jobject row_map = this->env->CallObjectMethod(row, get_row_map_method);

  if (row_map == NULL)
  {
    this->table->status = STATUS_NOT_FOUND;
    DBUG_RETURN(HA_ERR_END_OF_FILE);
  }

  java_to_sql(buf, row_map);
  this->table->status = 0;

  MYSQL_READ_ROW_DONE(rc);
  DBUG_RETURN(rc);
}

int CloudHandler::rnd_end()
{
  DBUG_ENTER("CloudHandler::rnd_end");

  this->end_scan();
  this->detach_thread();
  this->reset_scan_counter();

  DBUG_RETURN(0);
}

void CloudHandler::start_bulk_insert(ha_rows rows)
{
  DBUG_ENTER("CloudHandler::start_bulk_insert");

  attach_thread();
  Logging::info("%d rows to be inserted.", rows);

  DBUG_VOID_RETURN;
}

int CloudHandler::end_bulk_insert()
{
  DBUG_ENTER("CloudHandler::end_bulk_insert");

  this->flush_writes();
  jclass adapter_class = this->adapter();
  jmethodID update_count_method = find_static_method(adapter_class, "incrementRowCount", "(Ljava/lang/String;J)V",this->env);
  jstring table_name = this->table_name();
  this->env->CallStaticVoidMethod(adapter_class, update_count_method,
      table_name, (jlong) this->rows_written);
  this->rows_written = 0;

  detach_thread();
  DBUG_RETURN(0);
}

char* CloudHandler::index_name(TABLE* table, uint key)
{
    KEY *pos = table->key_info + key;
    KEY_PART_INFO *key_part = pos->key_part;
    KEY_PART_INFO *key_part_end = key_part + pos->key_parts;
    return this->index_name(key_part, key_part_end, pos->key_parts);
}

char* CloudHandler::index_name(KEY_PART_INFO* key_part, KEY_PART_INFO* key_part_end, uint key_parts)
{
    size_t size = 0;

    KEY_PART_INFO* start = key_part;
    for (; key_part != key_part_end; key_part++)
    {
      Field *field = key_part->field;
      size += strlen(field->field_name);
    }

    key_part = start;
    char* name = new char[size + key_parts];
    memset(name, 0, size + key_parts);
    for (; key_part != key_part_end; key_part++)
    {
      Field *field = key_part->field;
      strcat(name, field->field_name);
      if ((key_part+1) != key_part_end)
      {
        strcat(name, ",");
      }
    }

    return name;
}

jobject CloudHandler::create_multipart_keys(TABLE* table_arg)
{
  uint keys = table_arg->s->keys;
  jclass multipart_keys_class = this->env->FindClass(HBASECLIENT "TableMultipartKeys");
  jmethodID constructor = this->env->GetMethodID(multipart_keys_class, "<init>", "()V");
  jmethodID add_key_method = this->env->GetMethodID(multipart_keys_class, "addMultipartKey", "(Ljava/lang/String;)V");
  jobject java_keys = this->env->NewObject(multipart_keys_class, constructor);

  for (uint key = 0; key < keys; key++)
  {
    char* name = index_name(table_arg, key);
    this->env->CallVoidMethod(java_keys, add_key_method, string_to_java_string(name));
    ARRAY_DELETE(name);
  }

  return java_keys;
}

int CloudHandler::create(const char *path, TABLE *table_arg, HA_CREATE_INFO *create_info)
{
  DBUG_ENTER("CloudHandler::create");
  attach_thread();

  jobject java_keys = this->create_multipart_keys(table_arg);
  jclass adapter_class = this->adapter();
  if (adapter_class == NULL)
  {
    my_error(ER_CREATE_FILEGROUP_FAILED, MYF(0), "Could not find adapter class HBaseAdapter");
    print_java_exception(this->env);
    detach_thread();
    DBUG_RETURN(HA_ERR_INTERNAL_ERROR);
  }

  char* table_name = extract_table_name_from_path(path);
  jstring jtable_name = string_to_java_string(table_name);
  ARRAY_DELETE(table_name);

  jobject columnMap = create_java_map(this->env);
  FieldMetadata metadata(this->env);

  for (Field **field = table_arg->field; *field; field++)
  {
    switch ((*field)->real_type())
    {
    case MYSQL_TYPE_STRING:
    case MYSQL_TYPE_VARCHAR:
    case MYSQL_TYPE_BLOB:
      if (strncmp((*field)->charset()->name, "utf8_bin", 8) != 0
          && (*field)->binary() == false)
      {
        my_error(ER_CREATE_FILEGROUP_FAILED, MYF(0), "table. Required: character set utf8 collate utf8_bin");
        detach_thread();
        DBUG_RETURN(HA_WRONG_CREATE_OPTION);
      }
      break;
    default:
      break;
    }

    jobject java_metadata_obj = metadata.get_field_metadata(*field, table_arg);
    java_map_insert(columnMap, string_to_java_string((*field)->field_name), java_metadata_obj, this->env);
  }

  jmethodID create_table_method = find_static_method(adapter_class, "createTable", "(Ljava/lang/String;Ljava/util/Map;L" HBASECLIENT "TableMultipartKeys;)Z",this->env);
  this->env->CallStaticBooleanMethod(adapter_class, create_table_method, jtable_name, columnMap, java_keys);
  print_java_exception(this->env);

  DBUG_RETURN(0);
}

THR_LOCK_DATA **CloudHandler::store_lock(THD *thd, THR_LOCK_DATA **to,
    enum thr_lock_type lock_type)
{
  if (lock_type != TL_IGNORE && lock.type == TL_UNLOCK)
    lock.type = lock_type;
  *to++ = &lock;
  return to;
}

/*
 Free lock controls.
 */
int CloudHandler::free_share(CloudShare *share)
{
  DBUG_ENTER("CloudHandler::free_share");
  mysql_mutex_lock(cloud_mutex);
  int result_code = 0;
  if (!--share->use_count)
  {
    my_hash_delete(cloud_open_tables, (uchar*) share);
    thr_lock_delete(&share->lock);
    my_free(share);
  }

  mysql_mutex_unlock(cloud_mutex);

  DBUG_RETURN(result_code);
}

ha_rows CloudHandler::records_in_range(uint inx, key_range *min_key,
    key_range *max_key)
{
  return stats.records;
}

// MySQL calls this function all over the place whenever it needs you to update some crucial piece of info.
// It expects you to use this to set information about your indexes and error codes, as well as general info about your engine.
// The bit flags (defined in my_base.h) passed in will vary depending on what it wants you to update during this call. - ABC
int CloudHandler::info(uint flag)
{
  // TODO: Update this function to take into account the flag being passed in, like the other engines
  ha_rows		rec_per_key;

  DBUG_ENTER("CloudHandler::info");
  if (flag & HA_STATUS_VARIABLE) 
  {
    attach_thread();
    jclass adapter_class = this->adapter();
    jmethodID get_count_method = find_static_method(adapter_class, "getRowCount", "(Ljava/lang/String;)J",this->env);
    jstring table_name = this->table_name();
    jlong row_count = this->env->CallStaticLongMethod(adapter_class,
        get_count_method, table_name);
    if (row_count < 0)
      row_count = 0;
    if (row_count == 0 && !(flag & HA_STATUS_TIME))
      row_count++;

	THD*	thd = ha_thd();
    if (thd_sql_command(thd) == SQLCOM_TRUNCATE) 
    {
      row_count = 1;
    }

    stats.records = row_count;
    stats.deleted = 0;
    stats.max_data_file_length = this->max_supported_record_length();
    stats.data_file_length = stats.records * this->table->s->reclength;
    stats.index_file_length = this->max_supported_key_length();
    stats.delete_length = stats.deleted * stats.mean_rec_length;
    stats.check_time = 0;

    if (stats.records == 0) {
      stats.mean_rec_length = 0;
    } else {
      stats.mean_rec_length = (ulong) (stats.data_file_length / stats.records);
    }

    detach_thread();
  }

  if (flag & HA_STATUS_CONST)
  {
    // Update index cardinality - see ::analyze() function for more explanation
    /* Since MySQL seems to favor table scans
       too much over index searches, we pretend
       index selectivity is 2 times better than
       our estimate: */

    for (int i = 0; i < this->table->s->keys; i++)
    {
      for (int j = 0; j < table->key_info[i].key_parts; j++)
      {
        rec_per_key = stats.records / 2;

        if (rec_per_key == 0) {
          rec_per_key = 1;
        }

        table->key_info[i].rec_per_key[j] = rec_per_key >= ~(ulong) 0 ? ~(ulong) 0 : (ulong) rec_per_key;
      }
    }
  }
  // MySQL needs us to tell it the index of the key which caused the last operation to fail
  // Should be saved in this->failed_key_index for now
  // Later, when we implement transactions, we should use this opportunity to grab the info from the trx itself.
  if (flag & HA_STATUS_ERRKEY)
  {
    this->errkey = this->failed_key_index;
    this->failed_key_index = -1;
  }


  DBUG_RETURN(0);
}

CloudShare *CloudHandler::get_share(const char *table_name, TABLE *table)
{
  CloudShare *share;
  char *tmp_path_name;
  uint path_length;

  mysql_mutex_lock(cloud_mutex);
  path_length = (uint) strlen(table_name);

  /*
   If share is not present in the hash, create a new share and
   initialize its members.
   */
  if (!(share = (CloudShare*) my_hash_search(cloud_open_tables,
      (uchar*) table_name, path_length)))
  {
    if (!my_multi_malloc(MYF(MY_WME | MY_ZEROFILL), &share, sizeof(*share),
        &tmp_path_name, path_length + 1, NullS))
    {
      mysql_mutex_unlock(cloud_mutex);
      return NULL;
    }
  }

  share->use_count = 0;
  share->table_path_length = path_length;
  share->path_to_table = tmp_path_name;
  share->crashed = FALSE;
  share->rows_recorded = 0;

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

int CloudHandler::rename_table(const char *from, const char *to)
{
  DBUG_ENTER("CloudHandler::rename_table");

  attach_thread();

  jclass adapter_class = this->adapter();
  jmethodID rename_table_method = find_static_method(adapter_class, "renameTable", "(Ljava/lang/String;Ljava/lang/String;)V",this->env);
  char* from_str = extract_table_name_from_path(from);
  char* to_str = extract_table_name_from_path(to);
  jstring current_table_name = string_to_java_string(from_str);
  jstring new_table_name = string_to_java_string(to_str);
  ARRAY_DELETE(from_str);
  ARRAY_DELETE(to_str);
  this->env->CallStaticVoidMethod(adapter_class, rename_table_method, current_table_name, new_table_name);

  detach_thread();

  DBUG_RETURN(0);
}

bool CloudHandler::check_if_incompatible_data(HA_CREATE_INFO *create_info,
    uint table_changes)
{
  if (table_changes != IS_EQUAL_YES)
  {

    return (COMPATIBLE_DATA_NO);
  }

  if (this->check_for_renamed_column(table, NULL))
  {
    return COMPATIBLE_DATA_NO;
  }

  /* Check that row format didn't change */
  if ((create_info->used_fields & HA_CREATE_USED_ROW_FORMAT)
      && create_info->row_type != ROW_TYPE_DEFAULT
      && create_info->row_type != get_row_type())
  {

    return (COMPATIBLE_DATA_NO);
  }

  /* Specifying KEY_BLOCK_SIZE requests a rebuild of the table. */
  if (create_info->used_fields & HA_CREATE_USED_KEY_BLOCK_SIZE)
  {
    return (COMPATIBLE_DATA_NO);
  }

  return (COMPATIBLE_DATA_YES);
}

bool CloudHandler::check_for_renamed_column(const TABLE* table,
    const char* col_name)
{
  uint k;
  Field* field;

  for (k = 0; k < table->s->fields; k++)
  {
    field = table->field[k];

    if (field->flags & FIELD_IS_RENAMED)
    {

      // If col_name is not provided, return if the field is marked as being renamed.
      if (!col_name)
      {
        return (true);
      }

      // If col_name is provided, return only if names match
      if (my_strcasecmp(system_charset_info, field->field_name, col_name) == 0)
      {
        return (true);
      }
    }
  }

  return (false);
}

void CloudHandler::get_auto_increment(ulonglong offset, ulonglong increment,
                                 ulonglong nb_desired_values,
                                 ulonglong *first_value,
                                 ulonglong *nb_reserved_values)
{
  DBUG_ENTER("CloudHandler::get_auto_increment");
  jclass adapter_class = this->adapter();
  jmethodID get_auto_increment_method = find_static_method(adapter_class, "getNextAutoincrementValue", "(Ljava/lang/String;Ljava/lang/String;)J",this->env);
  jlong increment_value = (jlong) this->env->CallStaticObjectMethod(adapter_class, get_auto_increment_method, this->table_name(), string_to_java_string(table->next_number_field->field_name));
  *first_value = (ulonglong)increment_value;
  *nb_reserved_values = ULONGLONG_MAX;
  DBUG_VOID_RETURN;
}

int CloudHandler::write_row(uchar *buf)
{
  DBUG_ENTER("CloudHandler::write_row");

  if (share->crashed)
    DBUG_RETURN(HA_ERR_CRASHED_ON_USAGE);

  ha_statistic_increment(&SSV::ha_write_count);

  jclass adapter_class = this->adapter();
  jmethodID write_row_method = find_static_method(adapter_class, "writeRow", "(Ljava/lang/String;Ljava/util/Map;)Z", env);

  jstring table_name = this->table_name();

  jobject java_row_map = create_java_map(this->env);
  jobject unique_values_map = create_java_map(this->env);

  if (table->timestamp_field_type & TIMESTAMP_AUTO_SET_ON_INSERT)
    table->timestamp_field->set_time();

  if(table->next_number_field && buf == table->record[0])
  {
    int res;
    if((res = update_auto_increment()))
    {
      DBUG_RETURN(res);
    }
  }

  my_bitmap_map *old_map = dbug_tmp_use_all_columns(table, table->read_set);

  uint actualFieldSize;

  for (Field **field_ptr = table->field; *field_ptr; field_ptr++)
  {
    Field * field = *field_ptr;
    jstring field_name = string_to_java_string(field->field_name);

    const bool is_null = field->is_null();
    uchar* byte_val;

    if (is_null)
    {
      java_map_insert(java_row_map, field_name, NULL, this->env);
      continue;
    }

    switch (field->real_type())
    {
    case MYSQL_TYPE_TINY:
    case MYSQL_TYPE_SHORT:
    case MYSQL_TYPE_LONG:
    case MYSQL_TYPE_LONGLONG:
    case MYSQL_TYPE_INT24:
    case MYSQL_TYPE_YEAR:
    case MYSQL_TYPE_ENUM:
    {
      long long integral_value = field->val_int();
      if (is_little_endian())
      {
        integral_value = __builtin_bswap64(integral_value);
      }
      actualFieldSize = sizeof integral_value;
      byte_val = (uchar*) my_malloc(actualFieldSize, MYF(MY_WME));
      memcpy(byte_val, &integral_value, actualFieldSize);
      break;
    }
    case MYSQL_TYPE_FLOAT:
    case MYSQL_TYPE_DOUBLE:
    {
      double fp_value = field->val_real();
      long long* fp_ptr = (long long*) &fp_value;
      if (is_little_endian())
      {
        *fp_ptr = __builtin_bswap64(*fp_ptr);
      }
      actualFieldSize = sizeof fp_value;
      byte_val = (uchar*) my_malloc(actualFieldSize, MYF(MY_WME));
      memcpy(byte_val, fp_ptr, actualFieldSize);
      break;
    }
    case MYSQL_TYPE_DECIMAL:
    case MYSQL_TYPE_NEWDECIMAL:
      actualFieldSize = field->key_length();
      byte_val = (uchar*) my_malloc(actualFieldSize, MYF(MY_WME));
      memcpy(byte_val, field->ptr, actualFieldSize);
      break;
    case MYSQL_TYPE_DATE:
    case MYSQL_TYPE_NEWDATE:
    case MYSQL_TYPE_TIME:
    case MYSQL_TYPE_DATETIME:
    case MYSQL_TYPE_TIMESTAMP:
    {
      MYSQL_TIME mysql_time;
      char temporal_value[MAX_DATE_STRING_REP_LENGTH];
      field->get_time(&mysql_time);
      my_TIME_to_str(&mysql_time, temporal_value);
      actualFieldSize = strlen(temporal_value);
      byte_val = (uchar*) my_malloc(actualFieldSize, MYF(MY_WME));
      memcpy(byte_val, temporal_value, actualFieldSize);
      break;
    }
    case MYSQL_TYPE_BLOB:
    case MYSQL_TYPE_TINY_BLOB:
    case MYSQL_TYPE_MEDIUM_BLOB:
    case MYSQL_TYPE_LONG_BLOB:
    case MYSQL_TYPE_VARCHAR:
    case MYSQL_TYPE_VAR_STRING:
    {
      char string_value_buff[field->field_length];
      String string_value(string_value_buff, sizeof(string_value_buff),
          field->charset());
      field->val_str(&string_value);
      actualFieldSize = string_value.length();
      byte_val = (uchar*) my_malloc(actualFieldSize, MYF(MY_WME));
      memcpy(byte_val, string_value.ptr(), actualFieldSize);
      break;
    }
    case MYSQL_TYPE_NULL:
    case MYSQL_TYPE_BIT:
    case MYSQL_TYPE_SET:
    case MYSQL_TYPE_GEOMETRY:
    default:
      actualFieldSize = field->key_length();
      byte_val = (uchar*) my_malloc(actualFieldSize, MYF(MY_WME));
      memcpy(byte_val, field->ptr, actualFieldSize);
      break;
    }

    jbyteArray java_bytes = convert_value_to_java_bytes(byte_val, actualFieldSize, this->env);
    java_map_insert(java_row_map, field_name, java_bytes, this->env);

    // Remember this field for later if we find that it has a unique index, need to check it
    if (this->field_has_unique_index(field))
    {
      java_map_insert(unique_values_map, field_name, java_bytes, this->env);
    }
  }

  dbug_tmp_restore_column_map(table->read_set, old_map);

  if (this->row_has_duplicate_values(unique_values_map))
  {
    DBUG_RETURN(HA_ERR_FOUND_DUPP_KEY);
  }

  this->env->CallStaticBooleanMethod(adapter_class, write_row_method, table_name, java_row_map);
  this->rows_written++;

  DBUG_RETURN(0);
}

bool CloudHandler::row_has_duplicate_values(jobject value_map)
{
    jclass adapter_class = this->adapter();
    jmethodID has_duplicates_method = find_static_method(adapter_class, "findDuplicateKey", "(Ljava/lang/String;Ljava/util/Map;)Ljava/lang/String;", this->env);
    jstring duplicate_column = (jstring) this->env->CallStaticObjectMethod(adapter_class, has_duplicates_method, this->table_name(), value_map);

    bool error = duplicate_column != NULL;

    if (error)
    {
      const char *key_name = this->java_to_string(duplicate_column);
      this->failed_key_index = this->get_failed_key_index(key_name);

      this->env->ReleaseStringUTFChars(duplicate_column, key_name);
    }

    return error;
}

int CloudHandler::get_failed_key_index(const char *key_name)
{
  if (this->table->s->keys == 0)
  {
    return 0;
  }

  for (uint key = 0; key < this->table->s->keys; key++)
  {
    char* name = index_name(table, key);
    if (strcmp(name, key_name) == 0)
    {
      return key;
    }
  }

  return -1;
}

int CloudHandler::prepare_drop_index(TABLE *table_arg, uint *key_num, uint num_of_keys)
{
  uint keys = num_of_keys;
  attach_thread();

  jclass adapter = this->adapter();
  jmethodID add_index_method = find_static_method(adapter, "dropIndex", "(Ljava/lang/String;Ljava/lang/String;)V",this->env);

  for (uint key = 0; key < keys; key++)
  {
    char* name = index_name(table_arg, key);
    this->env->CallStaticVoidMethod(adapter, add_index_method, this->table_name(), string_to_java_string(name));
    ARRAY_DELETE(name);
  }

  return 0;
}

int CloudHandler::add_index(TABLE *table_arg, KEY *key_info, uint num_of_keys, handler_add_index **add)
{
  attach_thread();
  for(uint key = 0; key < num_of_keys; key++)
  {
    KEY* pos = key_info + key;
    KEY_PART_INFO *key_part = pos->key_part;
    KEY_PART_INFO *end_key_part = key_part + key_info->key_parts;
    char* index_columns = this->index_name(key_part, end_key_part, key_info->key_parts);

    Field *field_being_indexed = key_info->key_part->field;
    jbyteArray duplicate_value = this->find_duplicate_column_values(index_columns);

    int error = duplicate_value != NULL ? HA_ERR_FOUND_DUPP_KEY : 0;

    if (error == HA_ERR_FOUND_DUPP_KEY)
    {
      int length = (int)this->env->GetArrayLength(duplicate_value);
      char *value_key = char_array_from_java_bytes(duplicate_value, this->env);
      this->store_field_value(field_being_indexed, value_key, length);
      ARRAY_DELETE(value_key);
      this->failed_key_index = this->get_failed_key_index(key_part->field->field_name);
      detach_thread();
      return error;
    }

    jclass adapter = this->adapter();
    jmethodID add_index_method = find_static_method(adapter, "addIndex", "(Ljava/lang/String;Ljava/lang/String;)V",this->env);
    this->env->CallStaticVoidMethod(adapter, add_index_method, this->table_name(), string_to_java_string(index_columns));
  }

  detach_thread();
  return 0;
}

jbyteArray CloudHandler::find_duplicate_column_values(char* columns)
{
  attach_thread();

  jclass adapter = this->adapter();
  jmethodID column_has_duplicates_method = find_static_method(adapter, "findDuplicateValue", "(Ljava/lang/String;Ljava/lang/String;)[B",this->env);
  jbyteArray duplicate_value = (jbyteArray) this->env->CallStaticObjectMethod(adapter, column_has_duplicates_method, this->table_name(), string_to_java_string(columns));

  detach_thread();

  return duplicate_value;
}

bool CloudHandler::field_has_unique_index(Field *field)
{
  for (int i = 0; i < table->s->keys; i++)
  {
    KEY *key_info = table->s->key_info + i;
    KEY_PART_INFO *key_part = key_info->key_part;
    KEY_PART_INFO *end_key_part = key_part + key_info->key_parts;

    while(key_part < end_key_part)
    {
      if ((key_info->flags & HA_NOSAME) && strcmp(key_part->field->field_name, field->field_name) == 0)
      {
        return true;
      }

      key_part++;
    }
  }

  return false;
}

jstring CloudHandler::string_to_java_string(const char *string)
{
  return this->env->NewStringUTF(string);
}

const char *CloudHandler::java_to_string(jstring string)
{
  return this->env->GetStringUTFChars(string, JNI_FALSE);
}

int CloudHandler::index_init(uint idx, bool sorted)
{
  DBUG_ENTER("CloudHandler::index_init");

  this->active_index = idx;

  KEY *pos = table->s->key_info + idx;
  KEY_PART_INFO *key_part = pos->key_part;
  KEY_PART_INFO *key_part_end = key_part + pos->key_parts;
  const char* column_names = this->index_name(key_part, key_part_end, pos->key_parts);
  Field *field = table->field[idx];
  attach_thread();

  jclass adapter_class = this->adapter();
  jmethodID start_scan_method = find_static_method(adapter_class, "startIndexScan", "(Ljava/lang/String;Ljava/lang/String;)J",this->env);
  jstring table_name = this->table_name();
  jstring java_column_names = this->string_to_java_string(column_names);

  this->curr_scan_id = this->env->CallStaticLongMethod(adapter_class, start_scan_method, table_name, java_column_names);
  ARRAY_DELETE(column_names);

  DBUG_RETURN(0);
}

int CloudHandler::index_end()
{
  DBUG_ENTER("CloudHandler::index_end");

  this->end_scan();
  this->detach_thread();
  this->reset_index_scan_counter();

  DBUG_RETURN(0);
}

jobject CloudHandler::create_key_value_list(int index, uint* key_sizes, uchar** key_copies, const char** key_names, jboolean* key_null_bits, jboolean* key_is_null)
{
  jobject key_values = create_java_list(this->env);
  jclass key_value_class = this->env->FindClass(HBASECLIENT "KeyValue");
  jmethodID key_value_ctor = this->env->GetMethodID(key_value_class, "<init>", "(Ljava/lang/String;[BZZ)V");
  for(int x = 0; x < index; x++)
  {
    jbyteArray java_key = this->env->NewByteArray(key_sizes[x]);
    this->env->SetByteArrayRegion(java_key, 0, key_sizes[x], (jbyte*) key_copies[x]);
    jstring key_name = string_to_java_string(key_names[x]);
    jobject key_value = this->env->NewObject(key_value_class, key_value_ctor, key_name, java_key, key_null_bits[x], key_is_null[x]);
    java_list_insert(key_values, key_value, this->env);
  }

  return key_values;
}

bool CloudHandler::is_field_nullable(jstring table_name, const char* field_name)
{
  jclass adapter_class = this->adapter();
  jmethodID is_nullable_method = find_static_method(adapter_class, "isNullable", "(Ljava/lang/String;Ljava/lang/String;)Z",this->env);
  return (bool)this->env->CallStaticBooleanMethod(adapter_class, is_nullable_method, table_name, string_to_java_string(field_name));
}

int CloudHandler::index_read_map(uchar * buf, const uchar * key, key_part_map keypart_map, enum ha_rkey_function find_flag)
{
  DBUG_ENTER("CloudHandler::index_read_map");
  jclass adapter_class = this->adapter();
  jmethodID index_read_method = find_static_method(adapter_class, "indexRead", "(JLjava/util/List;L" MYSQLENGINE "IndexReadType;)L" MYSQLENGINE "IndexRow;",this->env);
  if (find_flag == HA_READ_PREFIX_LAST_OR_PREV)
  {
    find_flag = HA_READ_KEY_OR_PREV;
  }

  KEY *key_info = table->s->key_info + this->active_index;
  KEY_PART_INFO *key_part = key_info->key_part;
  KEY_PART_INFO *end_key_part = key_part + key_info->key_parts;
  key_part_map counter = keypart_map;
  int key_count = 0;
  while(counter)
  {
    counter >>= 1;
    key_count++;
  }

  uchar* key_copies[key_count];
  uint key_sizes[key_count];
  jboolean key_null_bits[key_count];
  jboolean key_is_null[key_count];
  const char* key_names[key_count];
  memset(key_null_bits, JNI_FALSE, key_count);
  memset(key_is_null, JNI_FALSE, key_count);
  memset(key_copies, 0, key_count);
  uchar* key_iter = (uchar*)key;
  int index = 0;

  while (key_part < end_key_part && keypart_map)
  {
    Field* field = key_part->field;
    key_names[index] = field->field_name;
    uint store_length = key_part->store_length;
    uint offset = store_length;
    if (this->is_field_nullable(this->table_name(), field->field_name))
    {
      if(key_iter[0] == 1)
      {
        if(index == (key_count - 1) && find_flag == HA_READ_AFTER_KEY)
        {
          key_is_null[index] = JNI_FALSE;
          DBUG_RETURN(index_first(buf));
        }
        else
        {
          key_is_null[index] = JNI_TRUE;
        }
      }

      // If the index is nullable, then the first byte is the null flag.  Ignore it.
      key_iter++;
      offset--;
      key_null_bits[index] = JNI_TRUE;
      store_length--;
    }

    uchar* key_copy = create_key_copy(field, key_iter, &store_length, table->in_use);
    key_sizes[index] = store_length;
    key_copies[index] = key_copy;
    keypart_map >>= 1;
    key_part++;
    key_iter += offset;
    index++;
  }

  jobject key_values = create_key_value_list(index, key_sizes, key_copies, key_names, key_null_bits, key_is_null);
  for (int x = 0; x < index; x++)
  {
    ARRAY_DELETE(key_copies[x]);
  }

  jobject java_find_flag = find_flag_to_java(find_flag, this->env);
  jobject index_row = this->env->CallStaticObjectMethod(adapter_class, index_read_method, this->curr_scan_id, key_values, java_find_flag);
  DBUG_RETURN(read_index_row(index_row, buf));
}

void CloudHandler::store_uuid_ref(jobject index_row, jmethodID get_uuid_method)
{
  jbyteArray uuid = (jbyteArray) this->env->CallObjectMethod(index_row, get_uuid_method);
  uchar* pos = (uchar*) this->env->GetByteArrayElements(uuid, JNI_FALSE);
  memcpy(this->ref, pos, 16);
  this->env->ReleaseByteArrayElements(uuid, (jbyte*) pos, 0);
}

int CloudHandler::analyze(THD* thd, HA_CHECK_OPT* check_opt)
{
  DBUG_ENTER("CloudHandler::analyze");

  // For each key, just tell MySQL that there is only one value per keypart.
  // This is, in effect, like telling MySQL that all our indexes are unique, and should essentially always be used for lookups.
  // If you don't do this, the optimizer REALLY tries to do scans, even when they're not ideal. - ABC

  for (int i = 0; i < this->table->s->keys; i++)
  {
    for (int j = 0; j < table->key_info[i].key_parts; j++)
    {
      this->table->key_info[i].rec_per_key[j] = 1;
    }
  }

  DBUG_RETURN(0);
}

ha_rows CloudHandler::estimate_rows_upper_bound()
{
  DBUG_ENTER("CloudHandler::estimate_rows_upper_bound");
  attach_thread();

  jclass adapter_class = this->adapter();
  jmethodID get_count_method = find_static_method(adapter_class, "getRowCount", "(Ljava/lang/String;)J",this->env);
  jstring table_name = this->table_name();
  jlong row_count = this->env->CallStaticLongMethod(adapter_class,
      get_count_method, table_name);

  detach_thread();

  // Stupid MySQL and its filesort. This must be large enough to filesort when there are less than 2 records.
  DBUG_RETURN(row_count < 2 ? 10 : 2*row_count + 1);
}

int CloudHandler::index_next(uchar *buf)
{
  DBUG_ENTER("CloudHandler::index_next");
  DBUG_RETURN(this->retrieve_value_from_index(buf));
}

int CloudHandler::index_prev(uchar *buf)
{
  DBUG_ENTER("CloudHandler::index_prev");
  DBUG_RETURN(this->retrieve_value_from_index(buf));
}

int CloudHandler::retrieve_value_from_index(uchar* buf)
{
  int rc = 0;

  MYSQL_READ_ROW_START(table_share->db.str, table_share->table_name.str, TRUE);
  my_bitmap_map * orig_bitmap = dbug_tmp_use_all_columns(table, table->read_set);

  rc = get_next_index_row(buf);

  dbug_tmp_restore_column_map(table->read_set, orig_bitmap);
  MYSQL_READ_ROW_DONE(rc);

  return rc;
}

int CloudHandler::index_first(uchar *buf)
{
  DBUG_ENTER("CloudHandler::index_first");
  DBUG_RETURN(get_index_row("INDEX_FIRST", buf));
}

int CloudHandler::index_last(uchar *buf)
{
  DBUG_ENTER("CloudHandler::index_last");
  DBUG_RETURN(get_index_row("INDEX_LAST", buf));
}

int CloudHandler::get_next_index_row(uchar* buf)
{
  jclass adapter_class = this->adapter();
  jmethodID index_next_method = find_static_method(adapter_class, "nextIndexRow", "(J)L" MYSQLENGINE "IndexRow;",this->env);
  jobject index_row = this->env->CallStaticObjectMethod(adapter_class, index_next_method, this->curr_scan_id);

  return read_index_row(index_row, buf); 
}

int CloudHandler::get_index_row(const char* indexType, uchar* buf)
{
  jclass adapter_class = this->adapter();
  jmethodID index_read_method = find_static_method(adapter_class, "indexRead", "(JLjava/util/List;L" MYSQLENGINE "IndexReadType;)L" MYSQLENGINE "IndexRow;",this->env);
  jclass read_class = find_jni_class("IndexReadType", this->env);
  jfieldID field_id = this->env->GetStaticFieldID(read_class, indexType, "L" MYSQLENGINE "IndexReadType;");
  jobject java_find_flag = this->env->GetStaticObjectField(read_class, field_id);
  jobject index_row = this->env->CallStaticObjectMethod(adapter_class, index_read_method, this->curr_scan_id, NULL, java_find_flag);
  return read_index_row(index_row, buf); 
}

int CloudHandler::read_index_row(jobject index_row, uchar* buf)
{
  jclass index_row_class = find_jni_class("IndexRow", this->env);
  jmethodID get_uuid_method = this->env->GetMethodID(index_row_class, "getUUID",
      "()[B");
  jmethodID get_rowmap_method = this->env->GetMethodID(index_row_class,
      "getRowMap", "()Ljava/util/Map;");

  jobject rowMap = this->env->CallObjectMethod(index_row, get_rowmap_method);
  if (rowMap == NULL)
  {
    this->table->status = STATUS_NOT_FOUND;
    return HA_ERR_END_OF_FILE;
  }

  this->store_uuid_ref(index_row, get_uuid_method);

  this->java_to_sql(buf, rowMap);

  this->table->status = 0;
  return 0;
}

void CloudHandler::flush_writes()
{
  jclass adapter_class = this->adapter();
  jmethodID end_write_method = find_static_method(adapter_class, "flushWrites", "()V",this->env);
  this->env->CallStaticVoidMethod(adapter_class, end_write_method);
}

void CloudHandler::end_scan()
{
  jclass adapter_class = this->adapter();
  jmethodID end_scan_method = find_static_method(adapter_class, "endScan", "(J)V",this->env);
  this->env->CallStaticVoidMethod(adapter_class, end_scan_method, this->curr_scan_id);
}

void CloudHandler::reset_index_scan_counter()
{
  this->curr_scan_id = -1;
  this->active_index = -1;
}

void CloudHandler::reset_scan_counter()
{
  this->curr_scan_id = -1;
  this->performing_scan = false;
}

void CloudHandler::detach_thread()
{
  thread_ref_count--;

  if (thread_ref_count <= 0)
  {
    this->jvm->DetachCurrentThread();
    this->env = NULL;
  }
}

void CloudHandler::attach_thread()
{
  thread_ref_count++;
  JavaVMAttachArgs attachArgs;
  attachArgs.version = JNI_VERSION_1_6;
  attachArgs.name = NULL;
  attachArgs.group = NULL;

  this->jvm->GetEnv((void**) &this->env, attachArgs.version);
  if (this->env == NULL)
  {
    this->jvm->AttachCurrentThread((void**) &this->env, &attachArgs);
  }
}

jstring CloudHandler::table_name()
{
  char* database_name = this->table->s->db.str;
  char* table_name = this->table->s->table_name.str;
  char namespaced_table[strlen(database_name) + strlen(table_name) + 2];
  sprintf(namespaced_table, "%s.%s", database_name, table_name);
  return string_to_java_string(namespaced_table);
}
