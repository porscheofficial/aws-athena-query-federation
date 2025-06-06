/*-
 * #%L
 * athena-jdbc
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
package com.amazonaws.athena.connectors.jdbc.manager;

import com.amazonaws.athena.connector.lambda.domain.Split;
import com.amazonaws.athena.connector.lambda.domain.predicate.Constraints;
import com.amazonaws.athena.connector.lambda.domain.predicate.OrderByField;
import com.amazonaws.athena.connector.lambda.domain.predicate.Range;
import com.amazonaws.athena.connector.lambda.domain.predicate.SortedRangeSet;
import com.amazonaws.athena.connector.lambda.domain.predicate.ValueSet;
import com.amazonaws.athena.connector.lambda.exceptions.AthenaConnectorException;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.Iterables;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.arrow.vector.types.Types;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.glue.model.ErrorDetails;
import software.amazon.awssdk.services.glue.model.FederationSourceErrorCode;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Query builder for database table split.
 */
public abstract class JdbcSplitQueryBuilder
{
    private static final Logger LOGGER = LoggerFactory.getLogger(JdbcSplitQueryBuilder.class);

    private static final int MILLIS_SHIFT = 12;

    private final String quoteCharacters;
    protected final String emptyString = "";

    private final FederationExpressionParser jdbcFederationExpressionParser;

    /**
     * Meant for connectors which do not yet support complex expressions.
     */
    public JdbcSplitQueryBuilder(String quoteCharacters)
    {
        this(quoteCharacters, new DefaultJdbcFederationExpressionParser());
    }

    /**
     * @param quoteCharacters database quote character for enclosing identifiers.
     */
    public JdbcSplitQueryBuilder(String quoteCharacters, FederationExpressionParser federationExpressionParser)
    {
        this.quoteCharacters = quoteCharacters;
        this.jdbcFederationExpressionParser = federationExpressionParser;
    }

    /**
     * Common logic to build Split SQL including constraints translated in where clause.
     *
     * @param jdbcConnection JDBC connection. See {@link Connection}.
     * @param catalog Athena provided catalog name.
     * @param schema table schema name.
     * @param table table name.
     * @param tableSchema table schema (column and type information).
     * @param constraints constraints passed by Athena to push down.
     * @param split table split.
     * @return prepated statement with SQL. See {@link PreparedStatement}.
     * @throws SQLException JDBC database exception.
     */
    @SuppressFBWarnings("SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING")
    public PreparedStatement buildSql(
            final Connection jdbcConnection,
            final String catalog,
            final String schema,
            final String table,
            final Schema tableSchema,
            final Constraints constraints,
            final Split split)
            throws SQLException
    {
        String columnNames = tableSchema.getFields().stream()
                .map(Field::getName)
                .filter(c -> !split.getProperties().containsKey(c))
                .map(this::quote)
                .collect(Collectors.joining(", "));
        return prepareStatementWithSql(jdbcConnection, catalog, schema, table, tableSchema, constraints, split, columnNames);
    }

    protected PreparedStatement prepareStatementWithSql(
            final Connection jdbcConnection,
            final String catalog,
            final String schema,
            final String table,
            final Schema tableSchema,
            final Constraints constraints,
            final Split split,
            final String columnNames)
            throws SQLException
    {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ");
        sql.append(columnNames);

        if (columnNames.isEmpty()) {
            sql.append("null");
        }
        sql.append(getFromClauseWithSplit(catalog, schema, table, split));

        List<TypeAndValue> accumulator = new ArrayList<>();

        List<String> clauses = toConjuncts(tableSchema.getFields(), constraints, accumulator, split.getProperties());
        clauses.addAll(getPartitionWhereClauses(split));
        if (!clauses.isEmpty()) {
            sql.append(" WHERE ")
                    .append(Joiner.on(" AND ").join(clauses));
        }

        String orderByClause = extractOrderByClause(constraints);

        if (!Strings.isNullOrEmpty(orderByClause)) {
            sql.append(" ").append(orderByClause);
        }

        if (constraints.getLimit() > 0) {
            sql.append(appendLimitOffset(split, constraints));
        }
        else {
            sql.append(appendLimitOffset(split)); // legacy method to preserve functionality of existing connector impls
        }
        LOGGER.info("Generated SQL : {}", sql.toString());
        PreparedStatement statement = jdbcConnection.prepareStatement(sql.toString());
        // TODO all types, converts Arrow values to JDBC.
        for (int i = 0; i < accumulator.size(); i++) {
            TypeAndValue typeAndValue = accumulator.get(i);

            Types.MinorType minorTypeForArrowType = Types.getMinorTypeForArrowType(typeAndValue.getType());

            switch (minorTypeForArrowType) {
                case BIGINT:
                    statement.setLong(i + 1, (long) typeAndValue.getValue());
                    break;
                case INT:
                    statement.setInt(i + 1, ((Number) typeAndValue.getValue()).intValue());
                    break;
                case SMALLINT:
                    statement.setShort(i + 1, ((Number) typeAndValue.getValue()).shortValue());
                    break;
                case TINYINT:
                    statement.setByte(i + 1, ((Number) typeAndValue.getValue()).byteValue());
                    break;
                case FLOAT8:
                    statement.setDouble(i + 1, (double) typeAndValue.getValue());
                    break;
                case FLOAT4:
                    statement.setFloat(i + 1, (float) typeAndValue.getValue());
                    break;
                case BIT:
                    statement.setBoolean(i + 1, (boolean) typeAndValue.getValue());
                    break;
                case DATEDAY:
                    //we received value in "UTC" time with DAYS only, appended it to timeMilli in UTC
                    long utcMillis = TimeUnit.DAYS.toMillis(((Number) typeAndValue.getValue()).longValue());
                    //Get the default timezone offset and offset it.
                    //This is because sql.Date will parse millis into localtime zone
                    //ex system timezone in GMT-5, sql.Date will think the utcMillis is in GMT-5, we need to add offset(eg. -18000000) .
                    //ex system timezone in GMT+9, sql.Date will think the utcMillis is in GMT+9, we need to remove offset(eg. 32400000).
                    TimeZone aDefault = TimeZone.getDefault();
                    int offset = aDefault.getOffset(utcMillis);
                    utcMillis -= offset;

                    statement.setDate(i + 1, new Date(utcMillis));
                    break;
                case DATEMILLI:
                    LocalDateTime timestamp = ((LocalDateTime) typeAndValue.getValue());
                    statement.setTimestamp(i + 1, new Timestamp(timestamp.toInstant(ZoneOffset.UTC).toEpochMilli()));
                    break;
                case VARCHAR:
                    statement.setString(i + 1, String.valueOf(typeAndValue.getValue()));
                    break;
                case VARBINARY:
                    statement.setBytes(i + 1, (byte[]) typeAndValue.getValue());
                    break;
                case DECIMAL:
                    statement.setBigDecimal(i + 1, (BigDecimal) typeAndValue.getValue());
                    break;
                default:
                    throw new AthenaConnectorException(String.format("Can't handle type: %s, %s", typeAndValue.getType(), minorTypeForArrowType),
                            ErrorDetails.builder().errorCode(FederationSourceErrorCode.OPERATION_NOT_SUPPORTED_EXCEPTION.toString()).build());
            }
        }

        return statement;
    }

    protected String extractOrderByClause(Constraints constraints)
    {
        List<OrderByField> orderByClause = constraints.getOrderByClause();
        if (orderByClause == null || orderByClause.size() == 0) {
            return "";
        }
        return "ORDER BY " + orderByClause.stream()
            .map(orderByField -> {
                String ordering = orderByField.getDirection().isAscending() ? "ASC" : "DESC";
                String nullsHandling = orderByField.getDirection().isNullsFirst() ? "NULLS FIRST" : "NULLS LAST";
                return quote(orderByField.getColumnName()) + " " + ordering + " " + nullsHandling;
            })
            .collect(Collectors.joining(", "));
    }

    protected abstract String getFromClauseWithSplit(final String catalog, final String schema, final String table, final Split split);

    protected abstract List<String> getPartitionWhereClauses(final Split split);

    private List<String> toConjuncts(List<Field> columns, Constraints constraints, List<TypeAndValue> accumulator, Map<String, String> partitionSplit)
    {
        List<String> conjuncts = new ArrayList<>();
        for (Field column : columns) {
            if (partitionSplit.containsKey(column.getName())) {
                continue; // Ignore constraints on partition name as RDBMS does not contain these as columns. Presto will filter these values.
            }
            ArrowType type = column.getType();
            if (constraints.getSummary() != null && !constraints.getSummary().isEmpty()) {
                ValueSet valueSet = constraints.getSummary().get(column.getName());
                if (valueSet != null) {
                    conjuncts.add(toPredicate(column.getName(), valueSet, type, accumulator));
                }
            }
        }
        conjuncts.addAll(jdbcFederationExpressionParser.parseComplexExpressions(columns, constraints, accumulator)); // not part of loop bc not per-column
        return conjuncts;
    }

    private String toPredicate(String columnName, ValueSet valueSet, ArrowType type, List<TypeAndValue> accumulator)
    {
        List<String> disjuncts = new ArrayList<>();
        List<Object> singleValues = new ArrayList<>();

        // TODO Add isNone and isAll checks once we have data on nullability.

        if (valueSet instanceof SortedRangeSet) {
            if (valueSet.isNone() && valueSet.isNullAllowed()) {
                return String.format("(%s IS NULL)", quote(columnName));
            }

            if (valueSet.isNullAllowed()) {
                disjuncts.add(String.format("(%s IS NULL)", quote(columnName)));
            }

            List<Range> rangeList = ((SortedRangeSet) valueSet).getOrderedRanges();
            if (rangeList.size() == 1 && !valueSet.isNullAllowed() && rangeList.get(0).getLow().isLowerUnbounded() && rangeList.get(0).getHigh().isUpperUnbounded()) {
                return String.format("(%s IS NOT NULL)", quote(columnName));
            }

            for (Range range : valueSet.getRanges().getOrderedRanges()) {
                if (range.isSingleValue()) {
                    singleValues.add(range.getLow().getValue());
                }
                else {
                    List<String> rangeConjuncts = new ArrayList<>();
                    if (!range.getLow().isLowerUnbounded()) {
                        switch (range.getLow().getBound()) {
                            case ABOVE:
                                rangeConjuncts.add(toPredicate(columnName, ">", range.getLow().getValue(), type, accumulator));
                                break;
                            case EXACTLY:
                                rangeConjuncts.add(toPredicate(columnName, ">=", range.getLow().getValue(), type, accumulator));
                                break;
                            case BELOW:
                                throw new AthenaConnectorException("Low marker should never use BELOW bound",
                                        ErrorDetails.builder().errorCode(FederationSourceErrorCode.INVALID_INPUT_EXCEPTION.toString()).build());
                            default:
                                throw new AthenaConnectorException("Unhandled bound: " + range.getLow().getBound(),
                                        ErrorDetails.builder().errorCode(FederationSourceErrorCode.OPERATION_NOT_SUPPORTED_EXCEPTION.toString()).build());
                        }
                    }
                    if (!range.getHigh().isUpperUnbounded()) {
                        switch (range.getHigh().getBound()) {
                            case ABOVE:
                                throw new AthenaConnectorException("High marker should never use ABOVE bound",
                                        ErrorDetails.builder().errorCode(FederationSourceErrorCode.INVALID_INPUT_EXCEPTION.toString()).build());
                            case EXACTLY:
                                rangeConjuncts.add(toPredicate(columnName, "<=", range.getHigh().getValue(), type, accumulator));
                                break;
                            case BELOW:
                                rangeConjuncts.add(toPredicate(columnName, "<", range.getHigh().getValue(), type, accumulator));
                                break;
                            default:
                                throw new AthenaConnectorException("Unhandled bound: " + range.getHigh().getBound(),
                                        ErrorDetails.builder().errorCode(FederationSourceErrorCode.OPERATION_NOT_SUPPORTED_EXCEPTION.toString()).build());
                        }
                    }
                    // If rangeConjuncts is null, then the range was ALL, which should already have been checked for
                    Preconditions.checkState(!rangeConjuncts.isEmpty());
                    disjuncts.add("(" + Joiner.on(" AND ").join(rangeConjuncts) + ")");
                }
            }

            // Add back all of the possible single values either as an equality or an IN predicate
            if (singleValues.size() == 1) {
                disjuncts.add(toPredicate(columnName, "=", Iterables.getOnlyElement(singleValues), type, accumulator));
            }
            else if (singleValues.size() > 1) {
                for (Object value : singleValues) {
                    accumulator.add(new TypeAndValue(type, value));
                }
                String values = Joiner.on(",").join(Collections.nCopies(singleValues.size(), "?"));
                disjuncts.add(quote(columnName) + " IN (" + values + ")");
            }
        }

        return "(" + Joiner.on(" OR ").join(disjuncts) + ")";
    }

    protected String toPredicate(String columnName, String operator, Object value, ArrowType type,
                                 List<TypeAndValue> accumulator)
    {
        accumulator.add(new TypeAndValue(type, value));
        return quote(columnName) + " " + operator + " ?";
    }

    protected String quote(String name)
    {
        name = name.replace(quoteCharacters, quoteCharacters + quoteCharacters);
        return quoteCharacters + name + quoteCharacters;
    }

    protected String appendLimitOffset(Split split)
    {
        // keeping this method for connectors that still override this (SAP Hana + Snowflake)
        return emptyString;
    }

    protected String appendLimitOffset(Split split, Constraints constraints)
    {
        return " LIMIT " + constraints.getLimit();
    }
}
