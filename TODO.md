# TickLoom TODO

## Completed - March 22, 2025

- [x] Refactor build.gradle following best practices for maintainability
- [x] Add proper Maven Central POM metadata for main library publication  
- [x] Fix Maven Central validation issues for tickloom component
- [x] Design enums for ConsistencyChecker to replace string parameters
- [x] Update SIGNING_README.md to replace OSSRH references with Maven Central
- [x] Update run-cluster.sh with realistic financial transaction demo
- [x] Add Clojure test integration with Gradle build
- [x] Move history recording to QuorumKVClient or ClusterClient
  - Added HistoryRecorder class and a ClusterTest base class to implement common methods for history recording.

## In Progress
- [ ] Update Storage to have byte[] values, so that it can have generic values. 
- [ ] The API should be updated to reflect datastores like RocksDB. It should have lastKey method, which will be used by WAL like implementations
- [ ] Add WriteOptions perameter to set method which allows supporting 'sync' parameter. 

## TODO
- [ ] Move Jepsen specific history_edn handling in linearizability-checker
- [ ] Use Jepsen's generator in the SimulationRunner.
- [ ] Store mavencetral token and gpg key and write a script to setup gradle.properties required for the publish task to work.
- [ ] Add support for process initialization indicator.
