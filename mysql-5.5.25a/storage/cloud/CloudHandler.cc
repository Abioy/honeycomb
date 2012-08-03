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
    DBUG_RETURN(free_share(share));
}

int CloudHandler::write_row(uchar *buf)
{
    DBUG_ENTER("CloudHandler::write_row");
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

    char* table_name = this->share->table_name;

    JVMThreadAttach attached_thread(this->env, this->jvm);

    jclass adapter_class = this->env->FindClass("HBaseAdapter");
    jmethodID start_scan_method = this->env->GetStaticMethodID(adapter_class, "startScan", "(Ljava/lang/String;)J");
    jstring java_table_name = this->string_to_java_string(table_name);
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
    int rc;
    DBUG_ENTER("CloudHandler::rnd_next");
    MYSQL_READ_ROW_START(table_share->db.str, table_share->table_name.str, TRUE);
    rc= HA_ERR_END_OF_FILE;
    MYSQL_READ_ROW_DONE(rc);
    /*
    JVMThreadAttach attached_thread(this->env, this->jvm);
    jclass adapter_class = this->env->FindClass("HBaseAdapter");
    jclass row_class = this->env->FindClass("Row");
    jmethodID next_row_method = this->env->GetStaticMethodID(adapter_class, "next_row", "(J)Lcom/nearinfinity/mysqlengine/jni/Row;");
    jlong java_scan_id = scan_id;
    jobject row = this->env->CallStaticObjectMethod(adapter_class, next_row_method, java_scan_id);

    jmethodID get_keys_method = this->env->GetMethodID(row_class, "getKeys", "()[Ljava/lang/String;");
    jmethodID get_vals_method = this->env->GetMethodID(row_class, "getValues", "()[[B");

    jarray keys = (jarray) this->env->CallObjectMethod(row, get_keys_method);
    jarray vals = (jarray) this->env->CallObjectMethod(row, get_vals_method);

    std::map<std::string, char*>* row_map = new std::map<std::string, char*>();
    std::string key;
    char* val;

    jboolean is_copy = JNI_FALSE;

    jsize size = this->env->GetArrayLength(keys);
    for(jsize i = 0; i < size; i++) {
      key = java_to_string((jstring) this->env->GetObjectArrayElement((jobjectArray) keys, (jsize) i));
      val = (char*) this->env->GetByteArrayElements((jbyteArray) this->env->GetObjectArrayElement((jobjectArray) vals, i), &is_copy);
      (*row_map)[key] = val;
    }
    return row_map;
  */
    DBUG_RETURN(rc);
}

void CloudHandler::position(const uchar *record)
{
    DBUG_ENTER("CloudHandler::position");
    DBUG_VOID_RETURN;
}

int CloudHandler::rnd_pos(uchar *buf, uchar *pos)
{
    int rc;
    DBUG_ENTER("CloudHandler::rnd_pos");
    my_off_t saved_data_file_length;
    MYSQL_READ_ROW_START(table_share->db.str, table_share->table_name.str,
                         TRUE);
    rc= HA_ERR_WRONG_COMMAND;
    MYSQL_READ_ROW_DONE(rc);
    DBUG_RETURN(rc);
}

int CloudHandler::rnd_end()
{
  DBUG_ENTER("CloudHandler::rnd_end");

  JVMThreadAttach attached_thread(this->env, this->jvm);
  jclass adapter_class = this->env->FindClass("HBaseAdapter");
  jmethodID end_scan_method = this->env->GetStaticMethodID(adapter_class, "end_scan", "(J)V");
  jlong java_scan_id = curr_scan_id;
  this->env->CallStaticVoidMethod(adapter_class, end_scan_method, java_scan_id);

  curr_scan_id = -1;
  DBUG_RETURN(0);
}

int CloudHandler::create(const char *name, TABLE *table_arg,
                         HA_CREATE_INFO *create_info)
{
    DBUG_ENTER("CloudHandler::create");
    JVMThreadAttach attached_thread(this->env, this->jvm);
    jclass adapter_class = this->env->FindClass("HBaseAdapter");
    if (adapter_class == NULL)
    {
      DBUG_PRINT("Error", ("Could not find adapter class HBaseAdapter"));
      DBUG_RETURN(1);
    }

    const char* table_name = table_arg->alias;

    jclass list_class = this->env->FindClass("java/util/LinkedList");
    jmethodID list_constructor = this->env->GetMethodID(list_class, "<init>", "()V");
    jobject columns = this->env->NewObject(list_class, list_constructor);
    jmethodID add_column = this->env->GetMethodID(list_class, "add", "(Ljava/lang/Object;)Z");

    for (Field **field = table->field ; *field ; field++)
    {
      this->env->CallBooleanMethod(columns, add_column, string_to_java_string((*field)->field_name));
    }

    jmethodID create_table_method = this->env->GetStaticMethodID(adapter_class, "createTable", "(Ljava/lang/String;Ljava/util/List;)Z");
    jboolean result = this->env->CallStaticBooleanMethod(adapter_class, create_table_method, table_name, columns);
    DBUG_PRINT("INFO", ("Result of createTable: %d", result));

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
    DBUG_RETURN(0);
}

CloudShare *CloudHandler::get_share(const char *table_name, TABLE *table)
{
    CloudShare *share;
    char meta_file_name[FN_REFLEN];
    MY_STAT file_stat;                /* Stat information for the data file */
    char *tmp_name;
    uint length;

    mysql_mutex_lock(cloud_mutex);
    length=(uint) strlen(table_name);

    /*
    If share is not present in the hash, create a new share and
    initialize its members.
    */
    if (!(share=(CloudShare*) my_hash_search(cloud_open_tables,
                (uchar*) table_name,
                length)))
    {
        if (!my_multi_malloc(MYF(MY_WME | MY_ZEROFILL),
                             &share, sizeof(*share),
                             &tmp_name, length+1,
                             NullS))
        {
            mysql_mutex_unlock(cloud_mutex);
            return NULL;
        }
    }

    share->use_count= 0;
    share->table_name_length= length;
    share->table_name= tmp_name;
    share->crashed= FALSE;
    share->rows_recorded= 0;
    share->data_file_version= 0;
    strmov(share->table_name, table_name);
    fn_format(share->data_file_name, table_name, "", "hbase", MY_REPLACE_EXT|MY_UNPACK_FILENAME);

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

const char* CloudHandler::java_to_string(jstring j_str)
{
    const char* str = this->env->GetStringUTFChars(j_str, NULL);
    this->env->ReleaseStringUTFChars(j_str, str);
    return str;
}

jstring CloudHandler::string_to_java_string(const char* string)
{
  return this->env->NewStringUTF(string);
}
