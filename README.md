# Welcome to the SwiftMQ Client (based on Jakarta JMS)

SwiftMQ Client is an open source (Apache 2) library that contains:

- SwiftMQ JNDI client to access SwiftMQ's Federated JNDI as well as a Filesystem JNDI.
- SwiftMQ Jakarta-JMS 1.1 client to connect to a SwiftMQ Router via JNDI/JMS.
- SwiftMQ CLI administration client (command line interface).

## Documentation

Find the documentation [here](https://www.swiftmq.com/docs/docs/client/intro/).

## Obtain the Library

You can obtain SwiftMQ Client from Maven Central by adding this dependency to your `pom.xml`:

```
     <dependency>
       <groupId>com.swiftmq</groupId>
       <artifactId>swiftmq-client-jakarta</artifactId>
       <version>13.1.1</version>
     </dependency>
```

Please use always the latest version.

## Building from Source

You need Apache Maven installed on your system. Then perform a

    mvn clean install

which generates the `jar` file into the `target/` directory.

## Community Support / Reporting Bugs

Please use the [Issue Tracker](https://github.com/iitsoftware/swiftmq-client/issues) to file any bugs.

## Contributing

We appreciate and welcome any contribution to this project. Please follow these guidelines:

### We use `git flow`

If you don't know it, read the [Tutorial](https://www.atlassian.com/git/tutorials/comparing-workflows/gitflow-workflow)
first.

Our main development branch (with all latest changes merged in) is `develop`. The `master` branch is for official
releases only.

Create a new branch with `git flow` and commit your changes there. If you are ready, push your branch to this
repository. We will take care of the merge if and only if your changes are appropriate.

### License

All your contributions are under the Apache 2.0 License. If you create new files, please add the license header at the
top of the file.

## Get in Touch

Please visit our website [www.swiftmq.com](https://www.swiftmq.com).

## Copyright and Trademark

SwiftMQ is a product and (c) of IIT Software GmbH. SwiftMQ and Swiftlet are registered trademarks (R) of IIT Software
GmbH.

