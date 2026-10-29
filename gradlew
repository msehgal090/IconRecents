#!/usr/bin/env sh
# Gradle start-up script for UNIX
# Generated for Gradle 8.9 — run `gradle wrapper --gradle-version 8.9` to regenerate

##############################################################################
# NOTE: This file is a stub. To generate the full Gradle wrapper (including
# the binary gradle-wrapper.jar) run ONE of:
#
#   gradle wrapper --gradle-version 8.9
#   OR open the project in Android Studio and let it sync.
#
# After generation, commit the following to version control:
#   gradlew
#   gradlew.bat
#   gradle/wrapper/gradle-wrapper.jar
#   gradle/wrapper/gradle-wrapper.properties
##############################################################################

# Attempt to use JAVA_HOME
if [ -n "$JAVA_HOME" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi

APP_HOME="$(dirname "$0")"
GRADLE_WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

exec "$JAVACMD" -classpath "$GRADLE_WRAPPER_JAR" \
    org.gradle.wrapper.GradleWrapperMain "$@"
