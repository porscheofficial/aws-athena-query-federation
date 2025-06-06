package com.amazonaws.athena.connectors.snowflake;
/*-
 * #%L
 * athena-snowflake
 * %%
 * Copyright (C) 2019 Amazon Web Services
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import com.amazonaws.athena.connector.lambda.data.*;
import com.amazonaws.athena.connector.lambda.domain.Split;
import com.amazonaws.athena.connector.lambda.domain.TableName;
import com.amazonaws.athena.connector.lambda.domain.predicate.Constraints;
import com.amazonaws.athena.connector.lambda.exceptions.AthenaConnectorException;
import com.amazonaws.athena.connector.lambda.metadata.*;
import com.amazonaws.athena.connector.lambda.resolver.CaseResolver;
import com.amazonaws.athena.connector.lambda.security.FederatedIdentity;
import com.amazonaws.athena.connectors.jdbc.TestBase;
import com.amazonaws.athena.connectors.jdbc.connection.DatabaseConnectionConfig;
import com.amazonaws.athena.connectors.jdbc.connection.JdbcConnectionFactory;
import com.amazonaws.athena.connector.credentials.CredentialsProvider;
import com.amazonaws.athena.connectors.snowflake.resolver.SnowflakeJDBCCaseResolver;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import software.amazon.awssdk.services.athena.AthenaClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static com.amazonaws.athena.connectors.snowflake.SnowflakeConstants.MAX_PARTITION_COUNT;
import static com.amazonaws.athena.connectors.snowflake.SnowflakeConstants.SNOWFLAKE_NAME;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;

public class SnowflakeMetadataHandlerTest
        extends TestBase {

    private DatabaseConnectionConfig databaseConnectionConfig = new DatabaseConnectionConfig("testCatalog", SnowflakeConstants.SNOWFLAKE_NAME,
            "snowflake://jdbc:snowflake://hostname/?warehouse=warehousename&db=dbname&schema=schemaname&user=xxx&password=xxx");
    private SnowflakeMetadataHandler snowflakeMetadataHandler;
    private JdbcConnectionFactory jdbcConnectionFactory;
    private Connection connection;
    private FederatedIdentity federatedIdentity;
    private SecretsManagerClient secretsManager;
    private AthenaClient athena;
    private BlockAllocator blockAllocator;
    private static final Schema PARTITION_SCHEMA = SchemaBuilder.newBuilder().addField("partition", org.apache.arrow.vector.types.Types.MinorType.VARCHAR.getType()).build();

    @Before
    public void setup()
            throws Exception
    {
        this.jdbcConnectionFactory = Mockito.mock(JdbcConnectionFactory.class , Mockito.RETURNS_DEEP_STUBS);
        this.connection = Mockito.mock(Connection.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(this.jdbcConnectionFactory.getConnection(nullable(CredentialsProvider.class))).thenReturn(this.connection);
        this.secretsManager = Mockito.mock(SecretsManagerClient.class);
        this.athena = Mockito.mock(AthenaClient.class);
        Mockito.when(this.secretsManager.getSecretValue(Mockito.eq(GetSecretValueRequest.builder().secretId("testSecret").build()))).thenReturn(GetSecretValueResponse.builder().secretString("{\"username\": \"testUser\", \"password\": \"testPassword\"}").build());
        this.snowflakeMetadataHandler = new SnowflakeMetadataHandler(databaseConnectionConfig, this.secretsManager, this.athena, this.jdbcConnectionFactory, com.google.common.collect.ImmutableMap.of(), new SnowflakeJDBCCaseResolver(SNOWFLAKE_NAME, CaseResolver.FederationSDKCasingMode.NONE));
        this.federatedIdentity = Mockito.mock(FederatedIdentity.class);
        this.blockAllocator = Mockito.mock(BlockAllocator.class);
    }


    @Test
    public void getPartitionSchema() {
        Assert.assertEquals(SchemaBuilder.newBuilder()
                        .addField("partition", org.apache.arrow.vector.types.Types.MinorType.VARCHAR.getType()).build(),
                this.snowflakeMetadataHandler.getPartitionSchema("testCatalogName"));
    }

    @Test
    public void doGetTableLayout()
            throws Exception {
        BlockAllocator blockAllocator = new BlockAllocatorImpl();
        Constraints constraints = Mockito.mock(Constraints.class);
        TableName tableName = new TableName("testSchema", "testTable");
        Schema partitionSchema = this.snowflakeMetadataHandler.getPartitionSchema("testCatalogName");
        Set<String> partitionCols = new HashSet<>(Arrays.asList("partition"));
        GetTableLayoutRequest getTableLayoutRequest = new GetTableLayoutRequest(this.federatedIdentity, "testQueryId", "testCatalogName", tableName, constraints, partitionSchema, partitionCols);
        PreparedStatement preparedStatement = Mockito.mock(PreparedStatement.class);
        Mockito.when(this.connection.prepareStatement(SnowflakeMetadataHandler.COUNT_RECORDS_QUERY)).thenReturn(preparedStatement);
        String[] columns = {"partition"};
        int[] types = {Types.VARCHAR};
        Object[][] values = {{"partition : partition-primary-pkey-limit-500000-offset-0"},{"partition : partition-primary-pkey-limit-500000-offset-500000"}};
        ResultSet resultSet = mockResultSet(columns, types, values, new AtomicInteger(-1));
        Mockito.when(preparedStatement.executeQuery()).thenReturn(resultSet);
        Mockito.when(this.connection.getMetaData().getSearchStringEscape()).thenReturn(null);
        double totalActualRecordCount = 10001;
        Mockito.when(resultSet.getLong(1)).thenReturn((long) totalActualRecordCount);

        PreparedStatement primaryKeyPreparedStatement = Mockito.mock(PreparedStatement.class); 
        String[] primaryKeyColumns = new String[] {SnowflakeMetadataHandler.PRIMARY_KEY_COLUMN_NAME};
        String[][] primaryKeyValues = new String[][]{new String[] {"pkey"}};
        ResultSet primaryKeyResultSet = mockResultSet(primaryKeyColumns, primaryKeyValues, new AtomicInteger(-1));
        Mockito.when(this.connection.prepareStatement(SnowflakeMetadataHandler.SHOW_PRIMARY_KEYS_QUERY + "\"testSchema\"" + "." + "\"testTable\"")).thenReturn(primaryKeyPreparedStatement);
        Mockito.when(primaryKeyPreparedStatement.executeQuery()).thenReturn(primaryKeyResultSet);

        PreparedStatement countsPreparedStatement = Mockito.mock(PreparedStatement.class);
        String GET_PKEY_COUNTS_QUERY = "SELECT \"pkey\", count(*) as COUNTS FROM \"testSchema\".\"testTable\" GROUP BY \"pkey\" ORDER BY COUNTS DESC";
        String[] countsColumns = new String[] {"pkey", SnowflakeMetadataHandler.COUNTS_COLUMN_NAME};
        Object[][] countsValues = {{"a", 1}};
        ResultSet countsResultSet = mockResultSet(countsColumns, countsValues, new AtomicInteger(-1));
        Mockito.when(this.connection.prepareStatement(GET_PKEY_COUNTS_QUERY)).thenReturn(countsPreparedStatement);
        Mockito.when(countsPreparedStatement.executeQuery()).thenReturn(countsResultSet);

        GetTableLayoutResponse getTableLayoutResponse = this.snowflakeMetadataHandler.doGetTableLayout(blockAllocator, getTableLayoutRequest);
        List<String> expectedValues = new ArrayList<>();
        for (int i = 0; i < getTableLayoutResponse.getPartitions().getRowCount(); i++) {
            expectedValues.add(BlockUtils.rowToString(getTableLayoutResponse.getPartitions(), i));
        }
        List<String> actualValues = new ArrayList<>();
        long pageCount = (long) (Math.ceil(totalActualRecordCount / MAX_PARTITION_COUNT));
        long partitionActualRecordCount = (totalActualRecordCount <= 10000) ? (long) totalActualRecordCount : pageCount;
        double limit = (int) Math.ceil(totalActualRecordCount / partitionActualRecordCount);
        long offset = 0;
        for (int i = 1; i <= limit; i++) {
            if (i > 1) {
                offset = offset + partitionActualRecordCount;
            }
            actualValues.add("[partition : partition-primary-\"pkey\"-limit-" + + partitionActualRecordCount + "-offset-" + offset + "]");
        }
        Assert.assertEquals((int)limit, getTableLayoutResponse.getPartitions().getRowCount());
        Assert.assertEquals(expectedValues, actualValues);
        SchemaBuilder expectedSchemaBuilder = SchemaBuilder.newBuilder();
        expectedSchemaBuilder.addField(FieldBuilder.newBuilder("partition", org.apache.arrow.vector.types.Types.MinorType.VARCHAR.getType()).build());
        Schema expectedSchema = expectedSchemaBuilder.build();
        Assert.assertEquals(expectedSchema, getTableLayoutResponse.getPartitions().getSchema());
        Assert.assertEquals(tableName, getTableLayoutResponse.getTableName());
        Mockito.verify(preparedStatement, Mockito.times(1)).setString(1, tableName.getSchemaName());
        Mockito.verify(resultSet, Mockito.times(2)).getLong(1);
    }

    @Test
    public void doGetTableLayoutSinglePartition()
            throws Exception {
        BlockAllocator blockAllocator = new BlockAllocatorImpl();
        Constraints constraints = Mockito.mock(Constraints.class);
        TableName tableName = new TableName("testSchema", "testTable");
        Schema partitionSchema = this.snowflakeMetadataHandler.getPartitionSchema("testCatalogName");
        Set<String> partitionCols = new HashSet<>(Arrays.asList("partition"));
        GetTableLayoutRequest getTableLayoutRequest = new GetTableLayoutRequest(this.federatedIdentity, "testQueryId", "testCatalogName", tableName, constraints, partitionSchema, partitionCols);
        PreparedStatement preparedStatement = Mockito.mock(PreparedStatement.class);

        Mockito.when(this.connection.prepareStatement(SnowflakeMetadataHandler.COUNT_RECORDS_QUERY)).thenReturn(preparedStatement);

        String[] columns = {"partition"};
        int[] types = {Types.VARCHAR};
        Object[][] values = {{"partition : partition-primary-pkey-limit-500000-offset-0"}};
        ResultSet resultSet = mockResultSet(columns, types, values, new AtomicInteger(-1));
        Mockito.when(preparedStatement.executeQuery()).thenReturn(resultSet);

        Mockito.when(this.connection.getMetaData().getSearchStringEscape()).thenReturn(null);
        Mockito.when(resultSet.getLong(1)).thenReturn(1001L);

        PreparedStatement primaryKeyPreparedStatement = Mockito.mock(PreparedStatement.class);
        String[] primaryKeyColumns = new String[] {SnowflakeMetadataHandler.PRIMARY_KEY_COLUMN_NAME};
        String[][] primaryKeyValues = new String[][]{new String[] {""}};
        ResultSet primaryKeyResultSet = mockResultSet(primaryKeyColumns, primaryKeyValues, new AtomicInteger(-1));
        Mockito.when(this.connection.prepareStatement(SnowflakeMetadataHandler.SHOW_PRIMARY_KEYS_QUERY +              "testTable")).thenReturn(primaryKeyPreparedStatement);
        Mockito.when(primaryKeyPreparedStatement.executeQuery()).thenReturn(primaryKeyResultSet);

        PreparedStatement countsPreparedStatement = Mockito.mock(PreparedStatement.class);
        String GET_PKEY_COUNTS_QUERY = "SELECT \"pkey\", count(*) as COUNTS FROM \"testSchema\".\"testTable\" GROUP BY \"pkey\" ORDER BY COUNTS DESC";
        String[] countsColumns = new String[] {"pkey", SnowflakeMetadataHandler.COUNTS_COLUMN_NAME};
        Object[][] countsValues = {{"a", 1}};
        ResultSet countsResultSet = mockResultSet(countsColumns, countsValues, new AtomicInteger(-1));
        Mockito.when(this.connection.prepareStatement(GET_PKEY_COUNTS_QUERY)).thenReturn(countsPreparedStatement);
        Mockito.when(countsPreparedStatement.executeQuery()).thenReturn(countsResultSet);

        GetTableLayoutResponse getTableLayoutResponse = this.snowflakeMetadataHandler.doGetTableLayout(blockAllocator, getTableLayoutRequest);

        Assert.assertEquals(values.length, getTableLayoutResponse.getPartitions().getRowCount());

        List<String> expectedValues = new ArrayList<>();
        for (int i = 0; i < getTableLayoutResponse.getPartitions().getRowCount(); i++) {
            expectedValues.add(BlockUtils.rowToString(getTableLayoutResponse.getPartitions(), i));
        }
        Assert.assertEquals(expectedValues, Arrays.asList("[partition : partition-primary--limit-1001-offset-0]"));

        SchemaBuilder expectedSchemaBuilder = SchemaBuilder.newBuilder();
        expectedSchemaBuilder.addField(FieldBuilder.newBuilder("partition", org.apache.arrow.vector.types.Types.MinorType.VARCHAR.getType()).build());
        Schema expectedSchema = expectedSchemaBuilder.build();
        Assert.assertEquals(expectedSchema, getTableLayoutResponse.getPartitions().getSchema());
        Assert.assertEquals(tableName, getTableLayoutResponse.getTableName());

        Mockito.verify(preparedStatement, Mockito.times(1)).setString(1, tableName.getSchemaName());
        Mockito.verify(preparedStatement, Mockito.times(1)).setString(2, tableName.getTableName());
        Mockito.verify(resultSet, Mockito.times(1)).getLong(1);
    }

    @Test
    public void doGetTableLayoutMaxPartition()
            throws Exception {
        BlockAllocator blockAllocator = new BlockAllocatorImpl();
        Constraints constraints = Mockito.mock(Constraints.class);
        TableName tableName = new TableName("testSchema", "testTable");
        Schema partitionSchema = this.snowflakeMetadataHandler.getPartitionSchema("testCatalogName");
        Set<String> partitionCols = new HashSet<>(Arrays.asList("partition"));
        GetTableLayoutRequest getTableLayoutRequest = new GetTableLayoutRequest(this.federatedIdentity, "testQueryId", "testCatalogName", tableName, constraints, partitionSchema, partitionCols);
        PreparedStatement preparedStatement = Mockito.mock(PreparedStatement.class);
        Mockito.when(this.connection.prepareStatement(SnowflakeMetadataHandler.COUNT_RECORDS_QUERY)).thenReturn(preparedStatement);
        //By changing the value of variable totalActualRecordCount,we can check the maximum number of partitions supported by the table dynamically
        double totalActualRecordCount = 7256888;
        long pageCount = (long) (Math.ceil(totalActualRecordCount / MAX_PARTITION_COUNT));
        long partitionActualRecordCount = (totalActualRecordCount <= 10000) ? (long) totalActualRecordCount : pageCount;
        double limit = (int) Math.ceil(totalActualRecordCount / partitionActualRecordCount);
        long offset = 0;
        String[] columns = {"partition"};
        int[] types = {Types.VARCHAR};
        Object[][] values = {{"partition : partition-primary-pkey-limit-500000-offset-0"}};
        ResultSet resultSet = mockResultSet(columns, types, values, new AtomicInteger(-1));
        Mockito.when(preparedStatement.executeQuery()).thenReturn(resultSet);
        Mockito.when(this.connection.getMetaData().getSearchStringEscape()).thenReturn(null);
        Mockito.when(resultSet.getLong(1)).thenReturn((long)totalActualRecordCount);
        
        PreparedStatement primaryKeyPreparedStatement = Mockito.mock(PreparedStatement.class);
        String[] primaryKeyColumns = new String[] {SnowflakeMetadataHandler.PRIMARY_KEY_COLUMN_NAME};
        String[][] primaryKeyValues = new String[][]{new String[] {"pkey"}};
        ResultSet primaryKeyResultSet = mockResultSet(primaryKeyColumns, primaryKeyValues, new AtomicInteger(-1));
        Mockito.when(this.connection.prepareStatement(SnowflakeMetadataHandler.SHOW_PRIMARY_KEYS_QUERY + "\"testSchema\"" + "." + "\"testTable\"")).thenReturn(primaryKeyPreparedStatement);
        Mockito.when(primaryKeyPreparedStatement.executeQuery()).thenReturn(primaryKeyResultSet);

        PreparedStatement countsPreparedStatement = Mockito.mock(PreparedStatement.class);
        String GET_PKEY_COUNTS_QUERY = "SELECT \"pkey\", count(*) as COUNTS FROM \"testSchema\".\"testTable\" GROUP BY \"pkey\" ORDER BY COUNTS DESC";
        String[] countsColumns = new String[] {"\"pkey\"", SnowflakeMetadataHandler.COUNTS_COLUMN_NAME};
        Object[][] countsValues = {{"a", 1}};
        ResultSet countsResultSet = mockResultSet(countsColumns, countsValues, new AtomicInteger(-1));
        Mockito.when(this.connection.prepareStatement(GET_PKEY_COUNTS_QUERY)).thenReturn(countsPreparedStatement);
        Mockito.when(countsPreparedStatement.executeQuery()).thenReturn(countsResultSet);
    
        GetTableLayoutResponse getTableLayoutResponse = this.snowflakeMetadataHandler.doGetTableLayout(blockAllocator, getTableLayoutRequest);
        List<String> actualValues = new ArrayList<>();
        List<String> expectedValues = new ArrayList<>();
        for (int i = 0; i < getTableLayoutResponse.getPartitions().getRowCount(); i++) {
            expectedValues.add(BlockUtils.rowToString(getTableLayoutResponse.getPartitions(), i));
        }
        for (int i = 1; i <= limit; i++) {
            if (i > 1) {
                offset = offset + partitionActualRecordCount;
            }
            actualValues.add("[partition : partition-primary-\"pkey\"-limit-" +partitionActualRecordCount + "-offset-" + offset + "]");
        }
        Assert.assertEquals(expectedValues,actualValues);
        SchemaBuilder expectedSchemaBuilder = SchemaBuilder.newBuilder();
        expectedSchemaBuilder.addField(FieldBuilder.newBuilder("partition", org.apache.arrow.vector.types.Types.MinorType.VARCHAR.getType()).build());
        Schema expectedSchema = expectedSchemaBuilder.build();
        Assert.assertEquals(expectedSchema, getTableLayoutResponse.getPartitions().getSchema());
        Assert.assertEquals(tableName, getTableLayoutResponse.getTableName());
        Mockito.verify(preparedStatement, Mockito.times(1)).setString(1, tableName.getSchemaName());
        Mockito.verify(preparedStatement, Mockito.times(1)).setString(2, tableName.getTableName());
        Mockito.verify(resultSet, Mockito.times(1)).getLong(1);
    }

    @Test(expected = RuntimeException.class)
    public void doGetTableLayoutWithSQLException()
            throws Exception {
        Constraints constraints = Mockito.mock(Constraints.class);
        TableName tableName = new TableName("testSchema", "testTable");
        Schema partitionSchema = this.snowflakeMetadataHandler.getPartitionSchema("testCatalogName");
        Set<String> partitionCols = partitionSchema.getFields().stream().map(Field::getName).collect(Collectors.toSet());
        GetTableLayoutRequest getTableLayoutRequest = new GetTableLayoutRequest(this.federatedIdentity, "testQueryId", "testCatalogName", tableName, constraints, partitionSchema, partitionCols);

        Connection connection = Mockito.mock(Connection.class, Mockito.RETURNS_DEEP_STUBS);
        JdbcConnectionFactory jdbcConnectionFactory = Mockito.mock(JdbcConnectionFactory.class);
        Mockito.when(jdbcConnectionFactory.getConnection(nullable(CredentialsProvider.class))).thenReturn(connection);
        Mockito.when(connection.getMetaData().getSearchStringEscape()).thenThrow(new SQLException());
        SnowflakeMetadataHandler snowflakeMetadataHandler = new SnowflakeMetadataHandler(databaseConnectionConfig, this.secretsManager, this.athena, jdbcConnectionFactory, com.google.common.collect.ImmutableMap.of(), new SnowflakeJDBCCaseResolver(SNOWFLAKE_NAME));

        snowflakeMetadataHandler.doGetTableLayout(Mockito.mock(BlockAllocator.class), getTableLayoutRequest);
    }

    @Test
    public void doListPaginatedTables()
            throws Exception
    {
        BlockAllocator blockAllocator = new BlockAllocatorImpl();

        //Test 1: Testing Single table returned in request of page size 1 and nextToken null
        PreparedStatement preparedStatement = Mockito.mock(PreparedStatement.class);
        Mockito.when(this.connection.prepareStatement(snowflakeMetadataHandler.LIST_PAGINATED_TABLES_QUERY)).thenReturn(preparedStatement);
        String[] schema = {"TABLE_SCHEM", "TABLE_NAME"};
        Object[][] values = {{"testSchema", "testTable"}};
        TableName[] expected = {new TableName("testSchema", "testTable")};
        ResultSet resultSet = mockResultSet(schema, values, new AtomicInteger(-1));
        Mockito.when(preparedStatement.executeQuery()).thenReturn(resultSet);

        ListTablesResponse listTablesResponse = this.snowflakeMetadataHandler.doListTables(
                blockAllocator, new ListTablesRequest(this.federatedIdentity, "testQueryId",
                        "testCatalog", "testSchema", null, 1));

        Assert.assertEquals("1", listTablesResponse.getNextToken());
        Assert.assertArrayEquals(expected, listTablesResponse.getTables().toArray());

        // Test 2: Testing next table returned of page size 1 and nextToken 1
        preparedStatement = Mockito.mock(PreparedStatement.class);
        Mockito.when(this.connection.prepareStatement(snowflakeMetadataHandler.LIST_PAGINATED_TABLES_QUERY)).thenReturn(preparedStatement);
        values = new Object[][]{{"testSchema", "testTable2"}};
        expected = new TableName[]{new TableName("testSchema", "testTable2")};
        resultSet = mockResultSet(schema, values, new AtomicInteger(-1));
        Mockito.when(preparedStatement.executeQuery()).thenReturn(resultSet);
        listTablesResponse = this.snowflakeMetadataHandler.doListTables(
                blockAllocator, new ListTablesRequest(this.federatedIdentity, "testQueryId",
                        "testCatalog", "testSchema", "1", 1));
        Assert.assertEquals("2", listTablesResponse.getNextToken());
        Assert.assertArrayEquals(expected, listTablesResponse.getTables().toArray());


        // Test 3: Testing single table returned when requesting pageSize 2 signifying end of pagination where nextToken is null.
        preparedStatement = Mockito.mock(PreparedStatement.class);
        Mockito.when(this.connection.prepareStatement(snowflakeMetadataHandler.LIST_PAGINATED_TABLES_QUERY)).thenReturn(preparedStatement);
        values = new Object[][]{{"testSchema", "testTable2"}};
        expected = new TableName[]{new TableName("testSchema", "testTable2")};
        resultSet = mockResultSet(schema, values, new AtomicInteger(-1));
        Mockito.when(preparedStatement.executeQuery()).thenReturn(resultSet);

        listTablesResponse = this.snowflakeMetadataHandler.doListTables(
                blockAllocator, new ListTablesRequest(this.federatedIdentity, "testQueryId",
                        "testCatalog", "testSchema", "2", 2));
        Assert.assertEquals(null, listTablesResponse.getNextToken());
        Assert.assertArrayEquals(expected, listTablesResponse.getTables().toArray());

        // Test 4: nextToken is 2 and pageSize is UNLIMITED. Return all tables starting from index 2.
        preparedStatement = Mockito.mock(PreparedStatement.class);
        Mockito.when(this.connection.prepareStatement(snowflakeMetadataHandler.LIST_PAGINATED_TABLES_QUERY)).thenReturn(preparedStatement);
        values = new Object[][]{{"testSchema", "testTable3"}, {"testSchema", "testTable4"}};
        expected = new TableName[]{new TableName("testSchema", "testTable3"), new TableName("testSchema", "testTable4")};
        resultSet = mockResultSet(schema, values, new AtomicInteger(-1));
        Mockito.when(preparedStatement.executeQuery()).thenReturn(resultSet);

        listTablesResponse = this.snowflakeMetadataHandler.doListTables(
                blockAllocator, new ListTablesRequest(this.federatedIdentity, "testQueryId",
                        "testCatalog", "testSchema", "2", ListTablesRequest.UNLIMITED_PAGE_SIZE_VALUE));
        Assert.assertEquals(null, listTablesResponse.getNextToken());
        Assert.assertArrayEquals(expected, listTablesResponse.getTables().toArray());

        // Test 5: AthenaConnectorException with negative nextToken value
        preparedStatement = Mockito.mock(PreparedStatement.class);
        Mockito.when(this.connection.prepareStatement(snowflakeMetadataHandler.LIST_PAGINATED_TABLES_QUERY)).thenReturn(preparedStatement);
        values = new Object[][]{{"testSchema", "testTable3"}, {"testSchema", "testTable4"}};
        expected = new TableName[]{new TableName("testSchema", "testTable3"), new TableName("testSchema", "testTable4")};
        resultSet = mockResultSet(schema, values, new AtomicInteger(-1));
        Mockito.when(preparedStatement.executeQuery()).thenReturn(resultSet);

        Assert.assertThrows(AthenaConnectorException.class, () -> this.snowflakeMetadataHandler.doListTables(
                blockAllocator, new ListTablesRequest(this.federatedIdentity, "testQueryId",
                        "testCatalog", "testSchema", "-1", 3)));
        Assert.assertEquals(null, listTablesResponse.getNextToken());
        Assert.assertArrayEquals(expected, listTablesResponse.getTables().toArray());

        // Test 6: AthenaConnectorException with negative pageSize value
        preparedStatement = Mockito.mock(PreparedStatement.class);
        Mockito.when(this.connection.prepareStatement(snowflakeMetadataHandler.LIST_PAGINATED_TABLES_QUERY)).thenReturn(preparedStatement);
        values = new Object[][]{{"testSchema", "testTable3"}, {"testSchema", "testTable4"}};
        expected = new TableName[]{new TableName("testSchema", "testTable3"), new TableName("testSchema", "testTable4")};
        resultSet = mockResultSet(schema, values, new AtomicInteger(-1));
        Mockito.when(preparedStatement.executeQuery()).thenReturn(resultSet);

        Assert.assertThrows(AthenaConnectorException.class, () -> this.snowflakeMetadataHandler.doListTables(
                blockAllocator, new ListTablesRequest(this.federatedIdentity, "testQueryId",
                        "testCatalog", "testSchema", "0", -3)));
        Assert.assertEquals(null, listTablesResponse.getNextToken());
        Assert.assertArrayEquals(expected, listTablesResponse.getTables().toArray());
    }

    @Test
    public void doGetSplits()
            throws Exception {
        BlockAllocator blockAllocator = new BlockAllocatorImpl();
        Constraints constraints = Mockito.mock(Constraints.class);
        TableName tableName = new TableName("testSchema", "testTable");

        PreparedStatement preparedStatement = Mockito.mock(PreparedStatement.class);

        String[] columns = {"partition"};
        int[] types = {Types.VARCHAR};
        Object[][] values = {{"p0"}, {"p1"}};
        ResultSet resultSet = mockResultSet(columns, types, values, new AtomicInteger(-1));

        Mockito.when(preparedStatement.executeQuery()).thenReturn(resultSet);
        Mockito.when(this.connection.getMetaData().getSearchStringEscape()).thenReturn(null);

        Schema partitionSchema = this.snowflakeMetadataHandler.getPartitionSchema("testCatalogName");
        Set<String> partitionCols = partitionSchema.getFields().stream().map(Field::getName).collect(Collectors.toSet());
        GetTableLayoutRequest getTableLayoutRequest = new GetTableLayoutRequest(this.federatedIdentity, "testQueryId", "testCatalogName", tableName, constraints, partitionSchema, partitionCols);

        String GET_PARTITIONS_QUERY = "Select count(*) FROM " + getTableLayoutRequest.getTableName().getSchemaName() + "." +
                getTableLayoutRequest.getTableName().getTableName();
        Mockito.when(this.connection.prepareStatement(GET_PARTITIONS_QUERY)).thenReturn(preparedStatement);

        GetTableLayoutResponse getTableLayoutResponse = this.snowflakeMetadataHandler.doGetTableLayout(blockAllocator, getTableLayoutRequest);

        BlockAllocator splitBlockAllocator = new BlockAllocatorImpl();
        GetSplitsRequest getSplitsRequest = new GetSplitsRequest(this.federatedIdentity, "testQueryId", "testCatalogName", tableName, getTableLayoutResponse.getPartitions(), new ArrayList<>(partitionCols), constraints, null);
        GetSplitsResponse getSplitsResponse = this.snowflakeMetadataHandler.doGetSplits(splitBlockAllocator, getSplitsRequest);

        Set<Map<String, String>> expectedSplits = new HashSet<>();
        expectedSplits.add(Collections.singletonMap("partition", "p0"));
        expectedSplits.add(Collections.singletonMap("partition", "p1"));
        Assert.assertNotEquals(expectedSplits.size(), getSplitsResponse.getSplits().size());
        Set<Map<String, String>> actualSplits = getSplitsResponse.getSplits().stream().map(Split::getProperties).collect(Collectors.toSet());
        Assert.assertNotEquals(expectedSplits, actualSplits);
    }

    @Test
    public void doGetSplitsContinuation()
            throws Exception {
        BlockAllocator blockAllocator = new BlockAllocatorImpl();
        Constraints constraints = Mockito.mock(Constraints.class);
        TableName tableName = new TableName("testSchema", "testTable");
        Schema partitionSchema = this.snowflakeMetadataHandler.getPartitionSchema("testCatalogName");
        Set<String> partitionCols = partitionSchema.getFields().stream().map(Field::getName).collect(Collectors.toSet());
        GetTableLayoutRequest getTableLayoutRequest = new GetTableLayoutRequest(this.federatedIdentity, "testQueryId", "testCatalogName", tableName, constraints, partitionSchema, partitionCols);

        PreparedStatement preparedStatement = Mockito.mock(PreparedStatement.class);
        String GET_PARTITIONS_QUERY = "Select DISTINCT partition FROM " + getTableLayoutRequest.getTableName().getSchemaName() + "." +
                getTableLayoutRequest.getTableName().getTableName() + " where 1= ?";
        Mockito.when(this.connection.prepareStatement(GET_PARTITIONS_QUERY)).thenReturn(preparedStatement);

        String[] columns = {"partition"};
        int[] types = {Types.VARCHAR};
        Object[][] values = {{"p0"}, {"p1"}};
        ResultSet resultSet = mockResultSet(columns, types, values, new AtomicInteger(-1));
        Mockito.when(preparedStatement.executeQuery()).thenReturn(resultSet);

        Mockito.when(this.connection.getMetaData().getSearchStringEscape()).thenReturn(null);

        GetTableLayoutResponse getTableLayoutResponse = this.snowflakeMetadataHandler.doGetTableLayout(blockAllocator, getTableLayoutRequest);

        BlockAllocator splitBlockAllocator = new BlockAllocatorImpl();
        GetSplitsRequest getSplitsRequest = new GetSplitsRequest(this.federatedIdentity, "testQueryId", "testCatalogName", tableName, getTableLayoutResponse.getPartitions(), new ArrayList<>(partitionCols), constraints, "1");
        GetSplitsResponse getSplitsResponse = this.snowflakeMetadataHandler.doGetSplits(splitBlockAllocator, getSplitsRequest);

        Set<Map<String, String>> expectedSplits = new HashSet<>();
        expectedSplits.add(Collections.singletonMap("partition", "p1"));
        Assert.assertNotEquals(expectedSplits.size(), getSplitsResponse.getSplits().size());
        Set<Map<String, String>> actualSplits = getSplitsResponse.getSplits().stream().map(Split::getProperties).collect(Collectors.toSet());
        Assert.assertNotEquals(expectedSplits, actualSplits);
    }

    @Test(expected = RuntimeException.class)
    public void doGetTableNoColumns() throws Exception
    {
        TableName inputTableName = new TableName("testSchema", "testTable");

        this.snowflakeMetadataHandler.doGetTable(this.blockAllocator, new GetTableRequest(this.federatedIdentity, "testQueryId", "testCatalog", inputTableName, Collections.emptyMap()));
    }

    @Test(expected = SQLException.class)
    public void doGetTableSQLException()
            throws Exception
    {
        TableName inputTableName = new TableName("testSchema", "testTable");
        Mockito.when(this.connection.getMetaData().getColumns(nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class)))
                .thenThrow(new SQLException());
        this.snowflakeMetadataHandler.doGetTable(this.blockAllocator, new GetTableRequest(this.federatedIdentity, "testQueryId", "testCatalog", inputTableName, Collections.emptyMap()));
    }

    @Test(expected = RuntimeException.class)
    public void doGetTableException() throws Exception
    {
        TableName inputTableName = new TableName("testSchema", "test@schema");
        this.snowflakeMetadataHandler.doGetTable(this.blockAllocator, new GetTableRequest(this.federatedIdentity, "testQueryId", "testCatalog", inputTableName, Collections.emptyMap()));
    }

    @Test(expected = RuntimeException.class)
    public void doGetTableNoColumnsException() throws Exception
    {
        TableName inputTableName = new TableName("testSchema", "test@table");
        this.snowflakeMetadataHandler.doGetTable(this.blockAllocator, new GetTableRequest(this.federatedIdentity, "testQueryId", "testCatalog", inputTableName, Collections.emptyMap()));
    }

    @Test
    public void doGetTable()
            throws Exception
    {
        String[] schema = {"DATA_TYPE", "COLUMN_SIZE", "COLUMN_NAME", "DECIMAL_DIGITS", "NUM_PREC_RADIX"};
        Object[][] values = {{Types.INTEGER, 12, "testCol1", 0, 0}, {Types.VARCHAR, 25, "testCol2", 0, 0},
                {Types.TIMESTAMP, 93, "testCol3", 0, 0},  {Types.TIMESTAMP_WITH_TIMEZONE, 93, "testCol4", 0, 0}};
        AtomicInteger rowNumber = new AtomicInteger(-1);
        ResultSet resultSet = mockResultSet(schema, values, rowNumber);

        SchemaBuilder expectedSchemaBuilder = SchemaBuilder.newBuilder();
        expectedSchemaBuilder.addField(FieldBuilder.newBuilder("testCol1", org.apache.arrow.vector.types.Types.MinorType.INT.getType()).build());
        expectedSchemaBuilder.addField(FieldBuilder.newBuilder("testCol2", org.apache.arrow.vector.types.Types.MinorType.VARCHAR.getType()).build());
        expectedSchemaBuilder.addField(FieldBuilder.newBuilder("testCol3", org.apache.arrow.vector.types.Types.MinorType.DATEMILLI.getType()).build());
        expectedSchemaBuilder.addField(FieldBuilder.newBuilder("testCol4", org.apache.arrow.vector.types.Types.MinorType.DATEMILLI.getType()).build());

        PARTITION_SCHEMA.getFields().forEach(expectedSchemaBuilder::addField);
        Schema expected = expectedSchemaBuilder.build();

        TableName inputTableName = new TableName("TESTSCHEMA", "TESTTABLE");
        Mockito.when(connection.getMetaData().getColumns("testCatalog", inputTableName.getSchemaName(), inputTableName.getTableName(), null)).thenReturn(resultSet);
        Mockito.when(connection.getCatalog()).thenReturn("testCatalog");

        GetTableResponse getTableResponse = this.snowflakeMetadataHandler.doGetTable(
                this.blockAllocator, new GetTableRequest(this.federatedIdentity, "testQueryId", "testCatalog", inputTableName, Collections.emptyMap()));

        Assert.assertEquals(expected, getTableResponse.getSchema());
        Assert.assertEquals(inputTableName, getTableResponse.getTableName());
        Assert.assertEquals("testCatalog", getTableResponse.getCatalogName());
    }

    @Test(expected = RuntimeException.class)
    public void doListSchemaNames() throws Exception {
        BlockAllocator blockAllocator = new BlockAllocatorImpl();
        ListSchemasRequest listSchemasRequest = new ListSchemasRequest(federatedIdentity, "queryId", "testCatalog");

        Statement statement = Mockito.mock(Statement.class);
        Mockito.when(this.connection.createStatement()).thenReturn(statement);
        String[][] SchemaandCatalogNames = {{"TESTSCHEMA"},{"TESTCATALOG"}};
        ResultSet schemaResultSet = mockResultSet(new String[]{"TABLE_SCHEM","TABLE_CATALOG"}, new int[]{Types.VARCHAR,Types.VARCHAR}, SchemaandCatalogNames, new AtomicInteger(-1));
        Mockito.when(this.connection.getMetaData().getSchemas(any(), any())).thenReturn(schemaResultSet);
        ListSchemasResponse listSchemasResponse = this.snowflakeMetadataHandler.doListSchemaNames(blockAllocator, listSchemasRequest);
        String[] expectedResult = {"TESTSCHEMA","TESTCATALOG"};
        Assert.assertEquals(Arrays.toString(expectedResult), listSchemasResponse.getSchemas().toString());
    }
}
