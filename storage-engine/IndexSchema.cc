#include "IndexSchema.h"
#include "AvroUtil.h"
#include <stdio.h>

const char IS_UNIQUE[] = "isUnique";

IndexSchema::IndexSchema()
: index_schema_schema(),
  index_schema()
{
  if (avro_schema_from_json_literal(INDEX_SCHEMA, &index_schema_schema))
  {
    printf("Unable to create IndexSchema schema.  Exiting.\n");
    abort();
  };
  avro_value_iface_t* rc_class = avro_generic_class_from_schema(index_schema_schema);
  if (avro_generic_value_new(rc_class, &index_schema))
  {
    printf("Unable to create IndexSchema.  Exiting.\n");
    abort();
  }
  avro_value_iface_decref(rc_class);
}

IndexSchema::~IndexSchema()
{
  avro_value_decref(&index_schema);
  avro_schema_decref(index_schema_schema);
}

/**
 * @brief Resets the IndexSchema to a fresh state. Resetting an existing
 * IndexSchema is much faster than creating a new one.
 * @return Error code
 */
int IndexSchema::reset()
{
  return avro_value_reset(&index_schema);
}

bool IndexSchema::equals( const IndexSchema& other)
{
  avro_value_t other_index_schema = other.index_schema;
  return avro_value_equal(&index_schema, &other_index_schema);
};

int IndexSchema::serialize(const char** buf, size_t* len)
{
  return serialize_object(&index_schema, buf, len);
};

int IndexSchema::deserialize(const char* buf, int64_t len)
{
  return deserialize_object(&index_schema, buf, len);
}

bool IndexSchema::get_is_unique() {
  int is_unique;
  avro_value_t avro_bool;
  avro_value_get_by_name(&index_schema, IS_UNIQUE, &avro_bool, NULL);
  avro_value_get_boolean(&avro_bool, (int*) &is_unique);
  return is_unique;
}

int IndexSchema::set_is_unique(bool is_unique) {
  int ret = 0;
  avro_value_t avro_bool;
  ret |= avro_value_get_by_name(&index_schema, IS_UNIQUE, &avro_bool, NULL);
  ret |= avro_value_set_boolean(&avro_bool, (int) is_unique);
  return ret;
}

/**
 * Return the number of columns in the index schema.
 */
size_t IndexSchema::size() {
  size_t size;
  avro_value_t column_list;
  avro_value_get_by_name(&index_schema, "columns", &column_list, NULL);
  avro_value_get_size(&column_list, &size);
  return size;
}

/**
 * Return the nth column of the index.
 */
const char* IndexSchema::get_column(size_t n) {
  const char* column;
  avro_value_t column_list;
  avro_value_t column_value;
  avro_value_get_by_name(&index_schema, "columns", &column_list, NULL);
  avro_value_get_by_index(&column_list, n, &column_value, NULL);
  avro_value_get_string(&column_value, &column, NULL);
  return column;
}

/**
 * Add a column to the index.
 */
int IndexSchema::add_column(const char* column_name)
{
  int ret = 0;
  avro_value_t column;
  avro_value_t column_list;
  ret |= avro_value_get_by_name(&index_schema, "columns", &column_list, NULL);
  ret |= avro_value_append(&column_list, &column, NULL);
  ret |= avro_value_set_string(&column, column_name);
  return ret;
}

avro_value_t* IndexSchema::get_avro_value()
{
  return &index_schema;
};

int IndexSchema::set_avro_value(avro_value_t* value)
{
  return avro_value_copy(&index_schema, value);
};
