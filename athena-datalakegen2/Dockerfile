# Argument for Java version, defaulting to 11
ARG JAVA_VERSION=11
# Use the specified version of Java
FROM public.ecr.aws/lambda/java:${JAVA_VERSION}

# Argument for Java tool options, defaulting to an empty string
ARG JAVA_TOOL_OPTIONS=""
# Set the JAVA_TOOL_OPTIONS environment variable for Java 17
ENV JAVA_TOOL_OPTIONS=${JAVA_TOOL_OPTIONS}

# Copy function code and runtime dependencies from Maven layout
COPY target/athena-datalakegen2-2025.22.1.jar ${LAMBDA_TASK_ROOT}
# Unpack the jar
RUN jar xf athena-datalakegen2-2025.22.1.jar

# Command can be overwritten by providing a different command in the template directly.
# No need to specify here (already defined in .yaml file because legacy and connections use different)
