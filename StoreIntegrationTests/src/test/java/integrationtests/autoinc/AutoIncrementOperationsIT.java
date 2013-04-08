package integrationtests.autoinc;

import com.nearinfinity.honeycomb.mysql.TableSchema;
import com.nearinfinity.honeycomb.mysql.gen.ColumnType;
import integrationtests.ColumnSchemaFactory;
import integrationtests.HoneycombIntegrationTest;
import integrationtests.ITUtils;
import org.junit.Test;

import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.Assert.assertThat;

public class AutoIncrementOperationsIT extends HoneycombIntegrationTest {

    @Override
    protected TableSchema getTableSchema() {
        final TableSchema schema = ITUtils.getTableSchema();
        schema.addColumn(ColumnSchemaFactory.createColumnSchema(ColumnType.LONG, true, true, 8, 0, 0, "test"));

        return schema;
    }

    @Test
    public void testGetAutoIncrement() {
        assertThat(proxy.getAutoIncrement(), equalTo(1L));
    }

    @Test
    public void testIncrementAutoIncrement() {
        assertThat(proxy.incrementAutoIncrement(3), equalTo(1L));

        assertThat(proxy.incrementAutoIncrement(1), equalTo(4L));
    }

    @Test
    public void testTruncateAutoInc() {
        proxy.truncateAutoIncrement();
        assertThat(proxy.getAutoIncrement(), equalTo(1L));
    }
}
