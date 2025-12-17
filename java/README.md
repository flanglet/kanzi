Build Kanzi
===========

## Gradle
```bash
./gradlew clean build
```

The generated `JAR` file is under 'build'.


## Maven
```bash
mvn clean install -DskipTests
```

The generated `JAR` file is under 'target'.

## Ant
Run 'ant build_lib' to generate a JAR file with all classes in tree excluding tests.

Run 'ant build_all' to generate a JAR file with all classes in tree including tests.



