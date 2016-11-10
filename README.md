##Install
Create .mvn/extensions.xml

    <extensions xmlns="http://maven.apache.org/EXTENSIONS/1.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                xsi:schemaLocation="http://maven.apache.org/EXTENSIONS/1.0.0 http://maven.apache.org/xsd/core-extensions-1.0.0.xsd">
        ...
        <extension>
            <groupId>com.qoomon</groupId>
            <artifactId>maven-branch-versioning-extension</artifactId>
            <version>1.0.3-SNAPSHOT</version>
        </extension>
        ...
    </extensions>

## Usage
### disable extension by parameter
    mvn ... -DdisableBranchVersioning  ...

##Build
mvn install


##TEST
test example to test plugin
(cd test_project_multi_module; mvn help:evaluate -Dexpression=project.version) 
