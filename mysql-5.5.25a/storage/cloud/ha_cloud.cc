#ifdef USE_PRAGMA_IMPLEMENTATION
#pragma implementation        // gcc: Class implementation
#endif

#include "sql_priv.h"
#include "sql_class.h"           // MYSQL_HANDLERTON_INTERFACE_VERSION
#include "ha_cloud.h"
#include "probes_mysql.h"
#include "sql_plugin.h"
#include <stdlib.h>
#include "Macros.h"

static handler *cloud_create_handler(handlerton *hton,
    TABLE_SHARE *table,
    MEM_ROOT *mem_root);

handlerton *cloud_hton;

mysql_mutex_t cloud_mutex;
static JNIEnv* env = NULL;
static JavaVM* jvm = NULL;
static HASH cloud_open_tables;

static uchar* cloud_get_key(CloudShare *share, size_t *length, my_bool not_used __attribute__((unused)))
{
    *length=share->table_path_length;
    return (uchar*) share->path_to_table;
}

#ifdef HAVE_PSI_INTERFACE
static PSI_mutex_key ex_key_mutex_cloud, ex_key_mutex_cloud_SHARE_mutex;

static PSI_mutex_info all_cloud_mutexes[]=
{
  { &ex_key_mutex_cloud, "cloud", PSI_FLAG_GLOBAL},
  { &ex_key_mutex_cloud_SHARE_mutex, "cloud_SHARE::mutex", 0}
};

static void init_cloud_psi_keys()
{
  const char* category= "cloud";
  int count;

  if (PSI_server == NULL)
    return;

  count= array_elements(all_cloud_mutexes);
  PSI_server->register_mutex(category, all_cloud_mutexes, count);
}
#endif

static void print_java_exception(JNIEnv* env)
{
  if(env->ExceptionCheck() == JNI_TRUE)
  {
    jthrowable throwable = env->ExceptionOccurred();
    jclass objClazz = env->GetObjectClass(throwable);
    jmethodID methodId = env->GetMethodID(objClazz, "toString", "()Ljava/lang/String;");
    jstring result = (jstring)env->CallObjectMethod(throwable, methodId);
    const char* string = env->GetStringUTFChars(result, NULL);
    INFO(("Exceptions from the jvm %s", string));
    env->ReleaseStringUTFChars(result, string);
  }
}

static void test_jvm(bool attach_thread)
{
#ifndef DBUG_OFF
  if(attach_thread)
  {
    JavaVMAttachArgs attachArgs;
    attachArgs.version = JNI_VERSION_1_6;
    attachArgs.name = NULL;
    attachArgs.group = NULL;
    jint ok = jvm->AttachCurrentThread((void**)&env, &attachArgs);
  }

  jclass adapter_class = env->FindClass("com/nearinfinity/mysqlengine/jni/HBaseAdapter");
  INFO(("Adapter class %p", adapter_class));
  print_java_exception(env);
#endif
}

static char* find_java_classpath()
{
  char* class_path;
  const char* prefix = "-Djava.class.path=";
  FILE* config = fopen("/etc/mysql/classpath.conf", "r");
  if(config != NULL)
  {
    fseek(config, 0, SEEK_END);
    long size = ftell(config);
    rewind(config);
    int prefix_length = strlen(prefix);
    int class_path_length = prefix_length + size; 
    class_path = new char[class_path_length];
    strncpy(class_path, prefix, prefix_length);
    fread(class_path + prefix_length, sizeof(char), size, config);
    int i = 0;
    for(char* ptr = class_path; i < class_path_length; ptr++, i++)
    {
      if(*ptr == '\n' || *ptr == '\r')
      {
        *ptr = '\0';
      }
    }

    fclose(config);
  }
  else
  {
    char* home = getenv("MYSQL_HOME");
    const char* suffix = "/lib/plugin/mysqlengine-0.1-jar-with-dependencies.jar";
    class_path = new char[strlen(prefix) + strlen(home) + strlen(suffix)];
    sprintf(class_path, "%s%s%s", prefix, home, suffix);
    FILE* jar = fopen(class_path, "r");
    if(jar == NULL)
    {
      ERROR(("No jar classpath specified and the default jar path %s cannot be opened. Either place \"classpath.conf\" in /etc/mysql/ or create %s. Place the java classpath in classpath.conf.", class_path, class_path));
    }
    else
    {
      fclose(jar);
    }
  }

  INFO(("Full class path: %s", class_path));
  return class_path;
}

static void create_or_find_jvm()
{
  JavaVM* created_vms;
  jsize vm_count;
  jint result = JNI_GetCreatedJavaVMs(&created_vms, 1, &vm_count);
  if (result == 0 && vm_count > 0)
  {
    jvm = created_vms;
    test_jvm(true);
  }
  else
  {
    JavaVMInitArgs vm_args;
#ifdef __APPLE__
    const int option_count = 3;
    JavaVMOption option[option_count];
    option[1].optionString = "-Djava.security.krb5.realm=OX.AC.UK";
    option[2].optionString = "-Djava.security.krb5.kdc=kdc0.ox.ac.uk:kdc1.ox.ac.uk";
#else
    const int option_count = 1;
    JavaVMOption option[option_count];
#endif
    char* class_path = find_java_classpath();
    option[0].optionString = class_path;
    // option[0].optionString = "-Djava.class.path=/usr/local/mysql/lib/plugin/mysql.jar";
    vm_args.nOptions = option_count;
    vm_args.options = option;
    vm_args.version = JNI_VERSION_1_6;
    jint result = JNI_CreateJavaVM(&jvm, (void**)&env, &vm_args);
    if (result != 0)
    {
      ERROR(("Failed to create JVM"));
    }

    delete class_path;
    test_jvm(false);
  }
}

static int cloud_init_func(void *p)
{
  DBUG_ENTER("ha_cloud::cloud_init_func");

#ifdef HAVE_PSI_INTERFACE
  init_cloud_psi_keys();
#endif

  cloud_hton = (handlerton *)p;
  mysql_mutex_init(ex_key_mutex_cloud, &cloud_mutex, MY_MUTEX_INIT_FAST);
  (void) my_hash_init(&cloud_open_tables,system_charset_info,32,0,0,
      (my_hash_get_key) cloud_get_key,0,0);

  cloud_hton->state = SHOW_OPTION_YES;
  cloud_hton->create = cloud_create_handler;
  cloud_hton->flags = HTON_ALTER_NOT_SUPPORTED;

  create_or_find_jvm();

  DBUG_RETURN(0);
}

static int cloud_done_func(void *p)
{
  int error = 0;
  DBUG_ENTER("cloud_done_func");

  if (cloud_open_tables.records)
  {
    error= 1;
  }

  my_hash_free(&cloud_open_tables);
  mysql_mutex_destroy(&cloud_mutex);
  DBUG_RETURN(error);
}

/**
  @brief
  Free lock controls. We call this whenever we close a table. If the table had
  the last reference to the share, then we free memory associated with it.
  */

static int free_share(CloudShare *share)
{
  mysql_mutex_lock(&cloud_mutex);
  if (!--share->use_count)
  {
    my_hash_delete(&cloud_open_tables, (uchar*) share);
    thr_lock_delete(&share->lock);
    mysql_mutex_destroy(&share->mutex);
    my_free(share);
  }

  mysql_mutex_unlock(&cloud_mutex);
  return 0;
}

static handler* cloud_create_handler(handlerton *hton, TABLE_SHARE *table, MEM_ROOT *mem_root)
{
  return new (mem_root) CloudHandler(hton, table, &cloud_mutex, &cloud_open_tables, jvm);
}

struct st_mysql_storage_engine cloud_storage_engine=
{
  MYSQL_HANDLERTON_INTERFACE_VERSION 
};

mysql_declare_plugin(cloud)
{
  MYSQL_STORAGE_ENGINE_PLUGIN,
    &cloud_storage_engine,
    "cloud",
    "Near Infinity Corporation",
    "HBase storage engine",
    PLUGIN_LICENSE_GPL,
    cloud_init_func,                            /* Plugin Init */
    cloud_done_func,                            /* Plugin Deinit */
    0x0001 /* 0.1 */,
    NULL,                                  /* status variables */
    NULL,                     /* system variables */
    NULL,                                         /* config options */
    NULL,                                            /* flags */
}
mysql_declare_plugin_end;
