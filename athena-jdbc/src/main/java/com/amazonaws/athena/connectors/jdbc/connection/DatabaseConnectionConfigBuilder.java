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
package com.amazonaws.athena.connectors.jdbc.connection;

import com.amazonaws.athena.connector.lambda.exceptions.AthenaConnectorException;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import software.amazon.awssdk.services.glue.model.ErrorDetails;
import software.amazon.awssdk.services.glue.model.FederationSourceErrorCode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.amazonaws.athena.connector.lambda.connection.EnvironmentConstants.DEFAULT_GLUE_CONNECTION;

/**
 * Builds connection configurations for all catalogs and databases provided in environment properties.
 */
public class DatabaseConnectionConfigBuilder
{
    public static final String CONNECTION_STRING_PROPERTY_SUFFIX = "_connection_string";
    public static final String DEFAULT_CONNECTION_STRING_PROPERTY = "default";
    private static final int MUX_CATALOG_LIMIT = 100;

    private static final String CONNECTION_STRING_REGEX = "([a-zA-Z0-9]+)://(.*)";
    private static final Pattern CONNECTION_STRING_PATTERN = Pattern.compile(CONNECTION_STRING_REGEX);
    private static final String SECRET_PATTERN_STRING = "\\$\\{(([a-z-]+!)?[a-zA-Z0-9:/_+=.@-]+)}";
    public static final Pattern SECRET_PATTERN = Pattern.compile(SECRET_PATTERN_STRING);

    private Map<String, String> properties;

    private String engine;

    /**
     * Utility to build database instance connection configurations from Environment variables.
     *
     * @param databaseEngine canonical name of engine (e.g. "postgres", "redshift", "mysql")
     * @return List of database connection configurations. See {@link DatabaseConnectionConfig}.
     */
    public static List<DatabaseConnectionConfig> buildFromSystemEnv(String databaseEngine, java.util.Map<String, String> configOptions)
    {
        return new DatabaseConnectionConfigBuilder()
                .properties(configOptions)
                .engine(databaseEngine)
                .build();
    }

    public DatabaseConnectionConfigBuilder engine(String engine)
    {
        this.engine = engine;
        return this;
    }

    /**
     * Builder input all system properties.
     *
     * @param properties system environment properties.
     * @return database connection configuration builder. See {@link DatabaseConnectionConfigBuilder}.
     */
    public DatabaseConnectionConfigBuilder properties(final Map<String, String> properties)
    {
        this.properties = properties;
        return this;
    }

    /**
     * Builds Database instance configurations from input properties.
     *
     * @return List of database connection configurations. See {@link DatabaseConnectionConfig}.
     */
    public List<DatabaseConnectionConfig> build()
    {
        Validate.notEmpty(this.properties, "properties must not be empty");
        Validate.isTrue(properties.containsKey(DEFAULT_CONNECTION_STRING_PROPERTY), "Default connection string must be present");

        List<DatabaseConnectionConfig> databaseConnectionConfigs = new ArrayList<>();

        int numberOfCatalogs = 0;
        for (Map.Entry<String, String> property : this.properties.entrySet()) {
            final String key = property.getKey();
            final String value = property.getValue();
    
            String catalogName;
            if (DEFAULT_CONNECTION_STRING_PROPERTY.equals(key.toLowerCase())) {
                catalogName = key.toLowerCase();
            }
            else if (key.endsWith(CONNECTION_STRING_PROPERTY_SUFFIX)) {
                catalogName = key.replace(CONNECTION_STRING_PROPERTY_SUFFIX, "");
            }
            else {
                // unknown property ignore
                continue;
            }
            databaseConnectionConfigs.add(extractDatabaseConnectionConfig(catalogName, value));

            if (StringUtils.isBlank(properties.get(DEFAULT_GLUE_CONNECTION))) {
                numberOfCatalogs++; // Mux is not supported with glue. Do not count
            }
            if (numberOfCatalogs > MUX_CATALOG_LIMIT) {
                throw new AthenaConnectorException("Too many database instances in mux. Max supported is " + MUX_CATALOG_LIMIT,
                        ErrorDetails.builder().errorCode(FederationSourceErrorCode.INVALID_INPUT_EXCEPTION.toString()).build());
            }
        }

        return databaseConnectionConfigs;
    }

    private DatabaseConnectionConfig extractDatabaseConnectionConfig(final String catalogName, final String connectionString)
    {
        Matcher m = CONNECTION_STRING_PATTERN.matcher(connectionString);
        final String dbType;
        final String jdbcConnectionString;
        if (m.find() && m.groupCount() == 2) {
            dbType = m.group(1);
            jdbcConnectionString = m.group(2);
        }
        else {
            throw new AthenaConnectorException("Invalid connection String for Catalog " + catalogName,
                    ErrorDetails.builder().errorCode(FederationSourceErrorCode.INVALID_INPUT_EXCEPTION.toString()).build());
        }

        Validate.notBlank(dbType, "Database type must not be blank.");
        Validate.notBlank(jdbcConnectionString, "JDBC Connection string must not be blank.");
        Validate.isTrue(dbType.equals(this.engine), "JDBC Connection string must be prepended by correct database type.");

        final Optional<String> optionalSecretName = extractSecretName(jdbcConnectionString);

        return optionalSecretName.map(s -> new DatabaseConnectionConfig(catalogName, this.engine, jdbcConnectionString, s))
                .orElseGet(() -> new DatabaseConnectionConfig(catalogName, this.engine, jdbcConnectionString));
    }

    private Optional<String> extractSecretName(final String jdbcConnectionString)
    {
        Matcher secretMatcher = SECRET_PATTERN.matcher(jdbcConnectionString);
        boolean isValidGroupCount = secretMatcher.groupCount() == 1 || secretMatcher.groupCount() == 2;
        String secretName = null;
        if (secretMatcher.find() && isValidGroupCount) {
            secretName = secretMatcher.group(1);
        }

        return StringUtils.isBlank(secretName) ? Optional.empty() : Optional.of(secretName);
    }
}
