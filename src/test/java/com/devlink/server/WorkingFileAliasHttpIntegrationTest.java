package com.devlink.server;

/** Applies the same real-HTTP acceptance tests to the existing broker URL. */
class WorkingFileAliasHttpIntegrationTest extends WorkingFileHttpIntegrationTest {
    @Override
    protected String workingPath() {
        return "/git/dependency-graph/working-files/";
    }
}
