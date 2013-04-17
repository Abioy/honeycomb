#include "Logging.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "my_pthread.h"
#include "Util.h"

namespace Logging
{
  static FILE* log_file;
  static pthread_mutex_t log_lock;

  char* time_string()
  {
    time_t current_time;
    time(&current_time);
    char* time_string = ctime(&current_time);
    int time_length = strlen(time_string);
    time_string[time_length - 1] = '\0';
    return time_string;
  }

  bool try_setup_logging(const char* path)
  {
    log_file = fopen(path, "a");
    if (log_file == NULL)
    {
      char owner[256],current[256];
      get_current_user_group(owner, sizeof(owner));
      get_file_user_group(path, current, sizeof(current));
      fprintf(stderr, "Error trying to open log file %s. Current process %s file %s. %s\n", path, owner, current, strerror(errno));
      return false;
    }

    pthread_mutex_init(&log_lock, NULL);
    info("%s - Log opened", time_string());
    return true;
  }

  void close_logging()
  {
    if (log_file != NULL)
    {
      fclose(log_file);
    }
    pthread_mutex_destroy(&log_lock);
  }

  void vlog_print(const char* level, const char* format, va_list args)
  {
    pthread_mutex_lock(&log_lock);
    fprintf(log_file, "%s %s - ", level, time_string());
    vfprintf(log_file, format, args);
    fprintf(log_file, "\n");
    fflush(log_file);
    pthread_mutex_unlock(&log_lock);
  }

  void print(const char* level, const char* format, ...)
  {
    va_list args;
    va_start(args,format);

    vlog_print(level, format, args);

    va_end(args);
  }

  void info(const char* format, ...)
  {
    va_list args;
    va_start(args,format);

    vlog_print("INFO", format, args);

    va_end(args);
  }

  void warn(const char* format, ...)
  {
    va_list args;
    va_start(args,format);

    vlog_print("WARN", format, args);

    va_end(args);
  }

  void error(const char* format, ...)
  {
    va_list args;
    va_start(args,format);

    vlog_print("ERROR", format, args);

    va_end(args);
  }

  void fatal(const char* format, ...)
  {
    va_list args;
    va_start(args,format);

    vlog_print("FATAL", format, args);

    va_end(args);
  }
}
