# TickLoom TODO

## Completed - March 22, 2025

- [x] Refactor build.gradle following best practices for maintainability
- [x] Add proper Maven Central POM metadata for main library publication  
- [x] Fix Maven Central validation issues for tickloom component
- [x] Design enums for ConsistencyChecker to replace string parameters
- [x] Update SIGNING_README.md to replace OSSRH references with Maven Central
- [x] Update run-cluster.sh with realistic financial transaction demo
- [x] Add Clojure test integration with Gradle build

## In Progress

- [ ] Move Jepsen specific history_edn handling in linearizability-checker

## TODO

- [ ] Move history recording to QuorumKVClient or ClusterClient
- [ ] Store mavencetral token and gpg key and write a script to setup gradle.properties required for the publish task to work.
