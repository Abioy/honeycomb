#include "CloudHandler.h"

void CloudHandler::start_bulk_insert(ha_rows rows)
{
  DBUG_ENTER("CloudHandler::start_bulk_insert");

  attach_thread();
  //Logging::info("%d rows to be inserted.", rows);

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

jobject CloudHandler::create_multipart_keys(TABLE* table_arg)
{
  uint keys = table_arg->s->keys;
  jmethodID add_key_method = add_multipart_key_method(this->env);
  jobject java_keys = new_multipart_key(this->env);

  for (uint key = 0; key < keys; key++)
  {
    char* name = index_name(table_arg, key);
    jboolean is_unique = (table_arg->key_info + key)->flags & HA_NOSAME ? JNI_TRUE : JNI_FALSE;
    this->env->CallVoidMethod(java_keys, add_key_method, string_to_java_string(name), is_unique);
    ARRAY_DELETE(name);
  }

  return java_keys;
}

jobject CloudHandler::create_multipart_key(KEY* key, KEY_PART_INFO* key_part, KEY_PART_INFO* key_part_end, uint key_parts)
{
  jmethodID add_key_method = add_multipart_key_method(this->env);
  jobject java_keys = new_multipart_key(this->env);

  char* name = index_name(key_part, key_part_end, key_parts);
  jboolean is_unique = key->flags & HA_NOSAME ? JNI_TRUE : JNI_FALSE;
  this->env->CallVoidMethod(java_keys, add_key_method, string_to_java_string(name), is_unique);
  ARRAY_DELETE(name);

  return java_keys;
}

#define YEAR2_NOT_SUPPORTED 0
#define ODD_TYPES_NOT_SUPPORTED 1
#define UTF_REQUIRED 2
const char* table_creation_errors[] = {
  "table. YEAR(2) is not supported.",
  "table. Bit, set and geometry are not supported.",
  "table. Required: character set utf8 collate utf8_bin"
};

bool CloudHandler::is_allowed_column(Field* field, int* error_number)
{
  bool allowed = true;
  switch (field->real_type())
  {
    case MYSQL_TYPE_YEAR:
      if (field->field_length == 2)
      {
        *error_number = YEAR2_NOT_SUPPORTED;
        allowed = false;
      }
      break;
    case MYSQL_TYPE_BIT:
    case MYSQL_TYPE_SET:
    case MYSQL_TYPE_GEOMETRY:
      *error_number = ODD_TYPES_NOT_SUPPORTED;
      allowed = false;
      break;
    case MYSQL_TYPE_STRING:
    case MYSQL_TYPE_VARCHAR:
    case MYSQL_TYPE_BLOB:
      if (strncmp(field->charset()->name, "utf8_bin", 8) != 0 && field->binary() == false)
      {
        *error_number = UTF_REQUIRED;
        allowed = false;
      }
      break;
    default:
      break;
  }

  return allowed;
}

int CloudHandler::create(const char *path, TABLE *table_arg, HA_CREATE_INFO *create_info)
{
  DBUG_ENTER("CloudHandler::create");
  attach_thread();
  char* error_message;

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

  for (Field **field_ptr = table_arg->field; *field_ptr; field_ptr++)
  {
    Field* field = *field_ptr;
    int error_number;
    if(!is_allowed_column(field, &error_number))
    {
      my_error(ER_CREATE_FILEGROUP_FAILED, MYF(0), table_creation_errors[error_number]);
      detach_thread();
      DBUG_RETURN(HA_WRONG_CREATE_OPTION);
    }
   
    jobject java_metadata_obj = metadata.get_field_metadata(field, table_arg, create_info->auto_increment_value);
    java_map_insert(columnMap, string_to_java_string(field->field_name), java_metadata_obj, this->env);
  }

  jmethodID create_table_method = find_static_method(adapter_class, "createTable", "(Ljava/lang/String;Ljava/util/Map;L" HBASECLIENT "TableMultipartKeys;)Z",this->env);
  this->env->CallStaticBooleanMethod(adapter_class, create_table_method, jtable_name, columnMap, java_keys);
  print_java_exception(this->env);
  detach_thread();

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

int CloudHandler::write_row(uchar *buf)
{
  return write_row(buf, NULL);
}

int CloudHandler::write_row(uchar* buf, char* updated_fields)
{
  DBUG_ENTER("CloudHandler::write_row");
  if (share->crashed)
    DBUG_RETURN(HA_ERR_CRASHED_ON_USAGE);

  ha_statistic_increment(&SSV::ha_write_count);

  jclass adapter_class = this->adapter();
  jmethodID write_row_method = find_static_method(adapter_class, "writeRow", "(Ljava/lang/String;Ljava/util/Map;)Z", env);

  jstring table_name = this->table_name();
  jlong new_autoincrement_value = -1;

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
    uint offset = field_ptr - table->field;
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

      if (table->found_next_number_field == field)
        new_autoincrement_value = (jlong) integral_value;

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

      if (table->found_next_number_field == field)
        new_autoincrement_value = (jlong) fp_value;

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
    {
      String string_value;
      field->val_str(&string_value);
      actualFieldSize = string_value.length();
      byte_val = (uchar*) my_malloc(actualFieldSize, MYF(MY_WME));
      memcpy(byte_val, string_value.ptr(), actualFieldSize);
      break;
    }
    case MYSQL_TYPE_VARCHAR:
    case MYSQL_TYPE_VAR_STRING:
    {
      char string_value_buff[field->field_length];
      String string_value(string_value_buff, sizeof(string_value_buff),&my_charset_bin);
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

  if (updated_fields)
  {
    jmethodID update_row_method = find_static_method(adapter_class, "updateRow", "(JLjava/lang/String;Ljava/lang/String;Ljava/util/Map;)V", env);
    jstring fields_changed = string_to_java_string(updated_fields);
    this->env->CallStaticBooleanMethod(adapter_class, update_row_method, this->curr_scan_id, fields_changed, table_name, java_row_map);
  }
  else
  {
    this->env->CallStaticBooleanMethod(adapter_class, write_row_method, table_name, java_row_map);
    this->rows_written++;
  }

  if (new_autoincrement_value >= 0)
    update_cloud_autoincrement_value(new_autoincrement_value + 1, JNI_FALSE);
  DBUG_RETURN(0);
}

/*
 This will be called in a table scan right before the previous ::rnd_next()
 call.
 */
int CloudHandler::update_row(const uchar *old_row, uchar *new_row)
{
  DBUG_ENTER("CloudHandler::update_row");
  typedef unsigned long int ulint;
  Field*		field;
  uint		n_fields = table->s->fields;
  ulint		o_len;
  ulint		n_len;
  ulint		col_pack_len;
  const uchar*	o_ptr;
  const uchar*	n_ptr;
  ulint		col_type;
  uint		i;
  const ulint null_field = 0xFFFFFFFF;
  int index = 0;

  ha_statistic_increment(&SSV::ha_update_count);
  if (table->timestamp_field_type & TIMESTAMP_AUTO_SET_ON_UPDATE)
    table->timestamp_field->set_time();
  
  char* updated_fields[n_fields];
  for (i = 0; i < n_fields; i++) 
  {
    field = table->field[i];

    o_ptr = (const uchar*) old_row + field->offset(table->record[0]);
    n_ptr = (const uchar*) new_row + field->offset(table->record[0]);

    col_pack_len = field->pack_length();

    o_len = col_pack_len;
    n_len = col_pack_len;

    if (field->null_ptr) 
    {
      if (field->is_null_in_record(old_row))
      {
        o_len = null_field;
      }

      if (field->is_null_in_record(new_row)) 
      {
        n_len = null_field;
      }
    }

    if (o_len != n_len || (o_len != null_field && 0 != memcmp(o_ptr, n_ptr, o_len))) 
    {
      updated_fields [index++] = (char*)field->field_name;
    }
  }

  int fieldname_length = 0;
  for(int x = 0 ; x < index; x++)
  {
    fieldname_length += strlen(updated_fields[x]) + 1;
  }

  char updated_fieldnames[fieldname_length];
  memset(updated_fieldnames, 0, fieldname_length);
  for(int x = 0; x < index; x++)
  {
    strcat(updated_fieldnames, updated_fields[x]);
    if (x + 1 != index)
      strcat(updated_fieldnames, ",");
  }
  Logging::info("Updated fields %s", updated_fieldnames);
  

  write_row(new_row, updated_fieldnames);
  this->flush_writes();

  DBUG_RETURN(0);
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
    if (pos->flags & HA_NOSAME)
    {
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
    }

    jclass adapter = this->adapter();
    jobject java_keys = this->create_multipart_key(pos, key_part, end_key_part, key_info->key_parts);
    jmethodID add_index_method = find_static_method(adapter, "addIndex", "(Ljava/lang/String;L" HBASECLIENT "TableMultipartKeys;)V",this->env);
    this->env->CallStaticVoidMethod(adapter, add_index_method, this->table_name(), java_keys);
  }

  detach_thread();
  return 0;
}

int CloudHandler::prepare_drop_index(TABLE *table_arg, uint *key_num, uint num_of_keys)
{
  attach_thread();

  jclass adapter = this->adapter();
  jmethodID add_index_method = find_static_method(adapter, "dropIndex", "(Ljava/lang/String;Ljava/lang/String;)V",this->env);

  for (uint key = 0; key < num_of_keys; key++)
  {
    char* name = index_name(table_arg, key_num[key]);
    this->env->CallStaticVoidMethod(adapter, add_index_method, this->table_name(), string_to_java_string(name));
    ARRAY_DELETE(name);
  }

  return 0;
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
  attach_thread();

  int returnValue = delete_all_rows();

  if (returnValue == 0)
    update_cloud_autoincrement_value((jlong) 1, JNI_TRUE);

  detach_thread();
  DBUG_RETURN(returnValue);
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

void CloudHandler::update_create_info(HA_CREATE_INFO* create_info)
{
  DBUG_ENTER("CloudHandler::update_create_info");
  attach_thread();

  //show create table
  if (!(create_info->used_fields & HA_CREATE_USED_AUTO)) {
    CloudHandler::info(HA_STATUS_AUTO);
    create_info->auto_increment_value = stats.auto_increment_value;
  }
  //alter table
  else if (create_info->used_fields == 1) {
    update_cloud_autoincrement_value((jlong) create_info->auto_increment_value, JNI_FALSE);
  }

  detach_thread();

  DBUG_VOID_RETURN;
}
