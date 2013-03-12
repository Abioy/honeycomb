#include <stdlib.h>
#include <stdio.h>
#include <time.h>
#include <string.h>
#include <avro.h>
#include "Generator.h"
#include "gtest/gtest.h"
#include "../IndexSchema.h"

const int ITERATIONS = 100;

class IndexSchemaTest : public ::testing::Test
{
  protected:
    IndexSchema schema;
    virtual void SetUp() {
      srand(time(NULL));
    }
};

TEST_F(IndexSchemaTest, Defaults)
{
  ASSERT_FALSE(schema.reset());
  ASSERT_FALSE(schema.get_is_unique());
  ASSERT_EQ(0, schema.size());
};

TEST_F(IndexSchemaTest, SetUnique)
{
  ASSERT_FALSE(schema.reset());
  ASSERT_FALSE(schema.set_is_unique(true));
  ASSERT_TRUE(schema.get_is_unique());

  ASSERT_FALSE(schema.set_is_unique(false));
  ASSERT_FALSE(schema.get_is_unique());
};

TEST_F(IndexSchemaTest, AddColumn)
{
  ASSERT_FALSE(schema.reset());
  ASSERT_FALSE(schema.add_column("foobar"));
  const char* str;
  size_t len;
  ASSERT_STREQ("foobar", schema.get_column(0));
};

void rand_columns_add(IndexSchema* schema)
{
  ASSERT_FALSE(schema->reset());

  int num_columns = 1 + rand() % 4;
  char** column_names = new char*[num_columns];
  int length;

  for (int i = 0; i < num_columns; i++)
  {
    length = 1 + rand() % 64;
    column_names[i] = new char[length];
    gen_random_string(column_names[i], length);

    ASSERT_FALSE(schema->add_column(column_names[i]));
  }

  const char* val;
  for (int i = 0; i < num_columns; i++)
  {
    ASSERT_STREQ(column_names[i], schema->get_column(i));
    delete[] column_names[i];
  }
  delete[] column_names;
}
TEST_F(IndexSchemaTest, RandColumnsAdd)
{
  for(int i = 0; i < ITERATIONS; i++) {
    rand_columns_add(&schema);
  }
}

void test_ser_de(IndexSchema* schema)
{
  ASSERT_FALSE(schema->reset());
  IndexSchema* schema_de = new IndexSchema();

  index_schema_gen(schema);

  const char* serialized;
  size_t size;
  schema->serialize(&serialized, &size);

  schema_de->deserialize(serialized, (int64_t) size);
  ASSERT_TRUE(schema->equals(*schema_de));

  delete[] serialized;
  delete schema_de;
};
TEST_F(IndexSchemaTest, SerDe)
{
  for (int i = 0; i < ITERATIONS; i++)
  {
    test_ser_de(&schema);
  }
};
