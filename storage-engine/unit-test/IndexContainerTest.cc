#include <stdlib.h>
#include <stdio.h>
#include <time.h>
#include <string.h>
#include <avro.h>
#include "gtest/gtest.h"
#include "../QueryKey.h"
#include "Generator.h"
#include "map_test.hpp"

const int ITERATIONS = 100;

class QueryKeyTest : public ::testing::Test
{
  protected:
    QueryKey index;
    virtual void SetUp() {
      srand(time(NULL));
    }
};

TEST_F(QueryKeyTest, SetType)
{
  ASSERT_FALSE(index.reset());

#define TEST_TYPE(query_type) do {\
  ASSERT_FALSE(index.set_type(query_type)); \
  EXPECT_EQ(query_type, index.get_type()); } while(0);

  TEST_TYPE(QueryKey::EXACT_KEY);
  TEST_TYPE(QueryKey::AFTER_KEY);
  TEST_TYPE(QueryKey::KEY_OR_NEXT);
  TEST_TYPE(QueryKey::KEY_OR_PREVIOUS);
  TEST_TYPE(QueryKey::BEFORE_KEY);
  TEST_TYPE(QueryKey::INDEX_FIRST);
  TEST_TYPE(QueryKey::INDEX_LAST);
#undef TEST_TYPE
}

TEST_F(QueryKeyTest, RandRecords)
{
  for(int i = 0; i < ITERATIONS; i++)
  {
    rand_record_map(index);
  }
}

TEST_F(QueryKeyTest, BytesRecord)
{
  for(int i = 0; i < ITERATIONS; i++)
  {
    bytes_record(index);
  }
}

TEST_F(QueryKeyTest, Name)
{
  int i;
  char name[256];
  const char* name_cmp;
  memset(name, 0, 256);
  index.reset();
  for (i = 0; i < ITERATIONS; i++) 
  {
    snprintf(name, 256, "%s%d", "i", i);
    index.set_name(name);
    name_cmp = index.get_name();
    ASSERT_TRUE(strcmp(name, name_cmp) == 0);
  }
}

TEST_F(QueryKeyTest, CanHaveNullValue)
{
  index.reset();
  const char* value;

  ASSERT_EQ(index.set_value("test", NULL, 0), 0);
  ASSERT_EQ(index.get_value("test", &value, NULL), 0);
  ASSERT_TRUE(value == NULL);
}
